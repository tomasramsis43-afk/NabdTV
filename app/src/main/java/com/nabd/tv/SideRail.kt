package com.nabd.tv

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

/** Width of the collapsed side menu (icons only). The page content is inset by this much; the expanded menu draws over it. */
internal val RAIL_W: Dp = 76.dp
private val RAIL_OPEN_W: Dp = 220.dp

private fun tabIcon(t: Tab): Ic = when (t) {
    Tab.Home -> Ic.Home
    Tab.Live -> Ic.Live
    Tab.Movies -> Ic.Movies
    Tab.Series -> Ic.Series
    Tab.Guide -> Ic.Guide
    Tab.Recordings -> Ic.Rec
    Tab.Downloads -> Ic.Download
}

/**
 * Optional TV navigation: a vertical menu on the start edge instead of the tab bar on top. Icons only while the remote is
 * on the page; it opens to show the names while the remote is on the menu, over the content (nothing is pushed around).
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun SideRail(vm: AppViewModel, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    val tabs = visibleTabs(vm)
    val reqs = remember { Tab.entries.associateWith { FocusRequester() } }
    var expanded by remember { mutableStateOf(false) }
    val w by animateDpAsState(if (expanded) RAIL_OPEN_W else RAIL_W, tween(170), label = "railW")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val toContent = if (rtl) FocusDirection.Left else FocusDirection.Right
    Column(
        modifier.width(w).fillMaxHeight()
            .background(Bg.copy(alpha = if (expanded) 0.98f else 0.9f))
            .onFocusChanged { expanded = it.hasFocus }
            .focusProperties {
                enter = { reqs[vm.tab] ?: FocusRequester.Default }
                exit = { dir -> if (dir == toContent) vm.contentEntry() ?: FocusRequester.Default else FocusRequester.Default }
            }
            .focusGroup()
            .padding(horizontal = 10.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            tabs.forEach { t ->
                RailItem(tabIcon(t), tr(t.labelRes), selected = vm.tab == t, expanded = expanded, requester = reqs.getValue(t)) { vm.selectTab(t) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RailItem(Ic.Search, tr(R.string.rail_search), selected = false, expanded = expanded) { onSearch() }
            RailItem(Ic.Refresh, tr(R.string.rail_refresh), selected = false, expanded = expanded) { vm.refresh() }
            if (vm.profileList.size > 1) RailItem(Ic.User, tr(R.string.rail_profiles), selected = false, expanded = expanded) { vm.openProfiles() }
            RailItem(Ic.Settings, tr(R.string.rail_settings), selected = false, expanded = expanded) { vm.openSettings() }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RailItem(
    ic: Ic, label: String, selected: Boolean, expanded: Boolean,
    requester: FocusRequester? = null, onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val tint = when {
        focused -> Ink
        selected -> Accent
        else -> TextSecondary
    }
    Row(
        Modifier.fillMaxWidth().height(48.dp)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Accent.copy(alpha = 0.18f) else if (selected) Accent.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NIcon(ic, tint, 24.dp)
        if (expanded) {
            Spacer(Modifier.width(14.dp))
            Text(
                label, fontSize = 15.sp, fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
                color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

