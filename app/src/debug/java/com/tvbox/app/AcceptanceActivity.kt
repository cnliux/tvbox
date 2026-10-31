package com.tvbox.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import com.tvbox.app.data.*
import com.tvbox.app.domain.*
import com.tvbox.app.ui.*
import com.tvbox.app.ui.theme.TVBoxTheme

/** Debug-only deterministic acceptance harness; never packaged in Release. */
class AcceptanceActivity : ComponentActivity() {
    private val vm: TvBoxViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val service = "http://127.0.0.1:8766"
                val localPlatform = EmbeddedPlatformLiveRepository(this@AcceptanceActivity)
                return TvBoxViewModel(
                    repository = DefaultMovieRepository(listOf(
                        ApiLine("acceptance", "验收接口", listOf("$service/api/")),
                        ApiLine("acceptance-alt", "验收备用", listOf("$service/alt-api/")),
                    )),
                    liveRepository = DefaultLiveRepository(
                        listOf(BuiltInLiveSource("acceptance", "验收直播源", "$service/live.txt")),
                    ),
                    platformLiveRepository = localPlatform,
                    appSettingsRepository = object : AppSettingsRepository {
                        var settings = AppSettings(homeApiLineId = "acceptance", checkUpdatesOnStartup = false,
                            theme = if (intent.getStringExtra("theme") == "cinema") TvTheme.Cinema else TvTheme.Default)
                        override suspend fun getSettings() = settings
                        override suspend fun saveSettings(settings: AppSettings): AppSettings { this.settings = settings; return settings }
                    },
                    historyRepository = SharedHistoryRepository(this@AcceptanceActivity),
                    platformLiveFavoritesRepository = SharedPlatformLiveFavoritesRepository(this@AcceptanceActivity),
                ) as T
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state = vm.state.collectAsStateWithLifecycle().value
            LaunchedEffect(Unit) { vm.selectParentCategory(2) }
            TVBoxTheme(state.appSettings.theme, state.appSettings.fontScale) {
                BackHandler(state.screen != TvScreen.Home) { vm.goBack() }
                TvBoxApp(state, vm)
            }
        }
    }
}
