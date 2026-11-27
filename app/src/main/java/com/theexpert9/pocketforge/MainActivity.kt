package com.theexpert9.pocketforge

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theexpert9.pocketforge.data.AppContainer
import com.theexpert9.pocketforge.ui.PocketForgeApp
import com.theexpert9.pocketforge.ui.theme.PocketForgeTheme
import com.theexpert9.pocketforge.viewmodel.PocketForgeViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure Firebase App Check with launch intent BEFORE UI, ViewModels, or Firebase calls.
        configureAppCheck(this, intent)

        // STEP 2: Enable edge-to-edge dark system bars matching PocketForge IDE Slate.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        val container = AppContainer(applicationContext)

        setContent {
            PocketForgeTheme {
                val vm: PocketForgeViewModel = viewModel(
                    factory = PocketForgeViewModel.factory(container.repository)
                )
                PocketForgeApp(viewModel = vm)
            }
        }
    }
}
