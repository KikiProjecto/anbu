package com.anbu.research

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.anbu.research.ui.AIEngineViewModel
import com.anbu.research.ui.screens.ChatScreen
import com.anbu.research.ui.screens.InitScreen
import com.anbu.research.ui.screens.SearchScreen
import com.anbu.research.ui.theme.AnbuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AnbuTheme {
                AnbuApp()
            }
        }
    }
}

object Routes {
    const val SPLASH = "splash"
    const val SEARCH = "search"
    const val CHAT = "chat/{query}"
    fun chat(query: String) = "chat/${android.net.Uri.encode(query)}"
}

@Composable
fun AnbuApp() {
    val vm: AIEngineViewModel = viewModel()
    val nav = rememberNavController()

    NavHost(
        navController = nav,
        startDestination = Routes.SPLASH,
        enterTransition = { fadeIn(tween(300)) + slideInHorizontally(tween(300)) { it / 3 } },
        exitTransition = { fadeOut(tween(300)) + slideOutHorizontally(tween(300)) { -it / 3 } },
        popEnterTransition = { fadeIn(tween(300)) + slideInHorizontally(tween(300)) { -it / 3 } },
        popExitTransition = { fadeOut(tween(300)) + slideOutHorizontally(tween(300)) { it / 3 } }
    ) {
        composable(Routes.SPLASH) {
            InitScreen(vm = vm, onReady = { nav.navigate(Routes.SEARCH) { popUpTo(Routes.SPLASH) { inclusive = true } } })
        }
        composable(Routes.SEARCH) {
            SearchScreen(onSearch = { q -> nav.navigate(Routes.chat(q)) })
        }
        composable(Routes.CHAT) { backStack ->
            val query = backStack.arguments?.getString("query").orEmpty()
            ChatScreen(vm = vm, query = query, onBack = { nav.popBackStack() })
        }
    }
}
