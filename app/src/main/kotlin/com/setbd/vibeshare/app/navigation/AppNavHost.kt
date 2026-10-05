package com.setbd.vibeshare.app.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.setbd.vibeshare.app.ui.screens.AppsScreen
import com.setbd.vibeshare.app.ui.screens.DevScreen
import com.setbd.vibeshare.app.ui.screens.HistoryScreen
import com.setbd.vibeshare.app.ui.screens.HomeScreen
import com.setbd.vibeshare.app.ui.screens.ReceiveScreen
import com.setbd.vibeshare.app.ui.screens.SendScreen
import com.setbd.vibeshare.app.ui.screens.SettingsScreen

/** Type-safe route table. */
object Routes {
    const val HOME = "home"
    const val SEND = "send"
    const val RECEIVE = "receive"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val APPS = "apps"
    const val DEV = "dev"
}

@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        enterTransition = { slideInHorizontally(tween(280)) { it / 4 } + fadeIn(tween(280)) },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(200)) },
        popExitTransition = { slideOutHorizontally(tween(240)) { it / 4 } + fadeOut(tween(240)) },
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onSend = { navController.navigate(Routes.SEND) },
                onReceive = { navController.navigate(Routes.RECEIVE) },
                onHistory = { navController.navigate(Routes.HISTORY) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onDevMode = { navController.navigate(Routes.DEV) },
            )
        }
        composable(Routes.SEND) {
            SendScreen(
                onBack = { navController.popBackStack() },
                onOpenApps = { navController.navigate(Routes.APPS) },
            )
        }
        composable(Routes.RECEIVE) {
            ReceiveScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.HISTORY) {
            HistoryScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.APPS) {
            AppsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.DEV) {
            DevScreen(onBack = { navController.popBackStack() })
        }
    }
}
