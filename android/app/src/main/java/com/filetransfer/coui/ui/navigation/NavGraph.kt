package com.filetransfer.coui.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.filetransfer.coui.ui.screen.ChatScreen
import com.filetransfer.coui.ui.screen.HomeScreen
import com.filetransfer.coui.ui.screen.SettingsScreen

@Composable
fun AppNavHost(navController: NavHostController, startDestination: String = "home") {
    androidx.navigation.compose.NavHost(navController, startDestination) {
        composable("home") {
            HomeScreen(
                onSettingsClick = { navController.navigate("settings") },
                onOpenChat = { deviceId, name ->
                    navController.navigate("chat/$deviceId?name=${Uri.encode(name)}")
                },
            )
        }
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable("chat/{deviceId}?name={name}") { entry ->
            val deviceId = entry.arguments?.getString("deviceId") ?: return@composable
            val name = entry.arguments?.getString("name") ?: ""
            ChatScreen(
                deviceId = deviceId,
                fallbackName = name,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

@Composable
fun rememberAppNavController(): NavHostController = rememberNavController()
