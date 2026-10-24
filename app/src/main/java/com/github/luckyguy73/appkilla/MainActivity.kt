package com.github.luckyguy73.appkilla

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import com.github.luckyguy73.appkilla.adb.AdbManager
import com.github.luckyguy73.appkilla.data.repository.AppRepository
import com.github.luckyguy73.appkilla.ui.AppKillaScreen
import com.github.luckyguy73.appkilla.ui.theme.AppKillaTheme
import com.github.luckyguy73.appkilla.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private lateinit var adbManager: AdbManager
    private lateinit var appRepository: AppRepository

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory(
            application = application,
            appRepository = appRepository,
            adbManager = adbManager
        )
    }

    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize core dependencies
        adbManager = AdbManager(applicationContext)
        appRepository = AppRepository(applicationContext, adbManager)

        setContent {
            AppKillaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape
                ) {
                    AppKillaScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        adbManager.disconnect()
    }
}
