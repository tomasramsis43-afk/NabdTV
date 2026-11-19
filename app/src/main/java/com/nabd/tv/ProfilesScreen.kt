package com.nabd.tv

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

private val avatarColors = listOf(Color(0xFF00E5A0), Color(0xFF4FC3F7), Color(0xFFFFB74D), Color(0xFFBA68C8), Color(0xFFFF8A65), Color(0xFF81C784))

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProfilesScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().background(Bg).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("من يشاهد؟", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(28.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            items(vm.profileList, key = { it.id }) { p ->
                Focusable(onClick = { vm.switchProfile(p.id) }, zoom = 1.1f) {
                    Column(Modifier.padding(18.dp).width(110.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(84.dp).clip(CircleShape).background(avatarColors[(p.id.hashCode() and 0x7fffffff) % avatarColors.size]),
                            contentAlignment = Alignment.Center
                        ) { Text(if (p.kids) "★" else p.name.take(1), fontSize = 34.sp, color = Color.Black, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.height(10.dp))
                        Text(p.name, fontSize = 15.sp, color = Color.White, maxLines = 1)
                        if (p.kids) Text("أطفال", fontSize = 11.sp, color = Accent)
                    }
                }
            }
        }
    }
}
