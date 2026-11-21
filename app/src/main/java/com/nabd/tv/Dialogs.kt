package com.nabd.tv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

/** Hosts the global overlays (PIN pad, context menu, text prompt, toast-like message). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun Overlays(vm: AppViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(vm.message) {
        vm.message?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_SHORT).show(); vm.message = null }
    }
    vm.pin?.let { req -> PinDialog(req.title, onDismiss = { vm.pin = null }) { vm.submitPin(it) } }
    vm.menu?.let { m ->
        GlassDialog(m.title, onDismiss = { vm.menu = null }) {
            LazyColumn(Modifier.widthIn(min = 260.dp).heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(m.items) { (label, act) ->
                    Focusable(onClick = { vm.menu = null; act() }, modifier = Modifier.fillMaxWidth()) {
                        Text(label, color = Ink, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp))
                    }
                }
            }
        }
    }
    vm.prompt?.let { pr ->
        var text by remember(pr) { mutableStateOf(pr.initial) }
        GlassDialog(pr.title, onDismiss = { vm.prompt = null }) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                visualTransformation = if (pr.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None
            )
            Spacer(Modifier.height(10.dp))
            MBtn(tr(R.string.dlg_save)) { vm.prompt = null; pr.onOk(text) }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PinDialog(title: String, onDismiss: () -> Unit, verify: (String) -> Boolean) {
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    GlassDialog(title, onDismiss) {
        OutlinedTextField(
            value = code, onValueChange = { v -> if (v.length <= 6 && v.all { it.isDigit() }) { code = v; wrong = false } },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
        )
        if (wrong) Text(tr(R.string.dlg_wrong_pin), color = Danger, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            MBtn(tr(R.string.dlg_confirm)) { if (!verify(code)) { wrong = true; code = "" } }
            MBtn(tr(R.string.dlg_cancel), filled = false) { onDismiss() }
        }
    }
}
