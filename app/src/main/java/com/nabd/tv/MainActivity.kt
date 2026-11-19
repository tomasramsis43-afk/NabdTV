package com.nabd.tv

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Recorder.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= 33) runCatching { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        setContent { AppTheme { App(vm) } }
    }
}

@Composable
fun App(vm: AppViewModel) {
    when (vm.screen) {
        Screen.Setup -> SetupScreen(vm)
        Screen.Main -> MainScreen(vm)
        Screen.Player -> PlayerScreen(vm)
        Screen.Detail -> DetailScreen(vm)
        Screen.MultiView -> MultiViewScreen(vm)
        Screen.Settings -> SettingsScreen(vm)
    }
    Overlays(vm)
}
