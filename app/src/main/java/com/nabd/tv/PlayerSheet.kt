@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.media3.common.util.UnstableApi::class
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
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Text
import kotlin.math.roundToInt

private data class SheetChoice(val title: String, val options: List<SheetOption>)
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
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }

    BackHandler {
        if (choice != null) choice = null else onClose()
    }

    fun openChoice(title: String, options: List<SheetOption>) {
        choice = SheetChoice(title, options)
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
        Modifier.fillMaxSize().background(Color(0xB3000000)).clickable { onClose() },
        contentAlignment = Alignment.CenterEnd
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.94f)
                .widthIn(max = 560.dp)
                .background(Color(0xF0101420), RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                .clickable { }   // consume taps inside the sheet
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlayerRoundButton("✕", size = 38, focusRequester = closeFocus) { onClose() }
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
                PlayerTab.entries.filter { it != PlayerTab.Episodes || hasEpisodes }.forEach { t ->
                    PlayerButton(
                        t.label,
                        active = t == tab,
                        onClick = {
                            tab = t
                            ui.sheet = t
                            ui.sheetRev++
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
                    c.options.forEach { o ->
                        SheetRow(o.label, if (o.selected) "●" else "", enabled = o.enabled) {
                            o.action()
                            choice = null
                        }
                        Spacer(Modifier.height(5.dp))
                    }
                }
            } else {
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
                            SheetRow("بداية المقطع (A)", if (ui.abStart >= 0) fmtTime(ui.abStart) else "—") { actions.setPointA() }
                            SheetRow("نهاية المقطع (B)", if (ui.abEnd > ui.abStart) fmtTime(ui.abEnd) else "—", enabled = ui.abStart >= 0) { actions.setPointB() }
                            SheetRow("إلغاء المقطع A–B", enabled = ui.abStart >= 0) { actions.clearAb() }
                            SheetRow("الحلقة السابقة", enabled = !live && vm.hasPreviousEpisode(ch)) { actions.prevEpisode() }
                            SheetRow("الحلقة التالية", enabled = !live && vm.hasNextEpisode(ch)) { actions.nextEpisode() }

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
                            SheetRow("حفظ موضع المشاهدة", if (store.rememberPosition) "مفعّل" else "متوقف") {
                                store.rememberPosition = !store.rememberPosition; actions.changed()
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
                                SheetRow("رجوع 30 ثانية") { onRewindLive(30); onClose() }
                                SheetRow("رجوع دقيقتين") { onRewindLive(120); onClose() }
                                SheetRow("العودة إلى البث المباشر") { onLiveEdge(); onClose() }
                                SheetRow("قائمة القنوات") { actions.openChannels(); onClose() }
                                SheetRow("المفضلة", if (ch.url in vm.favs) "★ مضافة" else "إضافة") { vm.toggleFav(ch) }
                                SheetRow("مصادر بديلة", "${vm.alternativesOf(ch).size}") { onAlternatives(); onClose() }
                            } else if (canSaveIntro) {
                                SheetSection("المسلسل")
                                SheetRow("حفظ نهاية المقدمة هنا", if (introEnd > 0) fmtTime(introEnd) else "—") { onSaveIntro() }
                                SheetRow("مسح المقدمة المحفوظة", enabled = introEnd > 0) { onClearIntro() }
                            }
                        }

                        PlayerTab.Audio -> {
                            SheetSection("الصوت")
                            SheetRow("تعزيز الصوت (+8 dB)", if (store.audioBoost) "مفعّل" else "متوقف") { actions.toggleAudioBoost() }
                            SheetRow("تأخير/تقديم الصوت", "${store.audioDelayMs} ms", detail = "يطبّق على سلسلة PCM في المحرك — مفيد لمطابقة الشفاه") {
                                openChoice("ضبط تزامن الصوت", listOf(-500, -300, -200, -100, -50, 0, 50, 100, 200, 300, 500).map { v ->
                                    SheetOption(
                                        when {
                                            v < 0 -> "تقديم ${-v} ms"
                                            v > 0 -> "تأخير $v ms"
                                            else -> "بدون تأخير"
                                        },
                                        v == store.audioDelayMs
                                    ) { actions.setAudioDelay(v) }
                                })
                            }
                            SheetRow("تقديم 50 ms") { actions.setAudioDelay(store.audioDelayMs - 50) }
                            SheetRow("تأخير 50 ms") { actions.setAudioDelay(store.audioDelayMs + 50) }
                            SheetRow("إعادة ضبط التزامن", enabled = store.audioDelayMs != 0) { actions.setAudioDelay(0) }
                            SheetRow("مسار الصوت", "${ui.audioTracks.size} مسار", detail = ui.audioTracks.firstOrNull()?.label.orEmpty()) {
                                val items = listOf(SheetOption("تلقائي (حسب اللغة المفضلة)") { actions.clearAudioOverride() }) +
                                    ui.audioTracks.mapIndexed { i, t ->
                                        SheetOption((if (t.selected) "● " else "") + t.label, t.selected) { actions.setAudioTrack(t) }
                                    }
                                if (items.size == 1) vm.say("لا توجد مسارات صوت متعددة في هذا البث") else openChoice("مسار الصوت", items)
                            }
                            SheetRow("الاستماع بالصوت فقط", detail = "يُبقي التشغيل في الخلفية عبر إشعار المشغّل") {
                                ctx.findActivity()?.moveTaskToBack(true) ?: vm.say("غير متاح الآن")
                            }

                            SheetSection("قيود الجهاز")
                            SheetInfo("تمرير الصوت الخام (Passthrough / Dolby Direct) غير مفعّل: يعتمد على نوع جهاز الإخراج ومخرج الصوت في Android، ولا يوفّر Media3 تبديلاً موثوقاً به لكل الأجهزة. الصوت يُفكّ ويعالَج داخل التطبيق.")
                            SheetRow("تمرير الصوت الخام", "غير مدعوم", enabled = false) { }
                            SheetInfo("تحسينات الصورة (التباين، التشبع، الـ HDR الاصطناعي) تحتاج معالج فيديو خاص بالجهاز ولا تتوفر في هذا المحرك.")
                        }

                        PlayerTab.Subtitles -> {
                            SheetSection("مصدر الترجمة")
                            SheetRow("تفعيل الترجمة", if (ui.subOn) "مفعّل" else "متوقف") { actions.setSubtitlesEnabled(!ui.subOn) }
                            SheetRow("ترجمة خارجية", ui.extSub?.name ?: "لم يتم تحميل ملف") { actions.loadSubtitle() }
                            SheetRow("إزالة الترجمة الخارجية", enabled = ui.extSub != null) { actions.removeSubtitle() }
                            ui.extError?.let { SheetInfo("خطأ: $it", danger = true) }
                            ui.extSub?.let { SheetInfo("الترميز المكتشف: ${ui.extCharset} · ${ui.extCues.size} سطر") }
                            SheetRow("مسار الترجمة المدمج", "${ui.textTracks.size} مسار") {
                                if (ui.extSub != null) actions.removeSubtitle()
                                val items = listOf(SheetOption("إيقاف الترجمة") { actions.setSubtitlesEnabled(false) }) +
                                    ui.textTracks.mapIndexed { i, t ->
                                        SheetOption((if (t.selected) "● " else "") + t.label, t.selected) { actions.selectSubtitle(i) }
                                    }
                                if (items.size == 1) vm.say("لا توجد ترجمة مدمجة في هذا البث") else openChoice("الترجمة المدمجة", items)
                            }

                            SheetSection("توقيت الترجمة")
                            SheetInfo("تأخير الترجمة يعمل على ملفات الترجمة الخارجية التي يقرأها التطبيق. الترجمة المدمجة في البث تتبع توقيت Media3 ولا يمكن إزاحتها.")
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
                            SheetRow("تقديم 50 ms", enabled = ui.extSub != null) { actions.setSubtitleDelay(store.subDelayMs - 50) }
                            SheetRow("تأخير 50 ms", enabled = ui.extSub != null) { actions.setSubtitleDelay(store.subDelayMs + 50) }
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
                            SheetRow("حدود وظل النص", if (store.subOutline) "مفعّل" else "متوقف") {
                                store.subOutline = !store.subOutline; actions.changed()
                            }
                            SheetRow("خلفية شفافة للنص", if (store.subBackground) "مفعّل" else "متوقف") {
                                store.subBackground = !store.subBackground; actions.changed()
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
                            SheetRow("تكبير +25%") { actions.zoomBy(0.25f) }
                            SheetRow("تصغير −25%", enabled = ui.zoom > 1f) { actions.zoomBy(-0.25f) }
                            SheetRow("تدوير الصورة", "${ui.rotation}°") { actions.rotate() }
                            SheetRow("إعادة ضبط الصورة") { actions.resetTransform() }
                            SheetRow("اتجاه الشاشة", OrientationPref.of(store.playerOrientation).label) {
                                openChoice("اتجاه الشاشة", OrientationPref.entries.map { p ->
                                    SheetOption(p.label, p.name == store.playerOrientation) { setOrientation(p) }
                                })
                            }
                            SheetInfo("الوضع الذكي يملأ الشاشة فقط عندما يكون القصّ بسيطاً، وإلا يُبقي الصورة كاملة بدون قصّ.")
                        }

                        PlayerTab.Gestures -> {
                            SheetSection("إيماءات اللمس (هاتف/تابلت)")
                            SheetInfo("اللمس لا يؤثر على تحكم التلفاز بالريموت. كل إيماءة يمكن إيقافها بشكل مستقل.")
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
                            SheetRow("قرص بإصبعين للتكبير", onOff(store.gesturePinch)) {
                                store.gesturePinch = !store.gesturePinch; actions.changed()
                            }
                            SheetRow("تحريك الصورة بعد التكبير", onOff(store.gesturePan)) {
                                store.gesturePan = !store.gesturePan; actions.changed()
                            }
                            SheetRow("حساسية الإيماءات", "${store.gestureSensitivity.toInt()}×") {
                                openChoice("حساسية الإيماءات", listOf(0.6f, 0.8f, 1f, 1.3f, 1.6f).map { v ->
                                    SheetOption("$v×", v == store.gestureSensitivity) {
                                        store.gestureSensitivity = v; actions.changed()
                                    }
                                })
                            }
                            SheetSection("ريموت التلفاز")
                            SheetRow("زر أعلى/أسفل", UpDownAction.of(store.tvUpDown).label) {
                                openChoice("زر أعلى/أسفل", UpDownAction.entries.map { a ->
                                    SheetOption(a.label, a.name == store.tvUpDown) {
                                        store.tvUpDown = a.name; actions.changed()
                                    }
                                })
                            }
                            SheetInfo("أزرار Media (تشغيل/إيقاف/تقديم/التالية) مدعومة دائماً، وأرقام الريموت تنتقل مباشرة إلى الق رقمها في البث المباشر.")
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
                            SheetRow("نسخ معلومات التشغيل") {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("nabd-player-info", ui.infoText))
                                vm.say("تم نسخ المعلومات")
                            }

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
                            SheetRow("إعادة بناء المحرك") { actions.recreatePlayer(); onClose() }
                            SheetRow("إعادة المحاولة") { actions.retry(); onClose() }

                            SheetSection("دعم الجهاز")
                            SheetRow("ترميز الفيديو الحالي", player.videoFormat?.codecs ?: "—", enabled = false) { }
                            SheetRow("H.264 (AVC)", if (deviceSupportsMime("video/avc")) "مدعوم" else "غير مدعوم", enabled = false) { }
                            SheetRow("H.265 (HEVC)", if (deviceSupportsMime("video/hevc")) "مدعوم" else "غير مدعوم", enabled = false) { }
                            SheetRow("AV1", if (deviceSupportsMime("video/av01")) "مدعوم" else "غير مدعوم", enabled = false) { }
                            SheetRow("حالة المصدر", if (live) vm.health.state(ch.url).name else "محتوى عند الطلب", enabled = false) { }
                            SheetInfo("محرك التشغيل: Media3 ExoPlayer 1.4.1 · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}")
                            SheetRow("مصادر بديلة", "${vm.alternativesOf(ch).size}", enabled = live) { onAlternatives(); onClose() }
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
        text, fontSize = 11.sp, color = if (danger) Color(0xFFFF9A9A) else TextSecondary,
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
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(
                when {
                    selected -> Accent
                    focused -> Color(0xFF2A3355)
                    else -> Color(0xAA171C2A)
                }
            )
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0x33FFFFFF), shape)
            .clickable(enabled = enabled, onClick = onClick)
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
            if (!detail.isNullOrBlank()) Text(detail, fontSize = 10.sp, color = TextSecondary, maxLines = 2)
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
