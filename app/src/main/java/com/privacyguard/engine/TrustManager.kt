package com.privacyguard.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

/**
 * Determines if the device is in a trusted environment (home/office WiFi or paired Bluetooth).
 * Reduces confidence score — no need for aggressive monitoring when you're safe.
 */
class TrustManager(private val context: Context) {

    private val trustedSSIDs = mutableSetOf<String>()
    private val trustedBTAddresses = mutableSetOf<String>()

    /** Set of user-configured trusted WiFi SSIDs. */
    fun setTrustedSSIDs(ssids: Set<String>) {
        trustedSSIDs.clear()
        trustedSSIDs.addAll(ssids.map { it.trim('"').trim() })
    }

    fun getTrustedSSIDs(): Set<String> = trustedSSIDs.toSet()

    /** Set of user-configured trusted Bluetooth MAC addresses. */
    fun setTrustedBTAddresses(addresses: Set<String>) {
        trustedBTAddresses.clear()
        trustedBTAddresses.addAll(addresses.map { it.uppercase().trim() })
    }

    fun getTrustedBTAddresses(): Set<String> = trustedBTAddresses.toSet()

    /** Returns true if the current environment is trusted. */
    fun isInTrustedEnvironment(): Boolean {
        return isOnTrustedWiFi() || isConnectedToTrustedBT()
    }

    private fun isOnTrustedWiFi(): Boolean {
        if (trustedSSIDs.isEmpty()) return false
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false

        // Get current SSID from WifiManager
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
            val ssid = wifiManager.connectionInfo?.ssid ?: return false
            val clean = ssid.trim('"').trim()
            clean in trustedSSIDs
        } catch (_: Exception) {
            false
        }
    }

    private fun isConnectedToTrustedBT(): Boolean {
        if (trustedBTAddresses.isEmpty()) return false
        return try {
            val bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return false
            if (!bluetoothAdapter.isEnabled) return false
            val devices = bluetoothAdapter.bondedDevices ?: return false
            devices.any { it.address.uppercase() in trustedBTAddresses }
        } catch (_: Exception) {
            false
        }
    }
}
