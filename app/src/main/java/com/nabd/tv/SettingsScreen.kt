package com.nabd.tv

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
enum class SettingsPage(val title: String, val subtitle: String, val icon: Ic) {
    Appearance("المظهر والتصميم", "الوضع الداكن وشاشة البداية", Ic.Palette),
    Display("الصورة والعرض", "نسبة العرض ومعدل التحديث وفك الترميز", Ic.Display),
    Audio("الصوت", "اللغة المفضلة وتزامن الصوت", Ic.Speaker),
    Subtitles("الترجمة واللغات", "الشكل والحجم والترميز و OpenSubtitles", Ic.Subtitles),
    Playback("التشغيل والمشغل", "السرعة والاستئناف والتكرار", Ic.Play),
    Network("الشبكة والاتصال", "السيرفر والفحص وصحة الروابط", Ic.Wifi),
    Account("الحساب والاشتراك", "المزوّد و Trakt و TMDB", Ic.User),
    Profiles("الملفات الشخصية", "إضافة وتبديل وحذف الملفات", Ic.Profiles),
    Parental("الرقابة الأبوية", "الرمز والتصنيفات والحد اليومي", Ic.Lock),
    Notifications("الإشعارات", "الحلقات الجديدة وتحديث التطبيق", Ic.Bell),
    Storage("التخزين والبيانات", "النسخ الاحتياطي والاستيراد والسجل", Ic.Storage),
    Language("اللغة والمنطقة", "لغات الصوت والترجمة والترميز", Ic.Globe),
    Remote("الريموت والتحكم", "أزرار التلفاز والإيماءات والتنقل", Ic.Remote),
    About("معلومات التطبيق والدعم", "الإصدار والتحديث والتشخيص", Ic.Info)
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
            PageView(vm, s, page!!, dash, onBack = { page = null }, upd = ::upd)
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
                Text("الإعدادات", fontSize = if (d.tv) 30.sp else 24.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text(
                    "كل ما يخص تجربة المشاهدة، منظم في مجموعات",
                    fontSize = 12.sp, color = TextSecondary, modifier = Modifier.padding(top = 2.dp)
                )
            }
            MBtn("رجوع", filled = false) { onBack() }
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
    upd: (() -> Unit) -> Unit
) {
    val tint = accentFor(page)
    Column(Modifier.fillMaxSize().padding(start = d.pad, end = d.pad, top = 6.dp)) {
        Row(
            Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            MBtn("رجوع", filled = false) { onBack() }
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
    Row2(label, if (on) "مفعّل" else "متوقف") { onChange(!on) }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Note(text: String) {
    Text(
        text, fontSize = 11.5.sp, lineHeight = 17.sp, color = TextMuted,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

private fun langLabel(code: String): String = when (code) {
    "ar" -> "العربية"; "en" -> "الإنجليزية"; "fr" -> "الفرنسية"; "tr" -> "التركية"; "es" -> "الإسبانية"
    else -> code
}

/* ───────────────────────── page content ───────────────────────── */

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AppearancePage(vm: AppViewModel, s: Store, upd: (() -> Unit) -> Unit) {
    Group("السمة") {
        Toggle("الوضع الداكن", vm.darkPref) { vm.setDark(it) }
        Row2("شاشة البداية", Tab.valueOf(s.startSection).label) {
            vm.menu = MenuRequest("شاشة البداية", Tab.entries.map { t -> t.label to { upd { s.startSection = t.name } } })
        }
    }
    Group("كيف يعمل التصميم") {
        Note("الواجهة مبنية للوضع الداكن. ألوان كل عنوان تُستخرج من بوستره وتظهر في مساحة العرض الرئيسية وأزرارها فقط، بينما تبقى خلفية التطبيق داكنة وثابتة ومريحة للعين.")
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DisplayPage(vm: AppViewModel, s: Store, upd: (() -> Unit) -> Unit) {
    Group("الصورة") {
        Row2("نسبة العرض الافتراضية", AspectMode.of(s.defaultAspect).label) {
            vm.menu = MenuRequest("نسبة العرض", AspectMode.entries.map { a -> a.label to { upd { s.defaultAspect = a.name } } })
        }
        Row2("مطابقة معدل تحديث الشاشة", when (s.afrMode) { "off" -> "إيقاف"; "switch" -> "تبديل وضع الشاشة"; else -> "تلقائي" }) {
            vm.menu = MenuRequest("مطابقة معدل التحديث", listOf(
                "إيقاف" to "off",
                "تلقائي (افتراضي)" to "seamless",
                "تبديل وضع الشاشة (للتلفاز)" to "switch"
            ).map { (label, v) -> label to { upd { s.afrMode = v } } })
        }
        Row2("اتجاه الشاشة في المشغّل", OrientationPref.of(s.playerOrientation).label) {
            vm.menu = MenuRequest("اتجاه الشاشة", OrientationPref.entries.map { p -> p.label to { upd { s.playerOrientation = p.name } } })
        }
    }
    Group("الأداء وفك الترميز") {
        Row2("تفضيل فك الترميز", DecoderMode.of(s.decoderMode).label) {
            vm.menu = MenuRequest("فك الترميز", DecoderMode.entries.map { m -> m.label to { upd { s.decoderMode = m.name } } })
        }
        Row2("مخزن المؤقت", BufferProfile.of(s.bufferProfile).label) {
            vm.menu = MenuRequest("مخزن المؤقت", BufferProfile.entries.map { p ->
                "${p.label} — ${p.detail}" to { upd { s.bufferProfile = p.name } }
            })
        }
    }
    Note("الخيارات المتاحة هي أوضاع العرض والترميز التي يدعمها المشغّل على هذا الجهاز.")
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AudioPage(vm: AppViewModel, s: Store, upd: (() -> Unit) -> Unit) {
    Group("الصوت") {
        Row2("لغة الصوت المفضلة", langLabel(s.prefAudioLang)) {
            vm.menu = MenuRequest("لغة الصوت", listOf("ar", "en", "fr", "tr", "es").map { l -> langLabel(l) to { upd { s.prefAudioLang = l } } })
        }
        Row2("تزامن الصوت", "${s.audioDelayMs} ms") {
            vm.menu = MenuRequest("تأخير الصوت", listOf(-500, -300, -200, -100, -50, 0, 50, 100, 200, 300, 500).map { v ->
                (if (v == 0) "بدون تأخير" else if (v > 0) "تأخير $v ms" else "تقديم ${-v} ms") to { upd { s.audioDelayMs = v } }
            })
        }
    }
    Group("التوافق") {
        Row2("مخزن المؤقت", BufferProfile.of(s.bufferProfile).label) {
            vm.menu = MenuRequest("مخزن المؤقت", BufferProfile.entries.map { p ->
                "${p.label} — ${p.detail}" to { upd { s.bufferProfile = p.name } }
            })
        }
        Row2("تفضيل فك الترميز", DecoderMode.of(s.decoderMode).label) {
            vm.menu = MenuRequest("فك الترميز", DecoderMode.entries.map { m -> m.label to { upd { s.decoderMode = m.name } } })
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SubtitlePage(vm: AppViewModel, s: Store, upd: (() -> Unit) -> Unit) {
    Group("اللغة والترميز") {
        Row2("لغة الترجمة المفضلة", langLabel(s.prefSubLang)) {
            vm.menu = MenuRequest("لغة الترجمة", listOf("ar", "en", "fr", "tr", "es").map { l -> langLabel(l) to { upd { s.prefSubLang = l } } })
        }
        Row2("ترميز ملف الترجمة", if (s.subEncoding == "auto") "تلقائي" else s.subEncoding) {
            vm.menu = MenuRequest("ترميز الترجمة", SubtitleParser.ENCODINGS.map { e ->
                (if (e == "auto") "تلقائي (كشف BOM/UTF-8/عربي)" else e) to { upd { s.subEncoding = e } }
            })
        }
        Row2("تأخير الترجمة الخارجية", "${s.subDelayMs} ms") {
            vm.menu = MenuRequest("تأخير الترجمة", listOf(-1000, -500, -250, -100, -50, 0, 50, 100, 250, 500, 1000).map { v ->
                (if (v == 0) "بدون تأخير" else if (v > 0) "تأخير $v ms" else "تقديم ${-v} ms") to { upd { s.subDelayMs = v } }
            })
        }
    }
    Group("الشكل") {
        Row2("لون الترجمة", if (s.subColor == 0xFFFFFFFF.toInt()) "أبيض" else "أصفر") {
            upd { s.subColor = if (s.subColor == 0xFFFFFFFF.toInt()) 0xFFFFE066.toInt() else 0xFFFFFFFF.toInt() }
        }
        Toggle("خلفية للترجمة", s.subBackground) { upd { s.subBackground = it } }
        Row2("حجم الترجمة", "${(s.subSize * 100).toInt()}%") {
            vm.menu = MenuRequest("حجم الترجمة", (5..14).map { v -> "$v%" to { upd { s.subSize = v / 100f } } })
        }
        Row2("موضع الترجمة", "${(s.subPosition * 100).toInt()}% من الأسفل") {
            vm.menu = MenuRequest("موضع الترجمة", listOf(4, 8, 12, 18, 24, 32).map { v -> "$v%" to { upd { s.subPosition = v / 100f } } })
        }
        Toggle("حدود وظل للترجمة", s.subOutline) { upd { s.subOutline = it } }
    }
    Group("ترجمة من الإنترنت (OpenSubtitles)") {
        Row2("مفتاح API", if (s.osKey.isBlank()) "—" else "••••") {
            vm.prompt = PromptRequest("مفتاح OpenSubtitles API", s.osKey) { v -> upd { s.osKey = v } }
        }
        Row2("اسم المستخدم", s.osUser.ifBlank { "—" }) {
            vm.prompt = PromptRequest("اسم مستخدم OpenSubtitles", s.osUser) { v -> upd { s.osUser = v } }
        }
        Row2("كلمة المرور", if (s.osPass.isBlank()) "—" else "••••") {
            vm.prompt = PromptRequest("كلمة مرور OpenSubtitles", "") { v -> upd { s.osPass = v } }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlaybackPage(vm: AppViewModel, s: Store, upd: (() -> Unit) -> Unit) {
    Group("التشغيل") {
        Row2("السرعة الافتراضية", if (s.defaultSpeed == 1f) "عادية" else "${s.defaultSpeed}x") {
            vm.menu = MenuRequest("سرعة التشغيل", PLAYER_SPEEDS.map { v ->
                (if (v == 1f) "عادية (1x)" else "${v}x") to { upd { s.defaultSpeed = v } }
            })
        }
        Row2("خطوة التقديم والإرجاع", "${s.seekInterval} ثانية") {
            vm.menu = MenuRequest("خطوة البحث", SEEK_INTERVALS.map { v -> "$v ثانية" to { upd { s.seekInterval = v } } })
        }
        Row2("استئناف المشاهدة", ResumeMode.of(s.resumeMode).label) {
            vm.menu = MenuRequest("استئناف المشاهدة", ResumeMode.entries.map { m -> m.label to { upd { s.resumeMode = m.name } } })
        }
        Row2("إزاحة وقت الإعادة (ساعات)", "${s.catchupOffsetHours}") {
            vm.menu = MenuRequest("إزاحة الوقت", (-3..3).map { h -> "$h" to { upd { s.catchupOffsetHours = h } } })
        }
    }
    Group("السلوك") {
        Toggle("حفظ موضع المشاهدة", s.rememberPosition) { upd { s.rememberPosition = it } }
        Toggle("تشغيل الحلقة التالية تلقائياً", s.autoNext) { upd { s.autoNext = it } }
        Toggle("تكرار المحتوى افتراضياً", s.loopByDefault) { upd { s.loopByDefault = it } }
        Toggle("تحميل مسبق للقناة التالية (HLS فقط)", s.prefetchNext) { upd { s.prefetchNext = it } }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NetworkPage(vm: AppViewModel, s: Store, upd: (() -> Unit) -> Unit) {
    Group("المصدر") {
        Row2(if (vm.diagBusy) "جارٍ فحص الحساب..." else "فحص الحساب والاتصالات") { if (!vm.diagBusy) vm.runDiag() }
        Row2("تحديث الحساب من السيرفر") { vm.refreshAccount() }
        Row2("تغيير السيرفر / الاشتراك") { vm.openSetup() }
        vm.diag?.let { Note(it) }
    }
    Group("صحة الروابط") {
        Toggle("إخفاء الروابط الميتة تلقائياً", s.hideDead) { upd { s.hideDead = it; vm.clearHealth() } }
        Toggle("دمج القنوات المكررة (HD / FHD / SD)", s.mergeDuplicates) { upd { s.mergeDuplicates = it; vm.listsChanged() } }
        Row2(vm.healthStatus?.let { "جارٍ الفحص $it" } ?: "فحص المفضلة الآن") { vm.checkHealth(true) }
        Row2("فحص التصنيف الحالي (حتى 150)") { vm.checkHealth(false) }
        Row2("مسح نتائج الفحص") { vm.clearHealth(); upd {} }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AccountPage(vm: AppViewModel, s: Store, upd: (() -> Unit) -> Unit) {
    Group("المزوّد") {
        Row2("نوع الاشتراك", if (vm.hasXtream) "Xtream Codes" else "قائمة M3U")
        Row2("السيرفر", runCatching { java.net.URI(vm.savedUrl).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "—")
        Row2("تحديث بيانات الحساب") { vm.refreshAccount() }
        Row2("تغيير السيرفر / الاشتراك") { vm.openSetup() }
    }
    Group("معلومات الأفلام") {
        Row2("مفتاح TMDB (اختياري)", if (s.tmdbKey.isBlank()) "—" else "••••") {
            vm.prompt = PromptRequest("مفتاح TMDB API", s.tmdbKey) { v -> upd { s.tmdbKey = v } }
        }
    }
    Group("مزامنة Trakt") {
        Row2("Client ID", if (s.traktId.isBlank()) "—" else "••••") {
            vm.prompt = PromptRequest("Trakt Client ID", s.traktId) { v -> upd { s.traktId = v } }
        }
        Row2("Client Secret", if (s.traktSecret.isBlank()) "—" else "••••") {
            vm.prompt = PromptRequest("Trakt Client Secret", "") { v -> upd { s.traktSecret = v } }
        }
        if (Trakt.linked(s)) Row2("الحساب", "مربوط ✓ (اضغط لفك الربط)") { vm.traktUnlink() }
        else Row2("ربط الحساب", "غير مربوط") { vm.traktLink() }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProfilePage(vm: AppViewModel, upd: (() -> Unit) -> Unit) {
    Group("الملفات الشخصية") {
        Row2("الملف الحالي", vm.profile.name + if (vm.profile.kids) " (أطفال)" else "") { vm.openProfiles() }
        Row2("إضافة ملف") {
            vm.prompt = PromptRequest("اسم الملف", "") { n ->
                if (n.isNotBlank()) vm.menu = MenuRequest(n.trim(), listOf(
                    "ملف عادي" to { vm.addProfile(n, false); upd {} },
                    "ملف أطفال" to { vm.addProfile(n, true); upd {} }
                ))
            }
        }
        vm.profileList.filter { it.id != "main" }.forEach { pr ->
            Row2("حذف: ${pr.name}") { vm.deleteProfile(pr.id); upd {} }
        }
    }
    Note("لكل ملف مفضلاته وسجل مشاهدته وإعداداته الخاصة.")
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ParentalPage(vm: AppViewModel, s: Store, upd: (() -> Unit) -> Unit) {
    Group("الرمز السري") {
        Row2(if (s.hasPin) "تغيير الرمز السري" else "تعيين رمز سري") {
            val set = {
                vm.prompt = PromptRequest("الرمز الجديد (4–6 أرقام)", "") { c ->
                    if (c.length in 4..6 && c.all { it.isDigit() }) upd { vm.setPin(c) } else vm.say("الرمز يجب أن يكون 4–6 أرقام")
                }
            }
            if (s.hasPin) vm.requirePin("أدخل الرمز الحالي") { set() } else set()
        }
        if (s.hasPin) Row2("إلغاء الرمز السري") { vm.clearPin(); upd {} }
        if (s.hasPin) Row2("قفل الآن") { vm.lockNow(); vm.say("تم القفل") }
    }
    Group("المحتوى") {
        Row2("أقصى مستوى مسموح", when (s.parentalLevel) {
            Maturity.Kids -> "أطفال"; Maturity.Teen -> "عام (بدون محتوى البالغين)"; Maturity.Adult -> "الكل"
        }) {
            vm.menu = MenuRequest("أقصى مستوى", listOf(
                "أطفال فقط" to { vm.setLevel(Maturity.Kids); upd {} },
                "عام (بدون محتوى البالغين)" to { vm.setLevel(Maturity.Teen); upd {} },
                "الكل" to { vm.setLevel(Maturity.Adult); upd {} }
            ))
        }
        Toggle("إخفاء المقفل من القوائم", s.hideLocked) { upd { s.hideLocked = it; vm.lockNow() } }
        Row2("حد المشاهدة اليومي", if (s.limitMinutes == 0) "بدون" else "${s.limitMinutes} د") {
            vm.requirePin("أدخل الرمز السري") {
                vm.menu = MenuRequest("الحد اليومي", listOf(0, 60, 120, 180, 240).map { m ->
                    (if (m == 0) "بدون حد" else "$m دقيقة") to { upd { s.limitMinutes = m } }
                })
            }
        }
    }
    Group("التصنيفات المخفية") {
        Row2("إظهار كل التصنيفات المخفية", "${s.hiddenGroups.size}") { vm.unhideAllGroups(); upd {} }
        Row2("إظهار كل القنوات المخفية", "${s.hiddenChannels.size}") { vm.unhideAllChannels(); upd {} }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NotificationPage(vm: AppViewModel, s: Store, upd: (() -> Unit) -> Unit) {
    val ctx = LocalContext.current
    Group("المحتوى") {
        Toggle("تحديث تلقائي في الخلفية", s.autoRefresh) { upd { s.autoRefresh = it } }
        Toggle("إشعار عند نزول حلقات جديدة للمسلسلات المفضلة", s.notifyNewEpisodes) { upd { s.notifyNewEpisodes = it } }
    }
    Group("التطبيق") {
        Toggle("تحديث تلقائي عند صدور إصدار جديد", UpdatePrefs.auto(ctx)) {
            upd { UpdatePrefs.setAuto(ctx, it); UpdateWorker.sync(ctx) }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StoragePage(vm: AppViewModel, s: Store, upd: (() -> Unit) -> Unit) {
    val cx = LocalContext.current
    Group("النسخ الاحتياطي") {
        Row2("نسخ المفضلة والإعدادات إلى الحافظة") {
            val cm = cx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("nabd-backup", s.exportBackup()))
            vm.say("تم النسخ. الصقها في جهاز آخر عبر \"استيراد\"")
        }
        val saveFile = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri != null) {
                val ok = runCatching { cx.contentResolver.openOutputStream(uri)?.use { it.write(s.exportBackup().toByteArray()) } != null }.getOrDefault(false)
                vm.say(if (ok) "تم حفظ النسخة الاحتياطية (مع تقدّم المشاهدة)" else "تعذّر حفظ الملف")
            }
        }
        val openFile = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                val txt = runCatching { cx.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) }.orEmpty() }.getOrDefault("")
                if (s.importBackup(txt)) { vm.say("تم الاستيراد"); vm.listsChanged(); upd {} } else vm.say("الملف ليس نسخة احتياطية صالحة")
            }
        }
        Row2("حفظ نسخة احتياطية في ملف (مع التقدّم)") { saveFile.launch("nabd-backup.json") }
        Row2("استيراد نسخة احتياطية من ملف") { openFile.launch(arrayOf("*/*")) }
        Row2("استيراد من الحافظة") {
            val cm = cx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val txt = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
            if (s.importBackup(txt)) { vm.say("تم الاستيراد"); upd {} } else vm.say("الحافظة لا تحتوي نسخة صالحة")
        }
    }
    Group("البيانات") {
        Row2("مسح سجل المشاهدة والتوصيات", "${vm.history.size}") { vm.clearHistory(); upd {} }
        Row2("مسح نتائج فحص الروابط") { vm.clearHealth(); upd {} }
    }
    Group("التنظيم") {
        Row2("كلمات تُحذف من أسماء التصنيفات", s.filterWords.ifBlank { "—" }) {
            vm.prompt = PromptRequest("كلمات مفصولة بفاصلة", s.filterWords) { v -> upd { s.filterWords = v }; vm.say("تم الحفظ") }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LanguagePage(vm: AppViewModel, s: Store, upd: (() -> Unit) -> Unit) {
    Group("اللغات المفضلة") {
        Row2("لغة الصوت المفضلة", langLabel(s.prefAudioLang)) {
            vm.menu = MenuRequest("لغة الصوت", listOf("ar", "en", "fr", "tr", "es").map { l -> langLabel(l) to { upd { s.prefAudioLang = l } } })
        }
        Row2("لغة الترجمة المفضلة", langLabel(s.prefSubLang)) {
            vm.menu = MenuRequest("لغة الترجمة", listOf("ar", "en", "fr", "tr", "es").map { l -> langLabel(l) to { upd { s.prefSubLang = l } } })
        }
        Row2("ترميز ملف الترجمة", if (s.subEncoding == "auto") "تلقائي" else s.subEncoding) {
            vm.menu = MenuRequest("ترميز الترجمة", SubtitleParser.ENCODINGS.map { e ->
                (if (e == "auto") "تلقائي (كشف BOM/UTF-8/عربي)" else e) to { upd { s.subEncoding = e } }
            })
        }
    }
    Note("تُطبَّق هذه الاختيارات تلقائياً على المسارات المتاحة في المشغّل، ويمكن تغييرها لكل فيلم أثناء التشغيل.")
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RemotePage(vm: AppViewModel, s: Store, upd: (() -> Unit) -> Unit) {
    Group("أزرار التلفاز") {
        Row2("زر أعلى/أسفل في ريموت التلفاز", UpDownAction.of(s.tvUpDown).label) {
            vm.menu = MenuRequest("زر أعلى/أسفل", UpDownAction.entries.map { a -> a.label to { upd { s.tvUpDown = a.name } } })
        }
        Row2("خطوة التقديم والإرجاع", "${s.seekInterval} ثانية") {
            vm.menu = MenuRequest("خطوة البحث", SEEK_INTERVALS.map { v -> "$v ثانية" to { upd { s.seekInterval = v } } })
        }
    }
    Group("اللمس والإيماءات") {
        Toggle("إيماءات اللمس", s.gesturesEnabled) { upd { s.gesturesEnabled = it } }
        Row2("حساسية الإيماءات", "${s.gestureSensitivity}×") {
            vm.menu = MenuRequest("حساسية الإيماءات", listOf(0.6f, 0.8f, 1f, 1.3f, 1.6f).map { v -> "$v×" to { upd { s.gestureSensitivity = v } } })
        }
    }
    Note("أثناء البث المباشر: أرقام الريموت تفتح رقم القناة مباشرة، و OK يفتح معلومات البرنامج، و CH+ / CH- للتنقل بين القنوات.")
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AboutPage(vm: AppViewModel, upd: (() -> Unit) -> Unit) {
    val ctx = LocalContext.current
    Group("التطبيق") {
        Row2("الإصدار الحالي", Updater.currentName(ctx))
        Row2(if (vm.update != null) "تحديث إلى ${vm.update!!.tag}" else "بحث عن تحديث") {
            if (vm.update != null) vm.installUpdate() else vm.checkUpdate(true)
        }
        Row2("الفحص التشخيصي للاتصال") { vm.runDiag() }
        Row2("القنوات الأكثر تقطيعاً") {
            val l = vm.problemReport()
            if (l.isEmpty()) vm.say("لا توجد قنوات متعثرة حتى الآن") else vm.menu = MenuRequest("الأكثر تقطيعاً", l.map { it to { } })
        }
    }
    Group("التشخيص") {
        Row2("إرسال تقارير الأعطال إلى Sentry (اختياري)", if (vm.store.sentryDsn.isBlank()) "متوقف" else "مفعّل") {
            vm.prompt = PromptRequest("Sentry DSN (اتركه فارغاً للإيقاف)", vm.store.sentryDsn) { v -> upd { vm.store.sentryDsn = v } }
        }
        Row2("آخر أخطاء التطبيق") {
            val t = CrashLog.read(ctx)
            if (t.isBlank()) vm.say("لا توجد أخطاء مسجّلة") else vm.menu = MenuRequest("الأخطاء", listOf(
                "مشاركة النص" to {
                    ctx.startActivity(android.content.Intent.createChooser(
                        android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_TEXT, t), "مشاركة"
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                "عرض السطور الأخيرة" to { vm.say(t.takeLast(300)) },
                "مسح السجل" to { CrashLog.clear(ctx) }
            ))
        }
    }
    Group("عن Nabd TV") {
        Note("مشغّل IPTV يعمل بالكامل على الجهاز: قنوات مباشرة، أفلام، مسلسلات، دليل برامج، تسجيل، وملفات شخصية. لا يمر أي محتوى عبر سيرفر وسيط.")
    }
}
