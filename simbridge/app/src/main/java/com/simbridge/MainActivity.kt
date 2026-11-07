package com.simbridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.simbridge.host.SimHostService
import com.simbridge.ui.HomeScreen
import com.simbridge.ui.InCallScreen
import com.simbridge.ui.theme.SimBridgeTheme

class MainActivity : ComponentActivity() {

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled per-feature in UI */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
        }
        requestPermissions.launch(perms.toTypedArray())

        setContent {
            SimBridgeTheme {
                AppNav()
            }
        }
    }

    @Composable
    private fun AppNav() {
        val navController = rememberNavController()

        NavHost(navController = navController, startDestination = "home") {
            composable("home") {
                HomeScreen(
                    onHostReady = {
                        startHostService()
                        promptDefaultDialer()
                        navController.navigate("incall")
                    },
                    onClientConnected = {
                        navController.navigate("incall")
                    }
                )
            }
            composable("incall") {
                val prefs = getSharedPreferences("simbridge", Context.MODE_PRIVATE)
                val isHost = prefs.getBoolean("is_host", false)
                InCallScreen(
                    isHost = isHost,
                    onDisconnect = {
                        stopHostService()
                        getSharedPreferences("simbridge", Context.MODE_PRIVATE).edit().clear().apply()
                        navController.popBackStack()
                    }
                )
            }
        }
    }

    private fun promptDefaultDialer() {
        val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        if (telecom.defaultDialerPackage != packageName) {
            startActivity(
                Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                    .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
            )
        }
    }

    private fun startHostService() {
        startForegroundService(Intent(this, SimHostService::class.java))
    }

    private fun stopHostService() {
        stopService(Intent(this, SimHostService::class.java))
    }
}
