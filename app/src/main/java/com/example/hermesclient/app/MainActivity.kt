package com.example.hermesclient.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.hermesclient.core.ui.theme.HermesClientTheme
import com.example.hermesclient.feature.chat.ChatRoute
import com.example.hermesclient.feature.connection.ConnectionRoute
import com.example.hermesclient.feature.sessions.SessionsRoute
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HermesClientApp() }
    }
}

@Composable
private fun HermesClientApp(viewModel: AppViewModel = hiltViewModel()) {
    val startupState by viewModel.state.collectAsStateWithLifecycle()
    HermesClientTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (startupState) {
                StartupState.Loading -> StartupLoading()
                StartupState.NeedsConnection -> HermesNavigation(startDestination = Routes.Connection)
                StartupState.Ready -> HermesNavigation(startDestination = Routes.Sessions)
            }
        }
    }
}

@Composable
private fun StartupLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun HermesNavigation(startDestination: String) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier.fillMaxSize(),
    ) {
        composable(Routes.Connection) {
            ConnectionRoute(
                onContinue = {
                    navController.navigate(Routes.Sessions) {
                        popUpTo(startDestination) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Routes.Sessions) {
            SessionsRoute(
                onOpenSession = { sessionId ->
                    navController.navigate(Routes.chat(sessionId))
                },
                onOpenSettings = {
                    navController.navigate(Routes.Connection) { launchSingleTop = true }
                },
            )
        }
        composable(
            route = Routes.Chat,
            arguments = listOf(
                navArgument(Routes.SessionIdArgument) { type = NavType.StringType },
            ),
        ) {
            ChatRoute(onBack = navController::navigateUp)
        }
    }
}

private object Routes {
    const val Connection = "connection"
    const val Sessions = "sessions"
    const val SessionIdArgument = "sessionId"
    const val Chat = "chat/{$SessionIdArgument}"

    fun chat(sessionId: String): String = "chat/${Uri.encode(sessionId)}"
}
