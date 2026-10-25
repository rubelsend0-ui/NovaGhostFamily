package com.novaghost.family

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novaghost.family.ui.AppViewModel
import com.novaghost.family.ui.NovaRoot
import com.novaghost.family.ui.NovaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: AppViewModel = viewModel()
            val look = vm.look.collectAsStateWithLifecycle().value
            NovaTheme(look.accent, look.reduceMotion, look.largeText, look.highContrast) {
                NovaRoot(vm)
            }
        }
    }
}
