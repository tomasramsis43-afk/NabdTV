@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nabd.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

/** Small layers over the video: lock, digit entry, skip button, gesture HUD, spinner, stats. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun BoxScope.PlayerStatusLayers(
    ui: PlayerUiState,
    vm: AppViewModel,
    actions: PlayerActions,
    feedback: GestureFeedback,
    showSkip: Boolean,
    skipLabel: Int,
    doSkip: () -> Unit,
) {
    if (ui.locked && !vm.inPip) {
        Box(Modifier.fillMaxSize().clickable { ui.lockHint = true; ui.ping++ })
        Row(
            Modifier.align(Alignment.Center).background(OverlayScrimStrong, RoundedCornerShape(20.dp))
                .clickable { actions.toggleLock() }.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("🔒 ${S(if (ui.lockHint) R.string.x_lock_hint else R.string.x_locked)}", color = Color.White, fontSize = 15.sp)
            PlayerRoundButton("🔓", size = 34) { actions.toggleLock() }
        }
    }

    if (!vm.inPip && ui.digits.isNotEmpty()) {
        Text(
            ui.digits, fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Accent,
            modifier = Modifier.align(Alignment.TopEnd).padding(32.dp)
        )
    }

    if (showSkip && !vm.inPip && !ui.locked) {
        Box(
            Modifier.align(Alignment.BottomEnd).padding(horizontal = 24.dp, vertical = 150.dp)
                .background(OverlayScrimStrong, RoundedCornerShape(12.dp))
                .clickable { doSkip() }
                .padding(horizontal = 18.dp, vertical = 12.dp)
        ) { Text(S(skipLabel), color = Accent, fontSize = 15.sp) }
    }

    GestureHud(feedback, Modifier.align(Alignment.Center))
    if (ui.hudText.isNotEmpty() && !feedback.active && !vm.inPip) {
        Text(
            ui.hudText, fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center).background(Color(0xBB000000), RoundedCornerShape(10.dp))
                .padding(horizontal = 18.dp, vertical = 10.dp)
        )
    }

    if (ui.status == STATUS_LOADING && !vm.inPip && ui.error == null) {
        androidx.compose.material3.CircularProgressIndicator(
            Modifier.align(Alignment.Center).size(46.dp), color = Accent, strokeWidth = 3.dp
        )
    } else if (ui.status.isNotEmpty() && !vm.inPip && ui.error == null) {
        Text(
            ui.status, color = Color.White, fontSize = 14.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 90.dp)
                .background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(12.dp)
        )
    }

    if (ui.stats && !vm.inPip) {
        Text(
            ui.infoText, fontSize = 12.sp, color = Accent,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                .background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(8.dp)
        )
    }
}
