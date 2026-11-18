package com.nabd.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { App(vm) } }
    }
}

@Composable
fun App(vm: AppViewModel) {
    when (vm.screen) {
        Screen.Setup -> SetupScreen(vm)
        Screen.Home -> HomeScreen(vm)
        Screen.Player -> PlayerScreen(vm)
    }
}
