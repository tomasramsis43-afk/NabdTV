package com.nabd.tv

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.ExperimentalComposeUiApi
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

/** Same navy pill and highlight logic everywhere; only its position and size change per device. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NavItem(ic: Ic, label: String, selected: Boolean, showLabel: Boolean, modifier: Modifier = Modifier, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (focused) 1.1f else 1f, tween(140), label = "navZoom")
    val shape = RoundedCornerShape(22.dp)
    val tint = if (selected || focused) Color.White else Color.White.copy(alpha = 0.62f)
    Column(
        modifier.then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier).scale(sc).onFocusChanged { focused = it.isFocused }.clip(shape)
            .background(if (selected) Color.White.copy(alpha = 0.22f) else if (focused) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
            .clickable(onClick = onClick).padding(vertical = 9.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        NIcon(ic, tint, 24.dp)
        if (showLabel) Text(label, fontSize = 10.sp, color = tint, maxLines = 1, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

private fun navBrush() = Brush.verticalGradient(listOf(Navy, NavyDeep))

/** Vertical pill (TV, tablet, landscape phone). */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun NavRail(vm: AppViewModel, d: Device) {
    val compact = d.nav == NavMode.RailCompact
    val shape = RoundedCornerShape(if (compact) 28.dp else 36.dp)
    val reqs = remember { Tab.entries.associateWith { FocusRequester() } }
    Column(
        Modifier
            .focusProperties {
                // Coming back from the page lands on the current section; leaving the rail goes to the remembered card.
                enter = { reqs[vm.tab] ?: FocusRequester.Default }
                exit = { dir ->
                    if (dir == FocusDirection.Left || dir == FocusDirection.Right) vm.contentEntry() ?: FocusRequester.Default
                    else FocusRequester.Default
                }
            }
            .focusGroup()
            .padding(start = if (compact) 8.dp else 14.dp, end = if (compact) 4.dp else 6.dp, top = 12.dp, bottom = 12.dp)
            .fillMaxHeight().width(if (compact) 54.dp else 84.dp)
            .softShadow(shape, 16.dp).clip(shape).background(navBrush()).padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(Modifier.size(if (compact) 32.dp else 40.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) {
                Text("N", color = OnAccent, fontWeight = FontWeight.Bold, fontSize = if (compact) 15.sp else 18.sp)
            }
            Spacer(Modifier.height(8.dp))
            visibleTabs(vm).forEach { t ->
                NavItem(tabIcon(t), t.label, vm.tab == t, showLabel = !compact, modifier = Modifier.fillMaxWidth(), focusRequester = reqs[t]) { vm.selectTab(t) }
            }
        }
        Spacer(Modifier.height(6.dp))
        if (vm.profileList.size > 1) NavItem(Ic.User, vm.profile.name, false, !compact, Modifier.fillMaxWidth()) { vm.openProfiles() }
        NavItem(Ic.Settings, "الإعدادات", false, !compact, Modifier.fillMaxWidth()) { vm.openSettings() }
    }
}

/** Floating pill at the bottom (phone, portrait). Only the selected item shows its label. */
@Composable
fun BottomBar(vm: AppViewModel) {
    val shape = RoundedCornerShape(32.dp)
    Row(
        Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth()
            .softShadow(shape, 16.dp).clip(shape).background(navBrush()).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
    ) {
        visibleTabs(vm).forEach { t ->
            NavItem(tabIcon(t), t.label, vm.tab == t, showLabel = vm.tab == t, modifier = Modifier.weight(1f)) { vm.selectTab(t) }
        }
    }
}

/** Page header: serif-accent title on one side, actions on the other. Search collapses to an icon on narrow screens. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun Header(vm: AppViewModel, d: Device, title: String, accent: String) {
    var searchOpen by remember { mutableStateOf(false) }
    val narrow = d.widthDp < 600 || (d.touch && d.portrait)
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
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentTitle(title, accent, size = if (d.tv) 30.sp else if (narrow) 22.sp else 26.sp, modifier = Modifier.weight(1f))
            if (narrow) {
                IconBtn(Ic.Search, { searchOpen = !searchOpen })
            } else {
                SearchField(vm.query, { vm.query = it })
            }
            MBtn("🎤", filled = false) { startVoice() }
            IconBtn(Ic.Refresh, { vm.refresh() })
            if (narrow) IconBtn(Ic.Settings, { vm.openSettings() })
        }
        if (narrow && (searchOpen || vm.query.isNotEmpty())) {
            Spacer(Modifier.height(8.dp))
            SearchField(vm.query, { vm.query = it }, fillWidth = true)
        }
    }
}
