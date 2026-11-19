package com.nabd.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SetupScreen(vm: AppViewModel) {
    var url by remember { mutableStateOf(vm.savedUrl) }
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val firstFocus = remember { FocusRequester() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun toast(m: String) = android.widget.Toast.makeText(ctx, m, android.widget.Toast.LENGTH_LONG).show()
    LaunchedEffect(vm.error) { vm.error?.let { toast(it) } }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    BackHandler(enabled = vm.all.isNotEmpty()) { vm.closeSetup() }

    Column(
        Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Nabd TV", fontSize = 40.sp, color = Accent)
        if (vm.loading) Text("جارٍ الاتصال وتحميل القنوات...", color = Accent)
        vm.error?.let { Text("خطأ: $it", color = Danger) }
        if (vm.hasXtream) {
            Spacer(Modifier.height(8.dp))
            MBtn(if (vm.diagBusy) "جارٍ الفحص..." else "فحص الاتصال والقنوات", filled = false) { vm.runDiag() }
            vm.diag?.let { Text(it, fontSize = 13.sp, color = Color.White) }
        }
        Spacer(Modifier.height(20.dp))
        Text("Xtream Codes", fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(host, { host = it }, singleLine = true, modifier = Modifier.fillMaxWidth(0.7f),
            placeholder = { androidx.compose.material3.Text("http://server:port") })
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(user, { user = it }, singleLine = true, modifier = Modifier.fillMaxWidth(0.7f),
            placeholder = { androidx.compose.material3.Text("Username") })
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(pass, { pass = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(0.7f), placeholder = { androidx.compose.material3.Text("Password") })
        Spacer(Modifier.height(10.dp))
        MBtn("دخول Xtream", modifier = Modifier.focusRequester(firstFocus)) {
            if (host.isNotBlank() && user.isNotBlank() && pass.isNotBlank()) vm.setXtream(host, user, pass)
            else toast("اكتب السيرفر واليوزر والباسورد")
        }
        Spacer(Modifier.height(20.dp))
        Text("أو رابط M3U", fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(url, { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth(0.7f),
            placeholder = { androidx.compose.material3.Text("http://.../playlist.m3u") })
        Spacer(Modifier.height(10.dp))
        MBtn("تحميل M3U") { if (url.isNotBlank()) vm.setUrl(url) else toast("اكتب رابط M3U") }
    }
}
