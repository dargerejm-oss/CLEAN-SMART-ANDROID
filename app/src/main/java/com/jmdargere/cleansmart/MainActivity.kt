package com.jmdargere.cleansmart

import android.content.Intent
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
        lireWidget(intent)
        setContent {
            CleanSmartTheme {
                // option "Gros caracteres" : agrandit tous les textes de l'appli
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * vm.settings.fontScale)) {
                    // changer de langue redessine tout l'ecran tout de suite
                    androidx.compose.runtime.key(vm.settings.langue) { CleanSmartApp(vm) }
                }
            }
        }
    }

    /** Le bouton « Menage » du widget ouvre directement le Menage guide. */
    private fun lireWidget(i: Intent?) {
        if (i?.getBooleanExtra(Widget.OUVRIR_GUIDE, false) == true) vm.ouvrirGuide = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        lireWidget(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.refreshStorage()
        Widget.rafraichir(this)
    }
}
