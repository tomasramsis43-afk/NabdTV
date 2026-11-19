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

    /** Floating window for the player (also used automatically when the user presses Home during playback). */
    fun enterPip(): Boolean {
        if (Build.VERSION.SDK_INT < 26 || !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            vm.say("الجهاز لا يدعم النافذة العائمة"); return false
        }
        return try {
            enterPictureInPictureMode(
                android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).build()
            )
            true
        } catch (e: Exception) { false }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (vm.screen == Screen.Player) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        vm.inPip = isInPictureInPictureMode
    }

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
        Screen.Profiles -> ProfilesScreen(vm)
        Screen.Main -> MainScreen(vm)
        Screen.Player -> PlayerScreen(vm)
        Screen.Detail -> DetailScreen(vm)
        Screen.MultiView -> MultiViewScreen(vm)
        Screen.Settings -> SettingsScreen(vm)
    }
    Overlays(vm)
}
