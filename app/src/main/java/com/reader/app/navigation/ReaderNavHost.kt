package com.reader.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.reader.feature.library.LibraryRoute
import com.reader.feature.reader.ReaderRoute
import com.reader.feature.settings.SettingsRoute

object Routes {
    const val Library = "library"
    const val Settings = "settings"
    const val Reader = "reader/{bookId}"
    fun reader(bookId: String) = "reader/$bookId"
}

@Composable
fun ReaderNavHost(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Routes.Library,
    ) {
        composable(Routes.Library) {
            LibraryRoute(
                onOpenBook = { id -> navController.navigate(Routes.reader(id)) },
                onOpenSettings = { navController.navigate(Routes.Settings) },
            )
        }
        composable(Routes.Settings) {
            SettingsRoute(onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.Reader,
            arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
        ) { entry ->
            val bookId = entry.arguments?.getString("bookId") ?: return@composable
            ReaderRoute(
                bookId = bookId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
