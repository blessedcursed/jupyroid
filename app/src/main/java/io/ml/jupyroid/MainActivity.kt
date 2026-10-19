package io.ml.jupyroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.ml.jupyroid.ui.MainScreen
import io.ml.jupyroid.ui.theme.JupyroidTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JupyroidTheme {
                MainScreen(vm)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // MIUI likes to kill background apps; don't leave unsaved edits behind.
        vm.saveAll()
    }
}
