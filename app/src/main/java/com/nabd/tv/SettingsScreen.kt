package com.nabd.tv

import androidx.annotation.StringRes

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/**
 * Settings are a dashboard, not a long list.
 *
 * The landing page shows one card per area; opening a card pushes a dedicated sub-page that only
 * contains the options of that area, grouped in readable blocks. Every option that existed before
 * is still reachable — nothing was dropped in the redesign.
 */
enum class SettingsPage(@StringRes val titleRes: Int,@StringRes val subtitleRes: Int, val icon: Ic) {
    Appearance(R.string.a_734eab8e, R.string.a_c9f86b2e, Ic.Palette),
    Display(R.string.a_cfa8a51c, R.string.a_ff5ac2c0, Ic.Display),
    Audio(R.string.a_beb4436d, R.string.a_303d962a, Ic.Speaker),
    Subtitles(R.string.a_f6f51585, R.string.a_77824275, Ic.Subtitles),
    Playback(R.string.a_57724931, R.string.a_e65601c8, Ic.Play),
    Network(R.string.a_d2fa6377, R.string.a_86da2afe, Ic.Wifi),
    Account(R.string.a_4ee8db59, R.string.a_5f193832, Ic.User),
    Profiles(R.string.a_ace4080f, R.string.a_841628fb, Ic.Profiles),
    Parental(R.string.a_a601855f, R.string.a_19a98677, Ic.Lock),
    Notifications(R.string.a_0eb7129e, R.string.a_234f00e6, Ic.Bell),
    Storage(R.string.a_2ab8654b, R.string.a_db13484a, Ic.Storage),
    Language(R.string.a_c34e6191, R.string.a_d0f404df, Ic.Globe),
    Remote(R.string.a_e7ebeee3, R.string.a_5f1c0bbb, Ic.Remote),
    About(R.string.a_859c31b9, R.string.a_8b616260, Ic.Info);

    val title: String get() = S(titleRes)
    val subtitle: String get() = S(subtitleRes)

}

private fun accentFor(p: SettingsPage): Color = when (p) {
    SettingsPage.Appearance -> Color(0xFF8C8FFF)
    SettingsPage.Display -> Color(0xFF5FB8FF)
    SettingsPage.Audio -> Color(0xFF69DDBB)
    SettingsPage.Subtitles -> Color(0xFFFFB36B)
    SettingsPage.Playback -> Color(0xFFC29BFF)
    SettingsPage.Network -> Color(0xFF57CCF0)
    SettingsPage.Account -> Color(0xFFE5B567)
    SettingsPage.Profiles -> Color(0xFF8BD3A8)
    SettingsPage.Parental -> Color(0xFFFF8E8E)
    SettingsPage.Notifications -> Color(0xFFFFD166)
    SettingsPage.Storage -> Color(0xFF9AA6FF)
    SettingsPage.Language -> Color(0xFF62E0D4)
    SettingsPage.Remote -> Color(0xFFFF9FBF)
    SettingsPage.About -> Color(0xFFA9B4C6)
}

/**
 * Number of edits made on the settings screen so far. The Store is plain SharedPreferences (not observable) and every argument
 * the pages receive keeps its identity, so Compose skips them and they keep showing old values. Every page and every [Group]
 * reads this local instead: a change invalidates exactly those readers, even when their parents were skipped, and nodes and
 * focus stay where they are (no re-creation, so the TV remote keeps its place).
 */
private val LocalSettingsRev = compositionLocalOf { 0 }

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val dash = LocalDevice.current
    val s = vm.store
    var page by remember { mutableStateOf<SettingsPage?>(null) }
    var lastPage by remember { mutableStateOf<SettingsPage?>(null) }
    val reqs = remember { SettingsPage.entries.associateWith { FocusRequester() } }

    // The Store is plain SharedPreferences, not observable: the counter makes this screen
    // recompose (and re-read every value) after a change. The comparison never happens.
    var tick by remember { mutableIntStateOf(0) }
    fun upd(f: () -> Unit) { f(); tick++ }
    if (tick < 0) return

    BackHandler { if (page != null) page = null else vm.closeSettings() }

    // TV: first entry lands on the first card instead of the back button.
    LaunchedEffect(Unit) {
        if (dash.tv && lastPage == null) {
            delay(120)
            runCatching { reqs.getValue(SettingsPage.entries.first()).requestFocus() }
        }
    }

    // Returning from a sub-page lands on the card that was opened.
    LaunchedEffect(page) {
        val target = lastPage
        if (page == null && target != null) {
            delay(70)
            runCatching { reqs[target]?.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (page == null) {
            Dashboard(
                vm, dash, reqs,
                onOpen = { p -> lastPage = p; page = p },
                onBack = { vm.closeSettings() }
            )
        } else {
            CompositionLocalProvider(LocalSettingsRev provides tick) {
                PageView(vm, s, page!!, dash, onBack = { page = null }, upd = ::upd, rev = tick)
            }
        }
    }
}

/* ───────────────────────── dashboard ───────────────────────── */

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Dashboard(
    vm: AppViewModel,
    d: Device,
    reqs: Map<SettingsPage, FocusRequester>,
    onOpen: (SettingsPage) -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(start = d.pad, end = d.pad, top = 6.dp)) {
        Row(
            Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(S(R.string.a_90b6c869), fontSize = if (d.tv) 30.sp else 24.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text(
                    S(R.string.a_8983b03b),
                    fontSize = 12.sp, color = TextSecondary, modifier = Modifier.padding(top = 2.dp)
                )
            }
            MBtn(S(R.string.a_328ddce5), filled = false) { onBack() }
        }
        Spacer(Modifier.height(16.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(d.dashboardColumns),
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(if (d.tv) 14.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(if (d.tv) 14.dp else 10.dp),
            contentPadding = PaddingValues(bottom = 28.dp)
        ) {
            items(SettingsPage.entries.toList(), key = { it.name }) { p ->
                SettingsCard(page = p, tv = d.tv, focusRequester = reqs.getValue(p)) { onOpen(p) }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsCard(
    page: SettingsPage,
    tv: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (focused) 1.03f else 1f, tween(140), label = "setZoom")
    val tint = accentFor(page)
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .scale(sc)
            .then(if (focused) Modifier.softShadow(shape, 16.dp, tint) else Modifier)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) SurfHigh else Surf)
            .border(1.dp, if (focused) tint.copy(alpha = 0.65f) else Hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = if (tv) 16.dp else 14.dp)
    ) {
        Box(
            Modifier.size(if (tv) 42.dp else 38.dp).clip(RoundedCornerShape(12.dp))
                .background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) { NIcon(page.icon, tint, if (tv) 23.dp else 21.dp) }
        Spacer(Modifier.height(12.dp))
        Text(
            page.title, fontSize = if (tv) 15.sp else 14.sp, fontWeight = FontWeight.Bold, color = Ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(
            page.subtitle, fontSize = 12.sp, lineHeight = 16.sp, color = TextSecondary, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/* ───────────────────────── sub-pages ───────────────────────── */

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PageView(
    vm: AppViewModel,
    s: Store,
    page: SettingsPage,
    d: Device,
    onBack: () -> Unit,
    upd: (() -> Unit) -> Unit,
    /** Changes after every edit so this frame is not skipped; the pages and groups inside follow [LocalSettingsRev]. */
    @Suppress("UNUSED_PARAMETER") rev: Int = 0
) {
    val tint = accentFor(page)
    Column(Modifier.fillMaxSize().padding(start = d.pad, end = d.pad, top = 6.dp)) {
        Row(
            Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            MBtn(S(R.string.a_328ddce5), filled = false) { onBack() }
            Box(
                Modifier.size(if (d.tv) 42.dp else 36.dp).clip(RoundedCornerShape(11.dp))
                    .background(tint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) { NIcon(page.icon, tint, if (d.tv) 22.dp else 19.dp) }
            Column(Modifier.weight(1f)) {
                Text(page.title, fontSize = if (d.tv) 24.sp else 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text(page.subtitle, fontSize = 12.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(14.dp))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (page) {
                SettingsPage.Appearance -> AppearancePage(vm, s, upd)
                SettingsPage.Display -> DisplayPage(vm, s, upd)
                SettingsPage.Audio -> AudioPage(vm, s, upd)
                SettingsPage.Subtitles -> SubtitlePage(vm, s, upd)
                SettingsPage.Playback -> PlaybackPage(vm, s, upd)
                SettingsPage.Network -> NetworkPage(vm, s, upd)
                SettingsPage.Account -> AccountPage(vm, s, upd)
                SettingsPage.Profiles -> ProfilePage(vm, upd)
                SettingsPage.Parental -> ParentalPage(vm, s, upd)
                SettingsPage.Notifications -> NotificationPage(vm, s, upd)
                SettingsPage.Storage -> StoragePage(vm, s, upd)
                SettingsPage.Language -> LanguagePage(vm, s, upd)
                SettingsPage.Remote -> RemotePage(vm, s, upd)
                SettingsPage.About -> AboutPage(vm, upd)
            }
        }
    }
}

/* ───────────────────────── shared controls ───────────────────────── */

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    if (LocalSettingsRev.current < 0) return   // re-run the rows below after every edit (see LocalSettingsRev)
    Column {
        Text(
            title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextSecondary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(Surf).border(1.dp, Hairline, RoundedCornerShape(16.dp))
                .padding(vertical = 4.dp),
            content = content
        )
    }
}

/** One option row; focused rows get a soft accent wash instead of a heavy container border. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Row2(
    label: String,
    value: String = "",
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(shape)
            .background(if (focused && onClick != null) Accent.copy(alpha = 0.10f) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label, fontSize = 14.sp, lineHeight = 18.sp,
            color = if (enabled) Ink else TextMuted, modifier = Modifier.weight(1f)
        )
        if (value.isNotEmpty()) {
            Spacer(Modifier.width(12.dp))
            Text(
                value, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                color = if (enabled) Accent else TextMuted, maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) =
    Row2(label, if (on) S(R.string.a_a41566d2) else S(R.string.a_bafc4458)) { onChange(!on) }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Note(text: String) {
    Text(
        text, fontSize = 11.5.sp, lineHeight = 17.sp, color = TextMuted,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

private fun langLabel(code: String): String = when (code) {
    "ar" -> S(R.string.a_9970632f); "en" -> S(R.string.a_10c4fe32); "fr" -> S(R.string.a_3c43e065); "tr" -> S(R.string.a_3c806cbc); "es" -> S(R.string.a_af7387d1)
    else -> code
}

/* ───────────────────────── page content ───────────────────────── */

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AppearancePage(vm: AppViewModel, s: Store, upd: (() -> Unit) -> Unit) {
    if (LocalSettingsRev.current < 0) return   // re-read the Store after every edit (see LocalSettingsRev)
    val cx = LocalContext.current
    Group(S(R.string.a_768ba958)) {
        Toggle(S(R.string.a_3318a3c3), vm.darkPref) { vm.setDark(it) }
        Toggle(S(R.string.amoled_label), vm.store.amoled) { vm.setAmoled(it); upd {} }
        val accentNames = listOf(R.string.accent_indigo, R.string.accent_teal, R.string.accent_rose, R.string.accent_amber, R.string.accent_green, R.string.accent_orange)
        Row2(S(R.string.accent_label), S(accentNames[Ui.accent.coerceIn(0, accentNames.lastIndex)])) {
            vm.menu = MenuRequest(S(R.string.accent_label), accentNames.mapIndexed { i, n -> Lang.str(cx, n) to { vm.setAccent(i); upd {} } })
        }
        Toggle(S(R.string.x_logos_toggle), s.titleLogos) { upd { s.titleLogos = it; Logos.enabled = it } }
        Toggle(S(R.string.x_hide_titles), vm.hidePosterTitles) { vm.applyHidePosterTitles(it) }
        Toggle(S(R.string.x_hero_rotate), vm.heroRotate) { vm.applyHeroRotate(it) }
        Row2(S(R.string.a_ca5c3624), tr(Tab.valueOf(s.startSection).labelRes)) {
            vm.menu = MenuRequest(S(R.string.a_ca5c3624), Tab.entries.map { t -> Lang.str(cx, t.labelRes) to { upd { s.startSection = t.name } } })
        }
    }
    Group(S(R.string.a_ad4583d2)) {
        Note(S(R.string.a_580ff642))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DisplayPage(vm: AppViewModel, s: Store, upd: (() -> Unit) -> Unit) {
    if (LocalSettingsRev.current < 0) return   // re-read the Store after every edit (see LocalSettingsRev)
    Group(S(R.string.a_80e8bb66)) {
        Row2(S(R.string.a_5b84a796), AspectMode.of(s.defaultAspect).label) {
            vm.menu = MenuRequest(S(R.string.a_06160737), AspectMode.entries.map { a -> a.label to { upd { s.defaultAspect = a.name } } })
        }
        Row2(S(R.string.a_326e8081), when (s.afrMode) { "off" -> S(R.string.a_0a390cb7); "switch" -> S(R.string.a_2d19b33b); else -> S(R.string.a_bbf6d1eb) }) {
            vm.menu = MenuRequest(S(R.string.a_198c2624), listOf(
                S(R.string.a_0a390cb7) to "off",
                S(R.string.a_5a20ebd4) to "seamless",
                S(R.string.a_5da14665) to "switch"
            ).map { (label, v) -> label to { upd { s.afrMode = v } } })
        }
        Row2(S(R.string.a_101bb468), OrientationPref.of(s.playerOrientation).label) {
            vm.menu = MenuRequest(S(R.string.a_7fba6576), OrientationPref.entries.map { p -> p.label to { upd { s.playerOrientation = p.name } } })
        }
    }
    Group(S(R.string.a_8d771a1c)) {
        Row2(S(R.string.a_5cfcffb9), DecoderMode.of(s.decoderMode).label) {
            vm.menu = MenuRequest(S(R.string.a_e032dd98), DecoderMode.entries.map { m -> m.label to { upd { s.decoderMode = m.name } } })
        }
        Row2(S(R.string.a_2d31f780), BufferProfile.of(s.bufferProfile).label) {
            vm.menu = MenuRequest(S(R.string.a_2d31f780), BufferProfile.entries.map { p ->
                "${p.label} — ${p.detail}" to { upd { s.bufferProfile = p.name } }
            })
        }
    }
    Note(S(R.string.a_56e719f3))
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AudioPage(vm: AppViewModel, s: Store, upd: (() -> Unit) -> Unit) {
    if (LocalSettingsRev.current < 0) return   // re-read the Store after every edit (see LocalSettingsRev)
    Group(S(R.string.a_beb4436d)) {
        Row2(S(R.string.a_6b558ffd), langLabel(s.prefAudioLang)) {
            vm.menu = MenuRequest(S(R.string.a_31c39c9d), listOf("ar", "en", "fr", "tr", "es").map { l -> langLabel(l) to { upd { s.prefAudioLang = l } } })
        }
        Row2(S(R.string.a_1b6dc5bc), "${s.audioDelayMs} ms") {
            vm.menu = MenuRequest(S(R.string.a_5ca15333), listOf(-500, -300, -200, -100, -50, 0, 50, 100, 200, 300, 500).map { v ->
                (if (v == 0) S(R.string.a_5d9f1039) else if (v > 0) S(R.string.x_delay, v) else S(R.string.x_adv, -v)) to { upd { s.audioDelayMs = v } }
            })
        }
    }
    Group(S(R.string.a_f6191aae)) {
        Row2(S(R.string.a_2d31f780), BufferProfile.of(s.bufferProfile).label) {
            vm.menu = MenuRequest(S(R.string.a_2d31f780), BufferProfile.entries.map { p ->
                "${p.label} — ${p.detail}" to { upd { s.bufferProfile = p.name } }
            })
        }
        Row2(S(R.string.a_5cfcffb9), DecoderMode.of(s.decoderMode).label) {
            vm.menu = MenuRequest(S(R.string.a_e032dd98), DecoderMode.entries.map { m -> m.label to { upd { s.decoderMode = m.name } } })
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SubtitlePage(vm: AppViewModel, s: Store, upd: (() -> Unit) -> Unit) {
    if (LocalSettingsRev.current < 0) return   // re-read the Store after every edit (see LocalSettingsRev)
    Group(S(R.string.a_bb8d46f9)) {
        Row2(S(R.string.a_17ea7fb2), langLabel(s.prefSubLang)) {
            vm.menu = MenuRequest(S(R.string.a_9eb26ff7), listOf("ar", "en", "fr", "tr", "es").map { l -> langLabel(l) to { upd { s.prefSubLang = l } } })
        }
        Row2(S(R.string.a_00c8bd7a), if (s.subEncoding == "auto") S(R.string.a_bbf6d1eb) else s.subEncoding) {
            vm.menu = MenuRequest(S(R.string.a_2701fb15), SubtitleParser.ENCODINGS.map { e ->
                (if (e == "auto") S(R.string.a_f8616546) else e) to { upd { s.subEncoding = e } }
            })
        }
        Row2(S(R.string.a_d3f7183d), "${s.subDelayMs} ms") {
            vm.menu = MenuRequest(S(R.string.a_80dd0013), listOf(-1000, -500, -250, -100, -50, 0, 50, 100, 250, 500, 1000).map { v ->
                (if (v == 0) S(R.string.a_5d9f1039) else if (v > 0) S(R.string.x_delay, v) else S(R.string.x_adv, -v)) to { upd { s.subDelayMs = v } }
            })
        }
    }
    Group(S(R.string.a_1d4f0efa)) {
        Row2(S(R.string.a_32a44b18), if (s.subColor == 0xFFFFFFFF.toInt()) S(R.string.a_ec42045b) else S(R.string.a_62b07216)) {
            upd { s.subColor = if (s.subColor == 0xFFFFFFFF.toInt()) 0xFFFFE066.toInt() else 0xFFFFFFFF.toInt() }
        }
        Toggle(S(R.string.a_8ab83264), s.subBackground) { upd { s.subBackground = it } }
        Row2(S(R.string.a_1acfde50), "${(s.subSize * 100).toInt()}%") {
            vm.menu = MenuRequest(S(R.string.a_1acfde50), (5..14).map { v -> "$v%" to { upd { s.subSize = v / 100f } } })
        }
        Row2(S(R.string.a_b8ffdfa3), S(R.string.x_from_bottom, (s.subPosition * 100).toInt())) {
            vm.menu = MenuRequest(S(R.string.a_b8ffdfa3), listOf(4, 8, 12, 18, 24, 32).map { v -> "$v%" to { upd { s.subPosition = v / 100f } } })
        }
        Toggle(S(R.string.a_6471ac58), s.subOutline) { upd { s.subOutline = it } }
    }
    Group(S(R.string.a_d4a99514)) {
        Row2(S(R.string.a_28dd6cac), if (s.osKey.isBlank()) "—" else "••••") {
            vm.prompt = PromptRequest(S(R.string.a_98cebfb2), s.osKey, secret = true) { v -> upd { s.osKey = v } }
        }
        Row2(S(R.string.a_794f68a2), s.osUser.ifBlank { "—" }) {
            vm.prompt = PromptRequest(S(R.string.a_9887f559), s.osUser) { v -> upd { s.osUser = v } }
        }
        Row2(S(R.string.a_7ba22cf7), if (s.osPass.isBlank()) "—" else "••••") {
            vm.prompt = PromptRequest(S(R.string.a_3860b4d0), "", secret = true) { v -> upd { s.osPass = v } }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlaybackPage(vm: AppViewModel, s: Store, upd: (() -> Unit) -> Unit) {
    if (LocalSettingsRev.current < 0) return   // re-read the Store after every edit (see LocalSettingsRev)
    Group(S(R.string.a_0ee01a2d)) {
        Row2(S(R.string.a_350c3a32), if (s.defaultSpeed == 1f) S(R.string.a_e7331441) else "${s.defaultSpeed}x") {
            vm.menu = MenuRequest(S(R.string.a_6bc0cff3), PLAYER_SPEEDS.map { v ->
                (if (v == 1f) S(R.string.a_ebca19a0) else "${v}x") to { upd { s.defaultSpeed = v } }
            })
        }
        Row2(S(R.string.a_608dc8b8), S(R.string.x_seconds, s.seekInterval)) {
            vm.menu = MenuRequest(S(R.string.a_6d0922bc), SEEK_INTERVALS.map { v -> S(R.string.x_seconds, v) to { upd { s.seekInterval = v } } })
        }
        Row2(S(R.string.a_fab55504), ResumeMode.of(s.resumeMode).label) {
            vm.menu = MenuRequest(S(R.string.a_fab55504), ResumeMode.entries.map { m -> m.label to { upd { s.resumeMode = m.name } } })
        }
        Row2(S(R.string.a_6cd46606), "${s.catchupOffsetHours}") {
            vm.menu = MenuRequest(S(R.string.a_e751abc5), (-3..3).map { h -> "$h" to { upd { s.catchupOffsetHours = h } } })
        }
    }
    Group(S(R.string.a_abf7e07a)) {
        Toggle(S(R.string.a_f6631716), s.rememberPosition) { upd { s.rememberPosition = it } }
        Toggle(S(R.string.a_954384b8), s.autoNext) { upd { s.autoNext = it } }
        Toggle(S(R.string.auto_skip_intro), s.autoSkip) { upd { s.autoSkip = it } }
        Toggle(S(R.string.auto_skip_credits), s.autoSkipCredits) { upd { s.autoSkipCredits = it } }
        Toggle(S(R.string.a_add97070), s.loopByDefault) { upd { s.loopByDefault = it } }
        Toggle(S(R.string.a_02217646), s.prefetchNext) { upd { s.prefetchNext = it } }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NetworkPage(vm: AppViewModel, s: Store, upd: (() -> Unit) -> Unit) {
    if (LocalSettingsRev.current < 0) return   // re-read the Store after every edit (see LocalSettingsRev)
    Group(S(R.string.a_7a466b92)) {
        Row2(if (vm.diagBusy) S(R.string.a_bf15efb7) else S(R.string.a_61809fbd)) { if (!vm.diagBusy) vm.runDiag() }
        Row2(S(R.string.a_5b5ad423)) { vm.refreshAccount() }
        Row2(S(R.string.a_b20500e6)) { vm.openSetup() }
        vm.diag?.let { Note(it) }
    }
    Group(S(R.string.a_df75cf66)) {
        Toggle(S(R.string.a_6a5e5b61), s.hideDead) { upd { s.hideDead = it; vm.clearHealth() } }
        Toggle(S(R.string.a_9f355a70), s.mergeDuplicates) { upd { s.mergeDuplicates = it; vm.listsChanged() } }
        Toggle(S(R.string.merge_vod_label), s.mergeVodDuplicates) { upd { s.mergeVodDuplicates = it; vm.listsChanged() } }
        Row2(vm.healthStatus?.let { S(R.string.x_checking, it) } ?: S(R.string.a_ba526b66)) { vm.checkHealth(true) }
        Row2(S(R.string.a_0bd6ec14)) { vm.checkHealth(false) }
        Row2(S(R.string.a_a7ab598d)) { vm.clearHealth(); upd {} }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AccountPage(vm: AppViewModel, s: Store, upd: (() -> Unit) -> Unit) {
    if (LocalSettingsRev.current < 0) return   // re-read the Store after every edit (see LocalSettingsRev)
    Group(S(R.string.a_19471ecc)) {
        Row2(S(R.string.a_a6f38e32), if (vm.hasXtream) "Xtream Codes" else S(R.string.a_57ccaa8d))
        Row2(S(R.string.a_7e8da771), runCatching { java.net.URI(vm.savedUrl).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "—")
        Row2(S(R.string.a_a750061f)) { vm.refreshAccount() }
        Row2(S(R.string.a_b20500e6)) { vm.openSetup() }
    }
    val sources = vm.savedSources()
    if (sources.size > 1) Group(S(R.string.sources_title)) {
        val cur = vm.currentSourceId()
        sources.forEach { src ->
            Row2(src.name, if (src.id == cur) S(R.string.sources_active) else "") {
                vm.menu = MenuRequest(src.name, listOf(
                    S(R.string.sources_switch) to { upd { vm.switchSource(src) } },
                    S(R.string.sources_delete) to { upd { vm.forgetSource(src) } }
                ))
            }
        }
    }
    Group(S(R.string.a_37b75114)) {
        Row2(S(R.string.prov_check)) { vm.providerCheck() }
        Row2(S(R.string.a_e9f10589), if (s.tmdbKey.isBlank()) "—" else "••••") {
            vm.prompt = PromptRequest(S(R.string.a_32f8a730), s.tmdbKey, secret = true) { v -> upd { s.tmdbKey = v }; vm.refreshTmdbCharts(true); vm.refreshUpcoming(true) }
        }
        Toggle(S(R.string.epg_toggle), s.epgEnabled) { upd { s.epgEnabled = it; if (it) vm.reloadEpg() } }
    }
    Group(S(R.string.a_d244a8a4)) {
        Row2("Client ID", if (s.traktId.isBlank()) "—" else "••••") {
            vm.prompt = PromptRequest("Trakt Client ID", s.traktId) { v -> upd { s.traktId = v } }
        }
        Row2("Client Secret", if (s.traktSecret.isBlank()) "—" else "••••") {
            vm.prompt = PromptRequest("Trakt Client Secret", "", secret = true) { v -> upd { s.traktSecret = v } }
        }
        if (Trakt.linked(s)) Row2(S(R.string.a_0fd1ce3a), S(R.string.a_fbe1a233)) { vm.traktUnlink() }
        else Row2(S(R.string.a_e6537128), S(R.string.a_060fb9f4)) { vm.traktLink() }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProfilePage(vm: AppViewModel, upd: (() -> Unit) -> Unit) {
    if (LocalSettingsRev.current < 0) return   // re-read the Store after every edit (see LocalSettingsRev)
    Group(S(R.string.a_ace4080f)) {
        Row2(S(R.string.a_7ae8d996), vm.profile.name + if (vm.profile.kids) S(R.string.a_204b9073) else "") { vm.openProfiles() }
        Row2(S(R.string.a_fafed82e)) {
            vm.prompt = PromptRequest(S(R.string.a_1a2b479a), "") { n ->
                if (n.isNotBlank()) vm.menu = MenuRequest(n.trim(), listOf(
                    S(R.string.a_1600cda7) to { vm.addProfile(n, false); upd {} },
                    S(R.string.a_f6141f05) to { vm.addProfile(n, true); upd {} }
                ))
            }
        }
        vm.profileList.filter { it.id != "main" }.forEach { pr ->
            Row2(S(R.string.x_delete_profile, pr.name)) {
                // Deleting a profile drops its favourites and history: ask first.
                vm.menu = MenuRequest(S(R.string.x_delete_profile_q, pr.name), listOf(
                    S(R.string.a_494feab7) to { vm.deleteProfile(pr.id); upd {} },
                    S(R.string.a_e776b020) to { }
                ))
            }
        }
    }
    Note(S(R.string.a_76665c67))
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ParentalPage(vm: AppViewModel, s: Store, upd: (() -> Unit) -> Unit) {
    if (LocalSettingsRev.current < 0) return   // re-read the Store after every edit (see LocalSettingsRev)
    Group(S(R.string.a_64d8acb6)) {
        Row2(if (s.hasPin) S(R.string.a_4100db18) else S(R.string.a_24f46537)) {
            val set = {
                vm.prompt = PromptRequest(S(R.string.a_d6723b7e), "", secret = true) { c ->
                    if (c.length in 4..6 && c.all { it.isDigit() }) upd { vm.setPin(c) } else vm.say(S(R.string.a_724a2256))
                }
            }
            if (s.hasPin) vm.requirePin(S(R.string.a_7094a57a)) { set() } else set()
        }
        if (s.hasPin) Row2(S(R.string.a_33f45dba)) { vm.clearPin(); upd {} }
        if (s.hasPin) Row2(S(R.string.a_9c81af58)) { vm.lockNow(); vm.say(S(R.string.a_8c9ad634)) }
    }
    Group(S(R.string.a_1711387d)) {
        Row2(S(R.string.a_ee5c76c8), when (s.parentalLevel) {
            Maturity.Kids -> S(R.string.a_babe43c8); Maturity.Teen -> S(R.string.a_4e681301); Maturity.Adult -> S(R.string.a_11fdef2d)
        }) {
            vm.menu = MenuRequest(S(R.string.a_7d47ce12), listOf(
                S(R.string.a_f92e3bc7) to { vm.setLevel(Maturity.Kids); upd {} },
                S(R.string.a_4e681301) to { vm.setLevel(Maturity.Teen); upd {} },
                S(R.string.a_11fdef2d) to { vm.setLevel(Maturity.Adult); upd {} }
            ))
        }
        Toggle(S(R.string.a_ed1dafca), s.hideLocked) { upd { s.hideLocked = it; vm.lockNow() } }
        Row2(S(R.string.a_8492be05), if (s.limitMinutes == 0) S(R.string.a_4a46afc8) else S(R.string.x_min_short, s.limitMinutes)) {
            vm.requirePin(S(R.string.a_24a4b868)) {
                vm.menu = MenuRequest(S(R.string.a_9d379939), listOf(0, 60, 120, 180, 240).map { m ->
                    (if (m == 0) S(R.string.a_7b9dba3c) else S(R.string.x_minutes, m)) to { upd { s.limitMinutes = m } }
                })
            }
        }
    }
    Group(S(R.string.a_d2effde4)) {
        Row2(S(R.string.a_fa8509bf), "${s.hiddenGroups.size}") { vm.unhideAllGroups(); upd {} }
        Row2(S(R.string.a_21100b97), "${s.hiddenChannels.size}") { vm.unhideAllChannels(); upd {} }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NotificationPage(vm: AppViewModel, s: Store, upd: (() -> Unit) -> Unit) {
    if (LocalSettingsRev.current < 0) return   // re-read the Store after every edit (see LocalSettingsRev)
    val ctx = LocalContext.current
    Group(S(R.string.a_1711387d)) {
        Toggle(S(R.string.a_69934be2), s.autoRefresh) { upd { s.autoRefresh = it } }
        Toggle(S(R.string.a_ee5a6d49), s.notifyNewEpisodes) { upd { s.notifyNewEpisodes = it } }
    }
    Group(S(R.string.a_6d0ff9f6)) {
        Toggle(S(R.string.a_0562016b), UpdatePrefs.auto(ctx)) {
            upd { UpdatePrefs.setAuto(ctx, it); UpdateWorker.sync(ctx) }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StoragePage(vm: AppViewModel, s: Store, upd: (() -> Unit) -> Unit) {
    if (LocalSettingsRev.current < 0) return   // re-read the Store after every edit (see LocalSettingsRev)
    val cx = LocalContext.current
    Group(S(R.string.a_a1d8f99b)) {
        Row2(S(R.string.a_17fd2870)) {
            val cm = cx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("nabd-backup", s.exportBackup()))
            vm.say(S(R.string.a_5a06deb7))
        }
        val saveFile = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri != null) {
                val ok = runCatching { cx.contentResolver.openOutputStream(uri)?.use { it.write(s.exportBackup().toByteArray()) } != null }.getOrDefault(false)
                vm.say(if (ok) S(R.string.a_f4145a16) else S(R.string.a_e756a616))
            }
        }
        val openFile = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                val txt = runCatching { cx.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) }.orEmpty() }.getOrDefault("")
                if (s.importBackup(txt)) { vm.say(S(R.string.a_b5a34c2f)); vm.listsChanged(); upd {} } else vm.say(S(R.string.a_52b8074d))
            }
        }
        Row2(S(R.string.a_082388b1)) { saveFile.launch("nabd-backup.json") }
        Row2(S(R.string.a_51998df2)) { openFile.launch(arrayOf("*/*")) }
        Row2(S(R.string.a_36364a37)) {
            val cm = cx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val txt = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
            if (s.importBackup(txt)) { vm.say(S(R.string.a_b5a34c2f)); upd {} } else vm.say(S(R.string.a_f9cba5ac))
        }
    }
    Group(S(R.string.a_114dc15a)) {
        Row2(S(R.string.a_8e9f9ea5), "${vm.history.size}") { vm.clearHistory(); upd {} }
        Row2(S(R.string.a_542cd645)) { vm.clearHealth(); upd {} }
    }
    Group(S(R.string.a_4d84d17d)) {
        Row2(S(R.string.a_9acfc6b6), s.filterWords.ifBlank { "—" }) {
            vm.prompt = PromptRequest(S(R.string.a_d2e082b6), s.filterWords) { v -> upd { s.filterWords = v }; vm.say(S(R.string.a_f286f2fd)) }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LanguagePage(vm: AppViewModel, s: Store, upd: (() -> Unit) -> Unit) {
    if (LocalSettingsRev.current < 0) return   // re-read the Store after every edit (see LocalSettingsRev)
    val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    Group(S(R.string.a_8a62656e)) {
        Row2(S(R.string.a_7f272eba), if (Lang.code == "ar") S(R.string.a_9970632f) else "English") {
            vm.menu = MenuRequest(S(R.string.a_7f272eba), listOf(
                S(R.string.a_9970632f) to { Lang.set(appCtx, "ar") },
                "English" to { Lang.set(appCtx, "en") }
            ))
        }
    }
    Group(S(R.string.a_076c8f81)) {
        Row2(S(R.string.a_6b558ffd), langLabel(s.prefAudioLang)) {
            vm.menu = MenuRequest(S(R.string.a_31c39c9d), listOf("ar", "en", "fr", "tr", "es").map { l -> langLabel(l) to { upd { s.prefAudioLang = l } } })
        }
        Row2(S(R.string.a_17ea7fb2), langLabel(s.prefSubLang)) {
            vm.menu = MenuRequest(S(R.string.a_9eb26ff7), listOf("ar", "en", "fr", "tr", "es").map { l -> langLabel(l) to { upd { s.prefSubLang = l } } })
        }
        Row2(S(R.string.a_00c8bd7a), if (s.subEncoding == "auto") S(R.string.a_bbf6d1eb) else s.subEncoding) {
            vm.menu = MenuRequest(S(R.string.a_2701fb15), SubtitleParser.ENCODINGS.map { e ->
                (if (e == "auto") S(R.string.a_f8616546) else e) to { upd { s.subEncoding = e } }
            })
        }
    }
    Note(S(R.string.a_ff51c5fb))
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RemotePage(vm: AppViewModel, s: Store, upd: (() -> Unit) -> Unit) {
    if (LocalSettingsRev.current < 0) return   // re-read the Store after every edit (see LocalSettingsRev)
    Group(S(R.string.a_028a5860)) {
        Row2(S(R.string.a_635631fb), UpDownAction.of(s.tvUpDown).label) {
            vm.menu = MenuRequest(S(R.string.a_776ca067), UpDownAction.entries.map { a -> a.label to { upd { s.tvUpDown = a.name } } })
        }
        Row2(S(R.string.a_608dc8b8), S(R.string.x_seconds, s.seekInterval)) {
            vm.menu = MenuRequest(S(R.string.a_6d0922bc), SEEK_INTERVALS.map { v -> S(R.string.x_seconds, v) to { upd { s.seekInterval = v } } })
        }
    }
    Group(S(R.string.a_905e8b45)) {
        Toggle(S(R.string.a_c17a4bc9), s.gesturesEnabled) { upd { s.gesturesEnabled = it } }
        Row2(S(R.string.a_d9c7345c), "${s.gestureSensitivity}×") {
            vm.menu = MenuRequest(S(R.string.a_d9c7345c), listOf(0.6f, 0.8f, 1f, 1.3f, 1.6f).map { v -> "$v×" to { upd { s.gestureSensitivity = v } } })
        }
    }
    Note(S(R.string.a_e15dda94))
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AboutPage(vm: AppViewModel, upd: (() -> Unit) -> Unit) {
    if (LocalSettingsRev.current < 0) return   // re-read the Store after every edit (see LocalSettingsRev)
    val ctx = LocalContext.current
    Group(S(R.string.a_6d0ff9f6)) {
        Row2(S(R.string.a_3560b6e8), Updater.currentName(ctx))
        Row2(if (vm.update != null) S(R.string.x_update_to, vm.update!!.tag) else S(R.string.a_967d497f)) {
            if (vm.update != null) vm.installUpdate() else vm.checkUpdate(true)
        }
        Row2(S(R.string.a_1b4a0fe4)) { vm.runDiag() }
        Row2(S(R.string.a_8f2ca978)) {
            val l = vm.problemReport()
            if (l.isEmpty()) vm.say(S(R.string.a_21377cc3)) else vm.menu = MenuRequest(S(R.string.a_a641a761), l.map { it to { } })
        }
    }
    Group(S(R.string.a_2c4cac88)) {
        Toggle(S(R.string.usage_count_label), vm.store.usageCount) { upd { vm.store.usageCount = it } }
        Row2(S(R.string.a_1717b495), if (vm.store.sentryDsn.isBlank()) S(R.string.a_bafc4458) else S(R.string.a_a41566d2)) {
            vm.prompt = PromptRequest(S(R.string.a_d7a85b68), vm.store.sentryDsn) { v -> upd { vm.store.sentryDsn = v } }
        }
        Row2(S(R.string.a_6c0e7c55)) {
            val t = CrashLog.read(ctx)
            if (t.isBlank()) vm.say(S(R.string.a_737fd8ff)) else vm.menu = MenuRequest(S(R.string.a_27433d12), listOf(
                S(R.string.a_39669a1e) to {
                    ctx.startActivity(android.content.Intent.createChooser(
                        android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_TEXT, t), S(R.string.a_0196884e)
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                S(R.string.a_3e85e97c) to { vm.say(t.takeLast(300)) },
                S(R.string.a_a5967ab8) to { CrashLog.clear(ctx) }
            ))
        }
    }
    Group(S(R.string.a_7d63544b)) {
        Note(S(R.string.a_1afc009b))
        Note(S(R.string.x_about_disclaimer))
        Note(S(R.string.x_about_privacy))
        Note(S(R.string.x_about_tmdb))
    }
}
