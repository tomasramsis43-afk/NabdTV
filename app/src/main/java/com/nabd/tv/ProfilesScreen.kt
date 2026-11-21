package com.nabd.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

private val avatarColors = listOf(Color(0xFF7C8CFF), Color(0xFF57CCF0), Color(0xFFE5B567), Color(0xFFC29BFF), Color(0xFFFF9FBF), Color(0xFF69DDBB))

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProfilesScreen(vm: AppViewModel) {
    // Back keeps the current profile instead of leaving the app.
    BackHandler { vm.switchProfile(vm.profile.id) }
    Column(
        Modifier.fillMaxSize().appBackground().padding(if (LocalDevice.current.tv) 32.dp else 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text(tr(R.string.who_watching), fontSize = if (LocalDevice.current.tv) 32.sp else 26.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text(
            tr(R.string.profiles_hint),
            fontSize = 12.5.sp, color = TextSecondary, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp)
        )
        Spacer(Modifier.height(26.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            items(vm.profileList, key = { it.id }) { p ->
                Focusable(onClick = { vm.switchProfile(p.id) }, zoom = 1.06f) {
                    Column(Modifier.padding(14.dp).width(112.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(82.dp).clip(CircleShape)
                                .background(avatarColors[(p.id.hashCode() and 0x7fffffff) % avatarColors.size]),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (p.kids) "★" else (if (p.id == "main") tr(R.string.x_profile_main) else p.name).take(1),
                                fontSize = 32.sp, color = Color(0xFF0A0B10), fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(if (p.id == "main") tr(R.string.x_profile_main) else p.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink, maxLines = 1)
                        if (p.kids) Text(tr(R.string.kids), fontSize = 12.sp, color = Accent)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        MBtn(tr(R.string.detail_back), filled = false) { vm.switchProfile(vm.profile.id) }
    }
}
