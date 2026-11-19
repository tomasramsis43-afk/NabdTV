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

        Section("معلومات الأفلام") {
            Row2("مفتاح TMDB (اختياري)", if (s.tmdbKey.isBlank()) "—" else "••••") {
                vm.prompt = PromptRequest("مفتاح TMDB API", s.tmdbKey) { v -> upd { s.tmdbKey = v } }
            }
        }

        Section("التطبيق") {
            Row2("الإصدار الحالي", Updater.currentName(androidx.compose.ui.platform.LocalContext.current)) { }
            Row2(if (vm.update != null) "تحديث إلى ${vm.update!!.tag}" else "بحث عن تحديث") {
                if (vm.update != null) vm.installUpdate() else vm.checkUpdate(true)
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
