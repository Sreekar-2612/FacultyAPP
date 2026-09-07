package com.facultybeacon

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.facultybeacon.beacon.BeaconService
import com.facultybeacon.beacon.BeaconState
import com.facultybeacon.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * Main screen: START BEACON / STOP BEACON plus live status, mirroring the UI sketch
 * in the spec. The heavy lifting happens in [BeaconService].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var lastShownError: String? = null

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (bluetoothReady()) {
                startBeacon()
            } else {
                Toast.makeText(this, R.string.bluetooth_required, Toast.LENGTH_LONG).show()
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (blockingPermissionsGranted()) {
                if (bluetoothReady()) startBeacon() else promptEnableBluetooth()
            } else {
                BeaconState.addLog("Required runtime permissions were not granted")
                Toast.makeText(this, R.string.permissions_required, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonStart.setOnClickListener { onStartBeaconClicked() }
        binding.buttonStop.setOnClickListener { onStopBeaconClicked() }
        binding.buttonTesting.setOnClickListener {
            startActivity(Intent(this, TestingActivity::class.java))
        }

        refreshFromCurrentState()
        observeState()
    }

    /** Initializes the labels from the current state (the flows below keep them fresh). */
    private fun refreshFromCurrentState() {
        updateStatusUi(BeaconState.status.value)
        binding.textBeaconId.text = getString(
            R.string.beacon_id_label,
            BeaconState.beaconUuid.value?.toString() ?: getString(R.string.not_set)
        )
        binding.textSupabase.text = getString(
            R.string.supabase_label,
            BeaconState.supabaseStatus.value ?: getString(R.string.not_reported)
        )
    }

    private fun updateStatusUi(status: BeaconState.Status) {
        binding.textStatus.text = getString(R.string.status_label, status.label)
        val running = status == BeaconState.Status.STARTING ||
            status == BeaconState.Status.REPORTING ||
            status == BeaconState.Status.ADVERTISING
        binding.buttonStart.isEnabled = !running
        binding.buttonStop.isEnabled = running
        binding.textAdvertising.text = getString(
            R.string.advertising_label,
            if (status == BeaconState.Status.ADVERTISING) "Active" else "Inactive"
        )
    }

    // ------------------------------------------------------------------ user actions

    private fun onStartBeaconClicked() {
        if (!blockingPermissionsGranted()) {
            permissionLauncher.launch(permissionsToRequest())
            return
        }
        if (!bluetoothReady()) {
            promptEnableBluetooth()
            return
        }
        startBeacon()
    }

    private fun onStopBeaconClicked() {
        startService(Intent(this, BeaconService::class.java).setAction(BeaconService.ACTION_STOP))
    }

    private fun startBeacon() {
        startForegroundService(
            Intent(this, BeaconService::class.java).setAction(BeaconService.ACTION_START)
        )
    }

    private fun promptEnableBluetooth() {
        val enableIntent = Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
        runCatching { enableBluetoothLauncher.launch(enableIntent) }
            .onFailure {
                Toast.makeText(this, R.string.bluetooth_required, Toast.LENGTH_LONG).show()
            }
    }

    // ------------------------------------------------------------------ permissions

    /**
     * Permissions that block starting the beacon if not granted:
     *  - Android 12+ (API 31): BLUETOOTH_ADVERTISE (+ BLUETOOTH_CONNECT for the
     *    connectedDevice foreground service on Android 14).
     *  - Android 6-11 (API 23-30): BLE advertising requires location permission.
     */
    private fun blockingPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** Blocking permissions plus optional ones (notifications on Android 13+). */
    private fun permissionsToRequest(): Array<String> =
        blockingPermissions() +
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            }

    private fun blockingPermissionsGranted(): Boolean =
        blockingPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun bluetoothReady(): Boolean {
        val manager = getSystemService(BluetoothManager::class.java)
        return manager.adapter?.isEnabled == true
    }

    // ------------------------------------------------------------------ state -> UI

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    BeaconState.status.collect { status -> updateStatusUi(status) }
                }
                launch {
                    BeaconState.beaconUuid.collect { uuid ->
                        binding.textBeaconId.text = getString(
                            R.string.beacon_id_label,
                            uuid?.toString() ?: getString(R.string.not_set)
                        )
                    }
                }
                launch {
                    BeaconState.supabaseStatus.collect { status ->
                        binding.textSupabase.text = status
                            ?.let { getString(R.string.supabase_label, it) }
                            ?: getString(R.string.supabase_label, getString(R.string.not_reported))
                    }
                }
                launch {
                    BeaconState.lastError.collect { error ->
                        if (error != null && error != lastShownError) {
                            lastShownError = error
                            Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }
}