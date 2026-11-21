@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.media3.common.util.UnstableApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.nabd.tv

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Text
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private data class SheetChoice(val title: String, val options: List<SheetOption>)

/** Lets a row report that it was clicked and take back focus when a choice list closes. */
private class RowFocus(val key: String?, val requester: FocusRequester, val onClicked: (String) -> Unit)
private val LocalRowFocus = compositionLocalOf<RowFocus?> { null }
private data class SheetOption(
    val label: String,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val action: () -> Unit
)

/** In-player settings: six tabs, every row performs a real action on the engine or on persistent settings. */
@Composable
fun PlayerSheet(
    ui: PlayerUiState,
    store: Store,
    vm: AppViewModel,
    actions: PlayerActions,
    player: ExoPlayer,
    ch: Channel,
    live: Boolean,
    introEnd: Long,
    canSaveIntro: Boolean,
    onClose: () -> Unit,
    onSaveIntro: () -> Unit,
    onClearIntro: () -> Unit,
    onRewindLive: (Int) -> Unit,
    onLiveEdge: () -> Unit,
    onAlternatives: () -> Unit
) {
    val ctx = LocalContext.current
    val settingsRev = ui.settingsRev
    if (settingsRev < -1) return   // keeps preference reads observable

    var tab by remember(ui.sheetRev) { mutableStateOf(ui.sheet ?: PlayerTab.Playback) }
    var choice by remember { mutableStateOf<SheetChoice?>(null) }
    val scope = rememberCoroutineScope()
    val tabFocus = remember { FocusRequester() }
    val choiceFocus = remember { FocusRequester() }
    val restoreFocus = remember { FocusRequester() }
    var lastRow by remember { mutableStateOf<String?>(null) }
    var firstRun by remember { mutableStateOf(true) }

    // Focus is placed explicitly (retrying until it sticks): on open → the active tab; when a choice list opens →
    // the selected option; when it closes → the row that opened it. Without this the D-pad had no focus after the
    // list was swapped and could not move between cells.
    LaunchedEffect(choice) {
        if (firstRun) { firstRun = false; tabFocus.focusWhenReady() }
        else if (choice != null) choiceFocus.focusWhenReady()
        else if (lastRow != null) restoreFocus.focusWhenReady() else tabFocus.focusWhenReady()
    }

    // One Back press closes the whole sheet (it used to need two or three: list → tab → sheet).
    BackHandler { onClose() }

    fun openChoice(title: String, options: List<SheetOption>) {
        choice = SheetChoice(title, options)
    }

    /** Online subtitle search (OpenSubtitles): lists results, downloads the picked one and applies it. */
    fun searchOnlineSubtitles() {
        if (store.osKey.isBlank() || store.osUser.isBlank() || store.osPass.isBlank()) {
            vm.say("أدخل مفتاح OpenSubtitles واسم المستخدم وكلمة المرور من الإعدادات"); return
        }
        val ep = vm.episodes.firstOrNull { it.url == ch.url }
        val title = OpenSubtitles.cleanQuery(if (ep != null) vm.detail?.name ?: ch.name else ch.name)
        if (title.isBlank()) { vm.say("تعذّر تحديد اسم العمل للبحث"); return }
        vm.say("جارٍ البحث عن ترجمة...")
        scope.launch {
            try {
                val res = OpenSubtitles.search(vm.repo.http, store.osKey, title, ep?.season, ep?.num)
                if (res.isEmpty()) { vm.say("لا توجد ترجمة لهذا العمل"); return@launch }
                openChoice("نتائج OpenSubtitles", res.map { r ->
                    SheetOption("[${r.lang}] ${r.label}  ·  ⬇ ${r.downloads}") {
                        vm.say("جارٍ تحميل الترجمة...")
                        scope.launch {
                            try {
                                val sub = OpenSubtitles.download(vm.repo.http, ctx, store.osKey, store.osUser, store.osPass, r.fileId, ch.url, title)
                                actions.useSubtitle(sub)
                            } catch (e: Exception) { vm.say(e.message ?: "تعذّر تحميل الترجمة") }
                        }
                    }
                })
            } catch (e: Exception) { vm.say(e.message ?: "تعذّر البحث عن الترجمة") }
        }
    }

    fun setOrientation(pref: OrientationPref) {
        store.playerOrientation = pref.name
        val act = ctx.findActivity()
        if (act != null && !isTelevision(ctx)) {
            act.requestedOrientation = when (pref) {
                OrientationPref.Auto -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                OrientationPref.Landscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                OrientationPref.Portrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }
        actions.changed()
    }

    Box(
        // pointerInput (not clickable): a clickable scrim is focusable and trapped the D-pad on a full-screen node.
        Modifier.fillMaxSize().background(Color(0xB3000000)).pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.CenterEnd
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.94f)
                .widthIn(max = 560.dp)
                .background(Color(0xF0101420), RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                .border(1.dp, OverlayLine, RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                .pointerInput(Unit) { detectTapGestures { } }   // taps inside never reach the scrim
                .focusProperties { exit = { FocusRequester.Cancel } }   // remote focus cannot leave the sheet
                .focusGroup()
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlayerRoundButton("✕", size = 38) { onClose() }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("إعدادات المشغّل", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                    Text(ch.name, fontSize = 12.sp, color = TextSecondary, maxLines = 1)
                }
                if (ui.stats) PlayerRoundButton("📊", size = 38, active = true) { ui.stats = false }
            }

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                val hasEpisodes = !live && vm.episodes.any { it.url == ch.url }
                PlayerTab.entries.filter { (it != PlayerTab.Episodes || hasEpisodes) && (it != PlayerTab.Gestures || LocalDevice.current.touch) }.forEach { t ->
                    PlayerButton(
                        t.label,
                        active = t == tab,
                        focusRequester = if (t == tab) tabFocus else null,
                        onClick = {
                            tab = t
                            ui.sheet = t
                            choice = null
                        }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            if (choice != null) {
                val c = choice!!
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlayerRoundButton("←", size = 36) { choice = null }
                        Spacer(Modifier.width(10.dp))
                        Text(c.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Accent, maxLines = 1)
                    }
                    Spacer(Modifier.height(10.dp))
                    val target = c.options.indexOfFirst { it.selected && it.enabled }.takeIf { it >= 0 }
                        ?: c.options.indexOfFirst { it.enabled }.coerceAtLeast(0)
                    c.options.forEachIndexed { i, o ->
                        SheetRow(
                            o.label, if (o.selected) "●" else "", enabled = o.enabled,
                            focusRequester = if (i == target) choiceFocus else null, rememberClick = false
                        ) {
                            o.action()
                            choice = null
                        }
                        Spacer(Modifier.height(5.dp))
                    }
                }
            } else {
                CompositionLocalProvider(LocalRowFocus provides RowFocus(lastRow, restoreFocus) { lastRow = it }) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(end = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    when (tab) {
                        PlayerTab.Episodes -> {
                            // Netflix / Kodi style episode list: ▶ playing · ✓ watched · % in progress.
                            val eps = vm.episodes
                            val seasons = eps.map { it.season }.distinct().sorted()
                            var sSel by remember(ch.url) { mutableStateOf(eps.firstOrNull { it.url == ch.url }?.season ?: seasons.firstOrNull() ?: 1) }
                            if (seasons.size > 1) {
                                SheetRow("الموسم", "الموسم $sSel") {
                                    openChoice("اختر الموسم", seasons.map { n -> SheetOption("الموسم $n", n == sSel) { sSel = n } })
                                }
                            }
                            SheetSection("حلقات الموسم $sSel")
                            eps.filter { it.season == sSel }.forEach { e ->
                                val h = store.historyOf(e.url)
                                val playingNow = e.url == ch.url
                                val value = when {
                                    playingNow -> "▶ يعمل الآن"
                                    h?.finished == true -> "✓ شوهدت"
                                    h != null && h.progress > 0.02f -> "${(h.progress * 100).toInt()}%"
                                    else -> ""
                                }
                                val t = e.title.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
                                SheetRow("حلقة ${e.num}$t", value, selected = playingNow) {
                                    val d = vm.detail
                                    if (!playingNow && d != null) { vm.playEpisode(d, e); onClose() }
                                }
                            }
                        }
                        PlayerTab.Playback -> {
                            SheetSection("التحكم بالتشغيل")
                            SheetRow("السرعة", if (ui.speed == 1f) "عادية" else "${ui.speed}x", detail = "تُحفظ كسرعة افتراضية للمحتوى القادم") {
                                openChoice("سرعة التشغيل", PLAYER_SPEEDS.map { v ->
                                    SheetOption(if (v == 1f) "عادية (1x)" else "${v}x", v == ui.speed) { actions.setSpeed(v) }
                                })
                            }
                            SheetRow("خطوة التقديم والإرجاع", "${store.seekInterval} ثانية") {
                                openChoice("خطوة البحث", SEEK_INTERVALS.map { v ->
                                    SheetOption("$v ثانية", v == store.seekInterval) {
                                        store.seekInterval = v
                                        ui.seekInterval = v
                                        actions.changed()
                                    }
                                })
                            }
                            SheetRow("الانتقال إلى وقت محدد", fmtTime(ui.position)) { ui.showJump = true; onClose() }
                            SheetRow("إعادة من البداية") { actions.restart(); onClose() }
                            SheetRow("تكرار المحتوى", if (ui.loop) "مفعّل" else "متوقف") { actions.setLoop(!ui.loop) }

                            SheetSection("سلوك التشغيل")
                            SheetRow("تشغيل الحلقة التالية تلقائياً", if (store.autoNext) "مفعّل" else "متوقف") {
                                store.autoNext = !store.autoNext; actions.changed()
                            }
                            SheetRow("استئناف المشاهدة", ResumeMode.of(store.resumeMode).label) {
                                openChoice("استئناف المشاهدة", ResumeMode.entries.map { m ->
                                    SheetOption(m.label, m.name == store.resumeMode) {
                                        store.resumeMode = m.name; actions.changed()
                                    }
                                })
                            }
                            val sleepLabel = when {
                                ui.sleepEnd -> "بعد انتهاء المحتوى"
                                vm.sleepAt > 0 -> "${((vm.sleepAt - System.currentTimeMillis()).coerceAtLeast(0) / 60000L) + 1} د متبقية"
                                else -> "متوقف"
                            }
                            SheetRow("مؤقت النوم", sleepLabel) {
                                openChoice("إيقاف التشغيل بعد", listOf(15, 30, 45, 60, 90, 120).map { m ->
                                    SheetOption("$m دقيقة") { actions.setSleep(m) }
                                } + listOf(
                                    SheetOption("بعد انتهاء المحتوى الحالي") { actions.setSleepEnd() },
                                    SheetOption("إلغاء المؤقت") { actions.cancelSleep() }
                                ))
                            }

                            if (live) {
                                SheetSection("البث المباشر")
                                SheetRow("العودة إلى البث المباشر") { onLiveEdge(); onClose() }
                                SheetRow("قائمة القنوات") { actions.openChannels(); onClose() }
                                SheetRow("المفضلة", if (ch.url in vm.favs) "★ مضافة" else "إضافة") { vm.toggleFav(ch) }
                                SheetRow("مصادر بديلة", "${vm.alternativesOf(ch).size}") { onAlternatives(); onClose() }
                                if (!LocalDevice.current.touch) SheetRow("زر أعلى/أسفل", UpDownAction.of(store.tvUpDown).label) {
                                    openChoice("زر أعلى/أسفل", UpDownAction.entries.map { a ->
                                        SheetOption(a.label, a.name == store.tvUpDown) { store.tvUpDown = a.name; actions.changed() }
                                    })
                                }
                            } else if (canSaveIntro) {
                                SheetSection("المسلسل")
                                SheetRow("حفظ نهاية المقدمة هنا", if (introEnd > 0) fmtTime(introEnd) else "—") { onSaveIntro() }
                                SheetRow("مسح المقدمة المحفوظة", enabled = introEnd > 0) { onClearIntro() }
                            }
                        }

                        PlayerTab.Audio -> {
                            SheetSection("الصوت")
                            SheetRow("تعزيز الصوت (+8 dB)", if (store.audioBoost) "مفعّل" else "متوقف") { actions.toggleAudioBoost() }
                            SheetRow("مسار الصوت", "${ui.audioTracks.size} مسار", detail = ui.audioTracks.firstOrNull()?.label.orEmpty()) {
                                val items = listOf(SheetOption("تلقائي (حسب اللغة المفضلة)") { actions.clearAudioOverride() }) +
                                    ui.audioTracks.mapIndexed { i, t ->
                                        SheetOption(t.label, t.selected) { actions.setAudioTrack(t) }
                                    }
                                if (items.size == 1) vm.say("لا توجد مسارات صوت متعددة في هذا البث") else openChoice("مسار الصوت", items)
                            }
                        }

                        PlayerTab.Subtitles -> {
                            SheetSection("مصدر الترجمة")
                            SheetRow("تفعيل الترجمة", if (ui.subOn) "مفعّل" else "متوقف") { actions.setSubtitlesEnabled(!ui.subOn) }
                            SheetRow("ترجمة خارجية", ui.extSub?.name ?: "لم يتم تحميل ملف") { actions.loadSubtitle() }
                            SheetRow("بحث عن ترجمة على الإنترنت", "OpenSubtitles", enabled = !live) { searchOnlineSubtitles() }
                            SheetRow("إزالة الترجمة الخارجية", enabled = ui.extSub != null) { actions.removeSubtitle() }
                            ui.extError?.let { SheetInfo("خطأ: $it", danger = true) }
                            SheetRow("مسار الترجمة المدمج", "${ui.textTracks.size} مسار") {
                                if (ui.extSub != null) actions.removeSubtitle()
                                val items = listOf(SheetOption("إيقاف الترجمة") { actions.setSubtitlesEnabled(false) }) +
                                    ui.textTracks.mapIndexed { i, t ->
                                        SheetOption(t.label, t.selected) { actions.selectSubtitle(i) }
                                    }
                                if (items.size == 1) vm.say("لا توجد ترجمة مدمجة في هذا البث") else openChoice("الترجمة المدمجة", items)
                            }

                            SheetRow("تأخير الترجمة", "${store.subDelayMs} ms", enabled = ui.extSub != null) {
                                openChoice("تأخير الترجمة", listOf(-1000, -500, -250, -100, -50, 0, 50, 100, 250, 500, 1000).map { v ->
                                    SheetOption(
                                        when {
                                            v < 0 -> "تقديم ${-v} ms"
                                            v > 0 -> "تأخير $v ms"
                                            else -> "بدون تأخير"
                                        },
                                        v == store.subDelayMs
                                    ) { actions.setSubtitleDelay(v) }
                                })
                            }
                            SheetRow("ترميز الملف", if (store.subEncoding == "auto") "تلقائي" else store.subEncoding, enabled = ui.extSub != null) {
                                openChoice("ترميز الترجمة", SubtitleParser.ENCODINGS.map { e ->
                                    SheetOption(if (e == "auto") "تلقائي (كشف BOM/UTF-8/عربي)" else e, e == store.subEncoding) {
                                        actions.setSubtitleEncoding(e)
                                    }
                                })
                            }

                            SheetSection("المظهر")
                            SheetRow("الحجم", "${(store.subSize * 100).roundToInt()}%") {
                                openChoice("حجم الترجمة", (5..14).map { v ->
                                    SheetOption("$v%", v == (store.subSize * 100).roundToInt()) {
                                        store.subSize = v / 100f; actions.changed()
                                    }
                                })
                            }
                            SheetRow("الموضع من الأسفل", "${(store.subPosition * 100).roundToInt()}%") {
                                openChoice("موضع الترجمة", listOf(4, 8, 12, 18, 24, 32, 42).map { v ->
                                    SheetOption("$v%", v == (store.subPosition * 100).roundToInt()) {
                                        store.subPosition = v / 100f; actions.changed()
                                    }
                                })
                            }
                            SheetRow("اللون", SUBTITLE_COLORS.firstOrNull { it.second == store.subColor }?.first ?: "مخصص") {
                                openChoice("لون الترجمة", SUBTITLE_COLORS.map { (name, argb) ->
                                    SheetOption(name, argb == store.subColor) { store.subColor = argb; actions.changed() }
                                })
                            }
                        }

                        PlayerTab.Display -> {
                            SheetSection("الصورة")
                            SheetRow("نسبة العرض", ui.aspect.label) {
                                openChoice("نسبة العرض", AspectMode.entries.map { a ->
                                    SheetOption(a.label, a == ui.aspect) { actions.setAspect(a) }
                                })
                            }
                            SheetRow("تكبير الصورة", "${(ui.zoom * 100).roundToInt()}%") {
                                openChoice("مستوى التكبير", listOf(100, 125, 150, 200, 250, 300, 400).map { v ->
                                    SheetOption("$v%", v == (ui.zoom * 100).roundToInt()) {
                                        actions.resetZoom(); actions.zoomBy(v / 100f - 1f)
                                    }
                                })
                            }
                            SheetRow("إعادة ضبط الصورة") { actions.resetTransform() }
                            SheetRow("اتجاه الشاشة", OrientationPref.of(store.playerOrientation).label) {
                                openChoice("اتجاه الشاشة", OrientationPref.entries.map { p ->
                                    SheetOption(p.label, p.name == store.playerOrientation) { setOrientation(p) }
                                })
                            }
                        }

                        PlayerTab.Gestures -> {
                            SheetSection("إيماءات اللمس (هاتف/تابلت)")
                            SheetRow("تفعيل الإيماءات", if (store.gesturesEnabled) "مفعّل" else "متوقف") {
                                store.gesturesEnabled = !store.gesturesEnabled; actions.changed()
                            }
                            SheetRow("سحب أفقي للبحث", onOff(store.gestureSeek)) {
                                store.gestureSeek = !store.gestureSeek; actions.changed()
                            }
                            SheetRow("سحب على اليسار للسطوع", onOff(store.gestureBrightness)) {
                                store.gestureBrightness = !store.gestureBrightness; actions.changed()
                            }
                            SheetRow("سحب على اليمين للصوت", onOff(store.gestureVolume)) {
                                store.gestureVolume = !store.gestureVolume; actions.changed()
                            }
                            SheetRow("نقرة مزدوجة للبحث/التشغيل", onOff(store.gestureDoubleTap)) {
                                store.gestureDoubleTap = !store.gestureDoubleTap; actions.changed()
                            }
                        }

                        PlayerTab.Info -> {
                            SheetSection("حالة التشغيل")
                            Box(
                                Modifier.fillMaxWidth().background(Color(0x99101520), RoundedCornerShape(12.dp)).padding(12.dp)
                            ) {
                                Text(ui.infoText.ifBlank { "جارٍ جمع المعلومات..." }, fontSize = 12.sp, color = Color(0xFFC9D3E4))
                            }
                            Spacer(Modifier.height(6.dp))
                            SheetRow("إظهار لوحة الإحصاءات فوق الفيديو", if (ui.stats) "مفعّل" else "متوقف") { ui.stats = !ui.stats }
                            SheetSection("الأداء (يُعاد بناء المحرك عند التغيير)")
                            SheetRow("مخزن المؤقت", BufferProfile.of(store.bufferProfile).label, detail = BufferProfile.of(store.bufferProfile).detail) {
                                openChoice("مخزن المؤقت", BufferProfile.entries.map { p ->
                                    SheetOption(p.label, p.name == store.bufferProfile) {
                                        store.bufferProfile = p.name
                                        actions.changed()
                                        actions.recreatePlayer()
                                    }
                                })
                            }
                            SheetRow("تفضيل فك الترميز", DecoderMode.of(store.decoderMode).label, detail = "يفضّل البرمجي عند الحاجة، ومع تعطيل تلقائي للبديل عند غياب المُفكّك") {
                                openChoice("فك الترميز", DecoderMode.entries.map { m ->
                                    SheetOption(m.label, m.name == store.decoderMode) {
                                        store.decoderMode = m.name
                                        actions.changed()
                                        actions.recreatePlayer()
                                    }
                                })
                            }
                            SheetRow("إعادة المحاولة") { actions.retry(); onClose() }

                            SheetRow("نافذة عائمة (PiP)") { actions.enterPip(); onClose() }
                            SheetRow("فتح في مشغّل خارجي") { actions.openExternal(); onClose() }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
                }
            }
        }
    }
}

private fun onOff(on: Boolean): String = if (on) "مفعّل" else "متوقف"

@Composable
private fun SheetSection(title: String) {
    Text(
        title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Accent,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp), maxLines = 1
    )
}

@Composable
private fun SheetInfo(text: String, danger: Boolean = false) {
    Text(
        text, fontSize = 12.sp, color = if (danger) Color(0xFFFF9A9A) else TextSecondary,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun SheetRow(
    label: String,
    value: String = "",
    detail: String? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    rememberClick: Boolean = true,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val rf = LocalRowFocus.current
    val fr = focusRequester ?: if (rememberClick && rf != null && rf.key == label) rf.requester else null
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (fr != null) Modifier.focusRequester(fr) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(
                when {
                    selected -> AccentFill
                    focused -> Color(0xFF2A3355)
                    else -> Color(0xAA171C2A)
                }
            )
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0x33FFFFFF), shape)
            .clickable(enabled = enabled) { if (rememberClick) rf?.onClicked(label); onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label, fontSize = 14.sp, maxLines = 1,
                fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.Medium,
                color = when {
                    !enabled -> Color(0x66FFFFFF)
                    selected -> OnAccent
                    else -> Color.White
                }
            )
            if (!detail.isNullOrBlank()) Text(detail, fontSize = 12.sp, color = TextSecondary, maxLines = 2)
        }
        if (value.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                value, fontSize = 12.sp, maxLines = 1, fontWeight = FontWeight.Bold,
                color = if (selected) OnAccent else Accent
            )
        }
    }
}
