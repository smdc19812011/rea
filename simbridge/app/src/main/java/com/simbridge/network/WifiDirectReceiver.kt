package com.simbridge.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Declared in the manifest so WiFi Direct broadcasts can reach the app when it is in the
 * background.  WifiDirectHelper registers its own inline receiver while active; this stub
 * exists only to satisfy the manifest declaration requirement.
 */
class WifiDirectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
