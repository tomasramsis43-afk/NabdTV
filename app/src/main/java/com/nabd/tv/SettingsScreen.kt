package com.nabd.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Accent, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Row2(label: String, value: String = "", onClick: () -> Unit) {
    Focusable(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 15.sp, color = Ink, modifier = Modifier.weight(1f))
            if (value.isNotEmpty()) Text(value, fontSize = 14.sp, color = Accent)
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) =
    Row2(label, if (on) "مفعّل" else "متوقف") { onChange(!on) }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s = vm.store
    BackHandler { vm.closeSettings() }
    // Local mirrors so rows redraw immediately after a change.
    var tick by remember { mutableIntStateOf(0) }
    fun upd(f: () -> Unit) { f(); tick++ }
    val observed = tick
    if (observed < 0) return

    Column(Modifier.fillMaxSize().appBackground().verticalScroll(rememberScrollState()).padding(horizontal = if (LocalDevice.current.tv) 40.dp else 16.dp, vertical = 24.dp).widthIn(max = 900.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("الإعدادات", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
            MBtn("رجوع", filled = false) { vm.closeSettings() }
        }

        Section("المظهر") {
            Row2("وضع التصميم", if (vm.darkPref) "داكن" else "فاتح") { vm.setDark(!vm.darkPref) }
        }

        Section("المصدر") {
            Row2("تحديث الحساب من السيرفر (قنوات + أفلام + مسلسلات + دليل)") { vm.refreshAccount() }
            Row2("تغيير السيرفر / الاشتراك") { vm.openSetup() }
            if (vm.hasXtream) Row2(if (vm.diagBusy) "جارٍ الفحص..." else "فحص الحساب والاتصالات") { vm.runDiag() }
            vm.diag?.let { Text(it, fontSize = 13.sp, color = Ink) }
            Row2("شاشة البداية", Tab.valueOf(s.startSection).label) {
                vm.menu = MenuRequest("شاشة البداية", Tab.entries.map { t -> t.label to { upd { s.startSection = t.name } } })
            }
        }

        Section("الملفات الشخصية") {
            Row2("الملف الحالي", vm.profile.name + if (vm.profile.kids) " (أطفال)" else "") { vm.openProfiles() }
            Row2("إضافة ملف") {
                vm.prompt = PromptRequest("اسم الملف", "") { n ->
                    if (n.isNotBlank()) vm.menu = MenuRequest(n.trim(), listOf(
                        "ملف عادي" to { vm.addProfile(n, false); tick++ },
                        "ملف أطفال" to { vm.addProfile(n, true); tick++ }
                    ))
                }
            }
            vm.profileList.filter { it.id != "main" }.forEach { pr ->
                Row2("حذف: ${pr.name}") { vm.deleteProfile(pr.id); tick++ }
            }
        }

        Section("التحكم الأبوي") {
            Row2(if (s.hasPin) "تغيير الرمز السري" else "تعيين رمز سري") {
                val set = { vm.prompt = PromptRequest("الرمز الجديد (4–6 أرقام)", "") { c -> if (c.length in 4..6 && c.all { it.isDigit() }) upd { vm.setPin(c) } else vm.say("الرمز يجب أن يكون 4–6 أرقام") } }
                if (s.hasPin) vm.requirePin("أدخل الرمز الحالي") { set() } else set()
            }
            if (s.hasPin) Row2("إلغاء الرمز السري") { vm.clearPin(); tick++ }
            Row2("أقصى مستوى مسموح", when (s.parentalLevel) { Maturity.Kids -> "أطفال"; Maturity.Teen -> "عام (بدون محتوى البالغين)"; Maturity.Adult -> "الكل" }) {
                vm.menu = MenuRequest("أقصى مستوى", listOf(
                    "أطفال فقط" to { vm.setLevel(Maturity.Kids); tick++ },
                    "عام (بدون محتوى البالغين)" to { vm.setLevel(Maturity.Teen); tick++ },
                    "الكل" to { vm.setLevel(Maturity.Adult); tick++ }
                ))
            }
            Toggle("إخفاء المقفل من القوائم", s.hideLocked) { upd { s.hideLocked = it; vm.lockNow() } }
            if (s.hasPin) Row2("قفل الآن") { vm.lockNow(); vm.say("تم القفل") }
            Row2("حد المشاهدة اليومي (يتطلب رمزاً سرياً)", if (s.limitMinutes == 0) "بدون" else "${s.limitMinutes} د") {
                vm.requirePin("أدخل الرمز السري") {
                    vm.menu = MenuRequest("الحد اليومي", listOf(0, 60, 120, 180, 240).map { m ->
                        (if (m == 0) "بدون حد" else "$m دقيقة") to { upd { s.limitMinutes = m } }
                    })
                }
            }
            Row2("إظهار كل التصنيفات المخفية", "${s.hiddenGroups.size}") { vm.unhideAllGroups(); tick++ }
            Row2("إظهار كل القنوات المخفية", "${s.hiddenChannels.size}") { vm.unhideAllChannels(); tick++ }
        }

        Section("فحص الروابط") {
            Toggle("إخفاء الروابط الميتة تلقائياً", s.hideDead) { upd { s.hideDead = it; vm.clearHealth() } }
            Toggle("دمج القنوات المكررة (HD / FHD / SD) في قناة واحدة", s.mergeDuplicates) { upd { s.mergeDuplicates = it; vm.listsChanged() } }
            Row2(vm.healthStatus?.let { "جارٍ الفحص $it" } ?: "فحص المفضلة الآن") { vm.checkHealth(true) }
            Row2("فحص التصنيف الحالي (حتى 150)") { vm.checkHealth(false) }
            Row2("مسح نتائج الفحص") { vm.clearHealth(); tick++ }
        }

        Section("الدليل والتحديث") {
            Toggle("تحديث تلقائي في الخلفية", s.autoRefresh) { upd { s.autoRefresh = it } }
            Row2("إزاحة وقت الإعادة (ساعات)", "${s.catchupOffsetHours}") {
                vm.menu = MenuRequest("إزاحة الوقت", (-3..3).map { h -> "$h" to { upd { s.catchupOffsetHours = h } } })
            }
            Row2("كلمات تُحذف من أسماء التصنيفات", s.filterWords.ifBlank { "—" }) {
                vm.prompt = PromptRequest("كلمات مفصولة بفاصلة", s.filterWords) { v -> upd { s.filterWords = v }; vm.say("تم الحفظ") }
            }
        }

        Section("الصوت والترجمة") {
            Row2("لغة الصوت المفضلة", s.prefAudioLang) {
                vm.menu = MenuRequest("لغة الصوت", listOf("ar", "en", "fr", "tr", "es").map { l -> l to { upd { s.prefAudioLang = l } } })
            }
            Row2("لغة الترجمة المفضلة", s.prefSubLang) {
                vm.menu = MenuRequest("لغة الترجمة", listOf("ar", "en", "fr", "tr", "es").map { l -> l to { upd { s.prefSubLang = l } } })
            }
            Row2("لون الترجمة", if (s.subColor == 0xFFFFFFFF.toInt()) "أبيض" else "أصفر") {
                upd { s.subColor = if (s.subColor == 0xFFFFFFFF.toInt()) 0xFFFFE066.toInt() else 0xFFFFFFFF.toInt() }
            }
            Toggle("خلفية للترجمة", s.subBackground) { upd { s.subBackground = it } }
        }

        Section("المشغّل") {
            Row2("مطابقة معدل تحديث الشاشة", when (s.afrMode) { "off" -> "إيقاف"; "switch" -> "تبديل وضع الشاشة"; else -> "تلقائي" }) {
                vm.menu = MenuRequest("مطابقة معدل التحديث", listOf(
                    "إيقاف" to "off",
                    "تلقائي (افتراضي)" to "seamless",
                    "تبديل وضع الشاشة (للتلفاز)" to "switch"
                ).map { (label, v) -> label to { upd { s.afrMode = v } } })
            }
            Row2("السرعة الافتراضية", if (s.defaultSpeed == 1f) "عادية" else "${s.defaultSpeed}x") {
                vm.menu = MenuRequest("سرعة التشغيل", PLAYER_SPEEDS.map { v ->
                    (if (v == 1f) "عادية (1x)" else "${v}x") to { upd { s.defaultSpeed = v } }
                })
            }
            Row2("نسبة العرض الافتراضية", AspectMode.of(s.defaultAspect).label) {
                vm.menu = MenuRequest("نسبة العرض", AspectMode.entries.map { a -> a.label to { upd { s.defaultAspect = a.name } } })
            }
            Row2("خطوة التقديم والإرجاع", "${s.seekInterval} ثانية") {
                vm.menu = MenuRequest("خطوة البحث", SEEK_INTERVALS.map { v -> "$v ثانية" to { upd { s.seekInterval = v } } })
            }
            Row2("استئناف المشاهدة", ResumeMode.of(s.resumeMode).label) {
                vm.menu = MenuRequest("استئناف المشاهدة", ResumeMode.entries.map { m -> m.label to { upd { s.resumeMode = m.name } } })
            }
            Toggle("حفظ موضع المشاهدة", s.rememberPosition) { upd { s.rememberPosition = it } }
            Toggle("تشغيل الحلقة التالية تلقائياً", s.autoNext) { upd { s.autoNext = it } }
            Toggle("تكرار المحتوى افتراضياً", s.loopByDefault) { upd { s.loopByDefault = it } }

            Row2("مخزن المؤقت", BufferProfile.of(s.bufferProfile).label) {
                vm.menu = MenuRequest("مخزن المؤقت", BufferProfile.entries.map { p ->
                    "${p.label} — ${p.detail}" to { upd { s.bufferProfile = p.name } }
                })
            }
            Row2("تفضيل فك الترميز", DecoderMode.of(s.decoderMode).label) {
                vm.menu = MenuRequest("فك الترميز", DecoderMode.entries.map { m -> m.label to { upd { s.decoderMode = m.name } } })
            }
            Row2("تزامن الصوت", "${s.audioDelayMs} ms") {
                vm.menu = MenuRequest("تأخير الصوت", listOf(-500, -300, -200, -100, -50, 0, 50, 100, 200, 300, 500).map { v ->
                    (if (v == 0) "بدون تأخير" else if (v > 0) "تأخير $v ms" else "تقديم ${-v} ms") to { upd { s.audioDelayMs = v } }
                })
            }
            Row2("تأخير الترجمة الخارجية", "${s.subDelayMs} ms") {
                vm.menu = MenuRequest("تأخير الترجمة", listOf(-1000, -500, -250, -100, -50, 0, 50, 100, 250, 500, 1000).map { v ->
                    (if (v == 0) "بدون تأخير" else if (v > 0) "تأخير $v ms" else "تقديم ${-v} ms") to { upd { s.subDelayMs = v } }
                })
            }
            Row2("ترميز ملف الترجمة", if (s.subEncoding == "auto") "تلقائي" else s.subEncoding) {
                vm.menu = MenuRequest("ترميز الترجمة", SubtitleParser.ENCODINGS.map { e ->
                    (if (e == "auto") "تلقائي (كشف BOM/UTF-8/عربي)" else e) to { upd { s.subEncoding = e } }
                })
            }
            Row2("حجم الترجمة", "${(s.subSize * 100).toInt()}%") {
                vm.menu = MenuRequest("حجم الترجمة", (5..14).map { v -> "$v%" to { upd { s.subSize = v / 100f } } })
            }
            Row2("موضع الترجمة", "${(s.subPosition * 100).toInt()}% من الأسفل") {
                vm.menu = MenuRequest("موضع الترجمة", listOf(4, 8, 12, 18, 24, 32).map { v -> "$v%" to { upd { s.subPosition = v / 100f } } })
            }
            Toggle("حدود وظل للترجمة", s.subOutline) { upd { s.subOutline = it } }
            Toggle("إيماءات اللمس", s.gesturesEnabled) { upd { s.gesturesEnabled = it } }
            Row2("حساسية الإيماءات", "${s.gestureSensitivity}×") {
                vm.menu = MenuRequest("حساسية الإيماءات", listOf(0.6f, 0.8f, 1f, 1.3f, 1.6f).map { v -> "$v×" to { upd { s.gestureSensitivity = v } } })
            }
            Row2("اتجاه الشاشة في المشغّل", OrientationPref.of(s.playerOrientation).label) {
                vm.menu = MenuRequest("اتجاه الشاشة", OrientationPref.entries.map { p -> p.label to { upd { s.playerOrientation = p.name } } })
            }
            Row2("زر أعلى/أسفل في ريموت التلفاز", UpDownAction.of(s.tvUpDown).label) {
                vm.menu = MenuRequest("زر أعلى/أسفل", UpDownAction.entries.map { a -> a.label to { upd { s.tvUpDown = a.name } } })
            }
        }

        Section("معلومات الأفلام") {
            Row2("مفتاح TMDB (اختياري)", if (s.tmdbKey.isBlank()) "—" else "••••") {
                vm.prompt = PromptRequest("مفتاح TMDB API", s.tmdbKey) { v -> upd { s.tmdbKey = v } }
            }
        }

        Section("مزامنة Trakt") {
            Row2("Client ID", if (s.traktId.isBlank()) "—" else "••••") {
                vm.prompt = PromptRequest("Trakt Client ID", s.traktId) { v -> upd { s.traktId = v } }
            }
            Row2("Client Secret", if (s.traktSecret.isBlank()) "—" else "••••") {
                vm.prompt = PromptRequest("Trakt Client Secret", "") { v -> upd { s.traktSecret = v } }
            }
            if (Trakt.linked(s)) Row2("الحساب", "مربوط ✓ (اضغط لفك الربط)") { vm.traktUnlink() }
            else Row2("ربط الحساب", "غير مربوط") { vm.traktLink() }
        }

        Section("ترجمة من الإنترنت (OpenSubtitles)") {
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

        Section("التطبيق") {
            val ctxU = androidx.compose.ui.platform.LocalContext.current
            Toggle("تحديث تلقائي عند صدور إصدار جديد (متوقف افتراضياً)", UpdatePrefs.auto(ctxU)) { upd { UpdatePrefs.setAuto(ctxU, it); UpdateWorker.sync(ctxU) } }
            Row2("الإصدار الحالي", Updater.currentName(androidx.compose.ui.platform.LocalContext.current)) { }
            Row2(if (vm.update != null) "تحديث إلى ${vm.update!!.tag}" else "بحث عن تحديث") {
                if (vm.update != null) vm.installUpdate() else vm.checkUpdate(true)
            }
        }

        Section("التقارير والنسخ الاحتياطي") {
            val cx = androidx.compose.ui.platform.LocalContext.current
            Toggle("تحميل مسبق للقناة التالية (HLS فقط)", s.prefetchNext) { upd { s.prefetchNext = it } }
            Row2("القنوات الأكثر تقطيعاً") {
                val l = vm.problemReport()
                if (l.isEmpty()) vm.say("لا توجد قنوات متعثرة حتى الآن") else vm.menu = MenuRequest("الأكثر تقطيعاً", l.map { it to { } })
            }
            Row2("نسخ المفضلة والإعدادات إلى الحافظة") {
                val cm = cx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("nabd-backup", s.exportBackup()))
                vm.say("تم النسخ. الصقها في جهاز آخر عبر \"استيراد\"")
            }
            Row2("استيراد من الحافظة") {
                val cm = cx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val txt = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
                if (s.importBackup(txt)) { vm.say("تم الاستيراد"); tick++ } else vm.say("الحافظة لا تحتوي نسخة صالحة")
            }
        }

        Section("التشخيص") {
            val c = androidx.compose.ui.platform.LocalContext.current
            Row2("آخر أخطاء التطبيق") {
                val t = CrashLog.read(c)
                if (t.isBlank()) vm.say("لا توجد أخطاء مسجّلة") else vm.menu = MenuRequest("الأخطاء", listOf(
                    "مشاركة النص" to {
                        c.startActivity(android.content.Intent.createChooser(
                            android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, t), "مشاركة"
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    },
                    "عرض السطور الأخيرة" to { vm.say(t.takeLast(300)) },
                    "مسح السجل" to { CrashLog.clear(c) }
                ))
            }
        }

        Section("البيانات") {
            Row2("مسح سجل المشاهدة والتوصيات", "${vm.history.size}") { vm.clearHistory(); tick++ }
        }
        Spacer(Modifier.height(30.dp))
    }
}
