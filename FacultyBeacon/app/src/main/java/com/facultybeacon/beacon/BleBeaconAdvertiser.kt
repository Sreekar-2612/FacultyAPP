package com.facultybeacon.beacon

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context

/**
 * Wraps Android's BLE advertising APIs so the phone broadcasts like the ESP32 beacon:
 * a non-connectable advertisement carrying manufacturer-specific data
 * (2 bytes manufacturer ID 0xFFFF + 16-byte UUID), broadcast continuously.
 *
 * Uses the public [BluetoothLeAdvertiser] (API 21+, BLE 4.0 controllers). TX power is
 * only selectable from the fixed preset list, so we use [AdvertiseSettings.ADVERTISE_TX_POWER_HIGH]
 * (the closest public equivalent to the ESP32's +9 dBm `ESP_PWR_LVL_N9`).
 *
 * The phone never scans and never connects - it is a pure broadcaster, like the ESP32.
 */
class BleBeaconAdvertiser(context: Context) {

    companion object {
        /** Advertise interval in ms. Matches the ESP32's continuous broadcast; LOW_LATENCY
         *  gives an interval of ~100 ms. */
        const val ADVERTISE_INTERVAL_MS = 100
    }

    interface Callback {
        /** Called when advertising has actually started. */
        fun onStarted(actualTxPower: String, modeDescription: String)

        /** Called when advertising has stopped. */
        fun onStopped()

        /** Called when advertising could not start. */
        fun onError(message: String)
    }

    private val bluetoothManager = context.applicationContext
        .getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    val adapter: BluetoothAdapter?
        get() = bluetoothManager.adapter

    val isBluetoothEnabled: Boolean
        get() = adapter?.isEnabled == true

    /** Whether this device's Bluetooth controller can run BLE advertising at all. */
    val isAdvertisingSupported: Boolean
        get() = adapter?.isMultipleAdvertisementSupported == true

    private var legacyCallback: AdvertiseCallback? = null
    private var activeLegacyAdvertiser: BluetoothLeAdvertiser? = null

    @Volatile
    var isAdvertising: Boolean = false
        private set

    /**
     * Starts continuous non-connectable advertising of the given 32-hex-char UUID.
     */
    @Synchronized
    fun start(uuid: String, callback: Callback) {
        stop()

        val btAdapter = adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            callback.onError("Bluetooth is not enabled")
            return
        }
        if (!isAdvertisingSupported) {
            callback.onError("This device does not support BLE advertising")
            return
        }

        startLegacy(btAdapter, BlePayload.uuidToBytes(uuid), callback)
    }

    /** Stops advertising. The [Callback.onStopped] of an active session fires asynchronously. */
    @Synchronized
    fun stop() {
        // Bluetooth may already be off (or the radio in a weird state), so never let
        // the stop calls throw - the callbacks are cleared regardless.
        runCatching {
            activeLegacyAdvertiser?.let { advertiser ->
                legacyCallback?.let { advertiser.stopAdvertising(it) }
            }
        }
        activeLegacyAdvertiser = null
        legacyCallback = null
        isAdvertising = false
    }

    // -------------------------------------------------------------------- legacy path

    private fun startLegacy(adapter: BluetoothAdapter, payload: ByteArray, callback: Callback) {
        val advertiser = try {
            adapter.bluetoothLeAdvertiser
        } catch (e: Exception) {
            callback.onError("Unable to access the BLE advertiser: ${e.message}")
            return
        }
        if (advertiser == null) {
            callback.onError("BLE advertising is not available on this device")
            return
        }

        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(BlePayload.MANUFACTURER_ID, payload)
            .build()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY) // ~100 ms interval
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)  // non-connectable broadcast only
            .setTimeout(0)          // advertise indefinitely
            .build()

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                isAdvertising = true
                callback.onStarted(
                    txPowerLabel(settingsInEffect.txPowerLevel),
                    "Non-connectable broadcast, ~${ADVERTISE_INTERVAL_MS} ms interval"
                )
            }

            override fun onStartFailure(errorCode: Int) {
                callback.onError("Advertising failed: ${advertiseErrorText(errorCode)}")
            }
        }
        legacyCallback = cb
        activeLegacyAdvertiser = advertiser

        try {
            advertiser.startAdvertising(settings, advertiseData, cb)
        } catch (e: Exception) {
            callback.onError("Unable to start advertising: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------- helpers

    private fun txPowerLabel(level: Int): String = when (level) {
        AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW -> "ULTRA_LOW (device-dependent dBm)"
        AdvertiseSettings.ADVERTISE_TX_POWER_LOW -> "LOW (device-dependent dBm)"
        AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM -> "MEDIUM (device-dependent dBm)"
        AdvertiseSettings.ADVERTISE_TX_POWER_HIGH -> "HIGH (device-dependent dBm)"
        else -> "UNKNOWN($level)"
    }

    private fun advertiseErrorText(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "advertisement data too large"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers already running"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "advertising already started"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "feature not supported by this device"
        else -> "unknown error ($code)"
    }
}