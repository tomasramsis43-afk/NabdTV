package com.nabd.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

/** Labelled glass input. Content is always left-to-right (URLs, usernames, passwords), the label follows the app direction. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SetupField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    ime: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {}
) {
    var focused by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radius.small)
    Column(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
        Spacer(Modifier.height(6.dp))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = LocalDevice.current.control + 8.dp)
                    .then(if (focused) Modifier.softShadow(shape, 12.dp) else Modifier)
                    .glass(shape, focused = focused)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(hint, color = TextSecondary.copy(alpha = 0.65f), fontSize = 15.sp, maxLines = 1)
                    BasicTextField(
                        value = value, onValueChange = onChange, singleLine = true,
                        textStyle = TextStyle(color = Ink, fontSize = 15.sp, textDirection = TextDirection.Ltr),
                        cursorBrush = SolidColor(Accent),
                        visualTransformation = if (password && !show) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (password) KeyboardType.Password else keyboard,
                            imeAction = ime
                        ),
                        keyboardActions = KeyboardActions(onDone = { onDone() }),
                        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
                    )
                }
                if (password) {
                    Text(
                        if (show) S(R.string.a_08042201) else S(R.string.a_f2dde4fd), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Accent,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { show = !show }.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SetupScreen(vm: AppViewModel) {
    val d = LocalDevice.current
    var m3u by remember { mutableStateOf(!vm.hasXtream && vm.savedUrl.isNotBlank()) }
    var url by remember { mutableStateOf(if (vm.hasXtream) "" else vm.savedUrl) }
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val firstFocus = remember { FocusRequester() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun toast(m: String) = android.widget.Toast.makeText(ctx, m, android.widget.Toast.LENGTH_LONG).show()
    fun submit() {
        if (vm.loading) return
        if (m3u) {
            if (url.isNotBlank()) vm.setUrl(url) else toast(S(R.string.a_c56dfeff))
        } else {
            if (host.isNotBlank() && user.isNotBlank() && pass.isNotBlank()) vm.setXtream(host, user, pass)
            else toast(S(R.string.a_ec479973))
        }
    }
    LaunchedEffect(Unit) { if (d.tv) runCatching { firstFocus.requestFocus() } }
    BackHandler(enabled = vm.all.isNotEmpty()) { vm.closeSetup() }

    vm.pairingUrl?.let { u ->
        GlassDialog(S(R.string.a_322b1c4a), onDismiss = { vm.stopPairing() }) {
            QrCode(u)
            Spacer(Modifier.height(10.dp))
            Text(u, fontSize = 12.sp, color = TextSecondary)
            Spacer(Modifier.height(10.dp))
            MBtn(S(R.string.a_e776b020), filled = false) { vm.stopPairing() }
        }
    }

    val cardShape = RoundedCornerShape(28.dp)
    Box(
        Modifier.fillMaxSize().appBackground().padding(horizontal = if (d.tv) 48.dp else 16.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            /* brand */
            Box(
                Modifier.size(64.dp).softShadow(RoundedCornerShape(20.dp), 14.dp).clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(Accent, Accent2))),
                contentAlignment = Alignment.Center
            ) { NIcon(Ic.Play, Color(0xFF0A0B10), 30.dp) }
            Spacer(Modifier.height(14.dp))
            AccentTitle("jolo", "", size = 34.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                if (vm.all.isNotEmpty()) S(R.string.a_f3b7cf9e) else S(R.string.a_99cf2726),
                fontSize = 14.sp, color = TextSecondary, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(22.dp))

            /* form card */
            Column(
                Modifier.fillMaxWidth().softShadow(cardShape, 22.dp).clip(cardShape)
                    .background(
                        Brush.verticalGradient(
                            if (Ui.dark) listOf(Color(0xF2171A23), Color(0xF20D0F15)) else listOf(Color(0xFAFFFFFF), Color(0xFAF1F2FD))
                        )
                    )
                    .border(1.dp, GlassBorder, cardShape)
                    .padding(if (d.tv) 26.dp else 18.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Focusable(selected = !m3u, onClick = { m3u = false }, modifier = Modifier.weight(1f), zoom = 1.02f, focusRequester = firstFocus) {
                        Text(
                            "Xtream Codes", fontSize = 14.sp, fontWeight = if (!m3u) FontWeight.Bold else FontWeight.Medium,
                            color = if (!m3u) Accent else Ink, textAlign = TextAlign.Center, maxLines = 1,
                            modifier = Modifier.fillMaxWidth().heightIn(min = d.control).wrapContentHeight(Alignment.CenterVertically)
                        )
                    }
                    Focusable(selected = m3u, onClick = { m3u = true }, modifier = Modifier.weight(1f), zoom = 1.02f) {
                        Text(
                            S(R.string.a_e304a24a), fontSize = 14.sp, fontWeight = if (m3u) FontWeight.Bold else FontWeight.Medium,
                            color = if (m3u) Accent else Ink, textAlign = TextAlign.Center, maxLines = 1,
                            modifier = Modifier.fillMaxWidth().heightIn(min = d.control).wrapContentHeight(Alignment.CenterVertically)
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))

                if (m3u) {
                    SetupField(S(R.string.a_55bf799f), url, { url = it }, "http://.../playlist.m3u",
                        keyboard = KeyboardType.Uri, ime = ImeAction.Done, onDone = ::submit)
                } else {
                    SetupField(S(R.string.a_7dbbd516), host, { host = it }, "http://server:port", keyboard = KeyboardType.Uri)
                    Spacer(Modifier.height(12.dp))
                    SetupField(S(R.string.a_794f68a2), user, { user = it }, "Username")
                    Spacer(Modifier.height(12.dp))
                    SetupField(S(R.string.a_7ba22cf7), pass, { pass = it }, "Password", password = true, ime = ImeAction.Done, onDone = ::submit)
                }

                /* status */
                if (vm.loading) {
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Accent, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(S(R.string.a_13b44d24), color = Accent, fontSize = 13.sp)
                    }
                }
                vm.error?.let { e ->
                    Spacer(Modifier.height(14.dp))
                    val es = RoundedCornerShape(Radius.small)
                    Text(
                        e, color = Danger, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().clip(es).background(Danger.copy(alpha = 0.10f))
                            .border(1.dp, Danger.copy(alpha = 0.35f), es).padding(12.dp)
                    )
                }

                Spacer(Modifier.height(18.dp))
                MBtn(if (vm.loading) S(R.string.a_04218da9) else if (m3u) S(R.string.a_553a5561) else S(R.string.a_c9cbc8b2), modifier = Modifier.fillMaxWidth().height(d.control + 8.dp)) { submit() }

                /* secondary actions */
                if (d.tv) {
                    Spacer(Modifier.height(10.dp))
                    MBtn(S(R.string.a_381f2be4), filled = false, modifier = Modifier.fillMaxWidth()) { vm.startPairing() }
                }
                if (vm.hasXtream) {
                    Spacer(Modifier.height(10.dp))
                    MBtn(if (vm.diagBusy) S(R.string.a_5a679758) else S(R.string.a_44f50601), filled = false, modifier = Modifier.fillMaxWidth()) { vm.runDiag() }
                    vm.diag?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, fontSize = 13.sp, color = Ink)
                    }
                }
            }

            if (vm.all.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                MBtn(S(R.string.a_328ddce5), filled = false) { vm.closeSetup() }
            }
        }
    }
}
