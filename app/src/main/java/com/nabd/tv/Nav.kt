package com.nabd.tv

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

fun tabIcon(t: Tab): Ic = when (t) {
    Tab.Home -> Ic.Home; Tab.Live -> Ic.Live; Tab.Movies -> Ic.Movies
    Tab.Series -> Ic.Series; Tab.Guide -> Ic.Guide; Tab.Recordings -> Ic.Rec
}

fun visibleTabs(vm: AppViewModel): List<Tab> = Tab.entries.filter { (it != Tab.Movies && it != Tab.Series) || vm.hasXtream }

/**
 * The whole main navigation is one horizontal app bar: brand, section names, search and account actions.
 * Sections are plain text with a soft focus wash and an animated underline for the selected one —
 * no rails, no boxes around each item.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppBar(vm: AppViewModel, d: Device) {
    var searchOpen by remember { mutableStateOf(false) }
    val wide = d.widthDp >= 780
    val tabs = visibleTabs(vm)
    val reqs = remember { Tab.entries.associateWith { FocusRequester() } }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!said.isNullOrBlank()) { vm.query = said; searchOpen = true }
    }
    fun startVoice() {
        runCatching {
            voice.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar")
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "قل اسم القناة أو الفيلم أو المسلسل")
            )
        }.onFailure { vm.say("البحث الصوتي غير مدعوم على هذا الجهاز") }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = d.pad, vertical = if (d.tv) 8.dp else 6.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = d.control),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (wide) 16.dp else 8.dp)
        ) {
            BrandMark(d, compact = !wide)
            if (wide) {
                SectionBar(vm, d, tabs, reqs, Modifier.weight(1f))
                SearchField(vm.query, { vm.query = it })
                IconBtn(Ic.Mic, { startVoice() })
                IconBtn(Ic.Refresh, { vm.refresh() })
            } else {
                Spacer(Modifier.weight(1f))
                IconBtn(Ic.Mic, { startVoice() }, size = 40.dp)
                IconBtn(if (searchOpen) Ic.Close else Ic.Search, { searchOpen = !searchOpen }, size = 40.dp)
                IconBtn(Ic.Refresh, { vm.refresh() }, size = 40.dp)
            }
            if (vm.profileList.size > 1) {
                IconBtn(Ic.User, { vm.openProfiles() }, size = if (wide) null else 40.dp)
            }
            IconBtn(Ic.Settings, { vm.openSettings() }, size = if (wide) null else 40.dp)
        }
        if (!wide) {
            SectionBar(vm, d, tabs, reqs, Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        if (!wide && (searchOpen || vm.query.isNotEmpty())) {
            Spacer(Modifier.height(8.dp))
            SearchField(vm.query, { vm.query = it }, fillWidth = true)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BrandMark(d: Device, compact: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(
            Modifier.size(if (d.tv) 36.dp else 32.dp).clip(RoundedCornerShape(11.dp))
                .background(Brush.linearGradient(listOf(Accent, Accent2))),
            contentAlignment = Alignment.Center
        ) {
            Text("N", color = Color(0xFF0A0B10), fontWeight = FontWeight.Black, fontSize = if (d.tv) 19.sp else 17.sp)
        }
        if (!compact || d.tv) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Nabd", fontSize = if (d.tv) 20.sp else 18.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("TV", fontSize = if (d.tv) 12.sp else 11.sp, fontWeight = FontWeight.Bold, color = Accent,
                    modifier = Modifier.padding(start = 3.dp, bottom = 3.dp))
            }
        }
    }
}

/**
 * Horizontal section names. Scrolling on narrow screens; the focused item gets a faint rounded wash,
 * the selected one an accent underline that animates in. D-pad focus is grouped so the bar behaves
 * like a single row and Down leaves it straight into the content.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun SectionBar(
    vm: AppViewModel,
    d: Device,
    tabs: List<Tab>,
    reqs: Map<Tab, FocusRequester>,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = modifier
            .focusProperties {
                enter = { reqs[vm.tab] ?: FocusRequester.Default }
                exit = { dir ->
                    if (dir == FocusDirection.Down) vm.contentEntry() ?: FocusRequester.Default
                    else FocusRequester.Default
                }
            }
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(if (d.tv) 4.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(tabs.size) { i ->
            val t = tabs[i]
            SectionItem(
                label = t.label,
                selected = vm.tab == t,
                tv = d.tv,
                focusRequester = reqs.getValue(t),
                onClick = { vm.selectTab(t) }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SectionItem(
    label: String,
    selected: Boolean,
    tv: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val underline by animateDpAsState(if (selected) 22.dp else 0.dp, tween(220), label = "tabLine")
    Column(
        Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Accent.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = if (tv) 15.dp else 11.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            label,
            fontSize = if (tv) 15.sp else 13.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = when {
                selected -> Ink
                focused -> Ink
                else -> TextSecondary
            },
            maxLines = 1
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.width(underline).height(3.dp).clip(CircleShape)
                .background(if (selected) Accent else Accent.copy(alpha = 0.55f))
        )
    }
}
