package com.jmdargere.cleansmart

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.jmdargere.cleansmart.ui.CleanSmartApp
import com.jmdargere.cleansmart.ui.CleanSmartTheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            CleanSmartTheme {
                // option "Gros caracteres" : agrandit tous les textes de l'appli
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * vm.settings.fontScale)) {
                    CleanSmartApp(vm)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshStorage()
    }
}
