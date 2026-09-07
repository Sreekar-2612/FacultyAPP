package com.facultybeacon

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.facultybeacon.beacon.BeaconState
import com.facultybeacon.beacon.BleBeaconAdvertiser
import com.facultybeacon.databinding.ActivityTestingBinding
import com.facultybeacon.databinding.ItemDiagnosticBinding
import kotlinx.coroutines.launch

/**
 * Testing / debug screen required by the spec. Shows device capabilities, the current
 * advertisement configuration (including requested vs actual TX power) and a live log of
 * the beacon lifecycle. Also offers the battery-optimization exemption, which matters a
 * lot for reliable background advertising.
 */
class TestingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTestingBinding
    private lateinit var advertiser: BleBeaconAdvertiser

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTestingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        advertiser = BleBeaconAdvertiser(this)

        binding.buttonRefresh.setOnClickListener { refreshCapabilities() }
        binding.buttonBatteryOptimization.setOnClickListener { requestBatteryOptimizationExemption() }

        refreshCapabilities()
        observeState()
    }

    override fun onResume() {
        super.onResume()
        refreshCapabilities()
    }

    /** Re-reads device capabilities (they can change, e.g. Bluetooth toggled). */
    private fun refreshCapabilities() {
        setRow(binding.rowBleSupported, R.string.ble_supported_label,
            yesNo(packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)))
        setRow(binding.rowAdvertisingSupported, R.string.advertising_supported_label,
            yesNo(advertiser.isAdvertisingSupported))
        setRow(binding.rowExtendedAdvertising, R.string.extended_advertising_label,
            yesNo(advertiser.isExtendedAdvertisingSupported))
        setRow(binding.rowBluetoothEnabled, R.string.bluetooth_enabled_label,
            yesNo(advertiser.isBluetoothEnabled))
    }

    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, R.string.battery_already_exempt, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            // Some OEMs remove this activity - fall back to the general settings screen.
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun yesNo(value: Boolean): String = if (value) "YES" else "NO"

    private fun setRow(row: ItemDiagnosticBinding, @StringRes labelRes: Int, value: String) {
        row.textLabel.setText(labelRes)
        row.textValue.text = value
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    BeaconState.status.collect { status ->
                        setRow(binding.rowAdvertisingStatus, R.string.advertising_status_label,
                            if (status == BeaconState.Status.ADVERTISING) "ACTIVE" else "INACTIVE")
                    }
                }
                launch {
                    BeaconState.beaconUuid.collect { uuid ->
                        setRow(binding.rowBeaconUuid, R.string.beacon_uuid_label,
                            uuid?.toString() ?: getString(R.string.not_set))
                    }
                }
                launch {
                    BeaconState.advertisingMode.collect { mode ->
                        setRow(binding.rowAdvertisingMode, R.string.advertising_mode_label,
                            mode ?: getString(R.string.not_set))
                    }
                }
                launch {
                    BeaconState.requestedTxPower.collect { power ->
                        setRow(binding.rowRequestedTxPower, R.string.requested_tx_power_label,
                            power ?: getString(R.string.not_set))
                    }
                }
                launch {
                    BeaconState.actualTxPower.collect { power ->
                        setRow(binding.rowActualTxPower, R.string.actual_tx_power_label,
                            power ?: getString(R.string.not_set))
                    }
                }
                launch {
                    BeaconState.supabaseStatus.collect { status ->
                        setRow(binding.rowSupabaseStatus, R.string.supabase_status_label,
                            status ?: getString(R.string.not_reported))
                    }
                }
                launch {
                    BeaconState.bluetoothMac.collect { mac ->
                        setRow(binding.rowBluetoothMac, R.string.bluetooth_mac_label,
                            mac ?: getString(R.string.not_set))
                    }
                }
                launch {
                    BeaconState.deviceName.collect { name ->
                        setRow(binding.rowDeviceName, R.string.device_name_label,
                            name ?: getString(R.string.not_set))
                    }
                }
                launch {
                    BeaconState.logs.collect { logs ->
                        binding.textLogs.text = logs.joinToString("\n")
                        binding.scrollLogs.post {
                            binding.scrollLogs.fullScroll(View.FOCUS_DOWN)
                        }
                    }
                }
            }
        }
    }
}