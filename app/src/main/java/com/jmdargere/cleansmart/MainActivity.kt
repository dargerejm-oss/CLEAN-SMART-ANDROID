package com.jmdargere.cleansmart

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.jmdargere.cleansmart.ui.CleanSmartApp
import com.jmdargere.cleansmart.ui.CleanSmartTheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            CleanSmartTheme { CleanSmartApp(vm) }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshStorage()
    }
}
