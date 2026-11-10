package com.kvmcontrol.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kvmcontrol.app.ui.screens.ConnectScreen
import com.kvmcontrol.app.ui.screens.ControlScreen
import com.kvmcontrol.app.ui.screens.HomeScreen
import com.kvmcontrol.app.ui.screens.SettingsScreen
import com.kvmcontrol.app.ui.theme.AndroidKvmTheme
import com.kvmcontrol.app.ui.theme.KvmGreen
import com.kvmcontrol.app.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Results handled via UI state */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRequiredPermissions()
        setContent {
            AndroidKvmTheme {
                KvmApp(viewModel)
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }
}

sealed class NavRoute(val route: String, val label: String) {
    data object Home : NavRoute("home", "Home")
    data object Connect : NavRoute("connect", "Connect")
    data object Control : NavRoute("control", "Control")
    data object Settings : NavRoute("settings", "Settings")
}

@Composable
fun KvmApp(viewModel: MainViewModel) {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = { KvmBottomNav(navController) },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = NavRoute.Home.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(NavRoute.Home.route) { HomeScreen(viewModel) }
            composable(NavRoute.Connect.route) { ConnectScreen(viewModel) }
            composable(NavRoute.Control.route) { ControlScreen(viewModel) }
            composable(NavRoute.Settings.route) { SettingsScreen(viewModel) }
        }
    }
}

@Composable
private fun KvmBottomNav(navController: NavHostController) {
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    NavigationBar {
        listOf(
            NavRoute.Home to Icons.Default.Home,
            NavRoute.Connect to Icons.Default.Wifi,
            NavRoute.Control to Icons.Default.Computer,
            NavRoute.Settings to Icons.Default.Settings,
        ).forEach { (route, icon) ->
            NavigationBarItem(
                icon = { Icon(icon, contentDescription = route.label) },
                label = { Text(route.label) },
                selected = current == route.route,
                onClick = {
                    navController.navigate(route.route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                colors = NavigationBarItemDefaults.colors(indicatorColor = KvmGreen.copy(alpha = 0.2f)),
            )
        }
    }
}
