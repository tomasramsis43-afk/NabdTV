package com.nabd.tv

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

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

    override fun onStart() { super.onStart(); AppState.foreground = true }
    override fun onStop() { super.onStop(); AppState.foreground = false }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (vm.screen == Screen.Player) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        vm.inPip = isInPictureInPictureMode
    }

    /** True full-screen: hides status + navigation bars (swipe from the edge shows them briefly). */
    private fun hideSystemBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                if (Build.VERSION.SDK_INT >= 30) android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        hideSystemBars()
        Recorder.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= 33) runCatching { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        setContent { AppTheme { App(vm) } }
    }
}

@Composable
fun App(vm: AppViewModel) {
    // Video screens use every pixel (even under the camera notch); the rest keep clear of the notch and the keyboard.
    val full = vm.screen == Screen.Player || vm.screen == Screen.MultiView
    Box(
        Modifier.fillMaxSize().appBackground()
            .then(if (full) Modifier else Modifier.windowInsetsPadding(WindowInsets.displayCutout).imePadding())
    ) {
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
}
