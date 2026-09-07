package com.facultybeacon.beacon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.facultybeacon.MainActivity
import com.facultybeacon.R
import com.facultybeacon.network.SupabaseConfig
import com.facultybeacon.network.SupabaseReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the beacon lifecycle, mirroring the ESP32 sequence:
 *
 *   generate random UUID  ->  report session to Supabase  ->  configure advertisement
 *   ->  start continuous non-connectable BLE broadcast
 *
 * Running as a foreground service (with a partial wake lock) keeps the broadcast alive
 * when the app is backgrounded and when the screen is off - a hard requirement for a
 * classroom beacon.
 */
class BeaconService : Service() {

    companion object {
        const val ACTION_START = "com.facultybeacon.action.START_BEACON"
        const val ACTION_STOP = "com.facultybeacon.action.STOP_BEACON"

        private const val CHANNEL_ID = "faculty_beacon"
        private const val NOTIFICATION_ID = 1
        private const val WAKELOCK_TAG = "FacultyBeacon:advertising"
        private const val SUPABASE_RETRY_DELAY_MS = 2_000L
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var advertiser: BleBeaconAdvertiser
    private lateinit var sessionManager: BeaconSessionManager
    private lateinit var reporter: SupabaseReporter

    private var wakeLock: PowerManager.WakeLock? = null
    private var startJob: Job? = null
    private var bluetoothStateReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        advertiser = BleBeaconAdvertiser(this)
        sessionManager = BeaconSessionManager(this)
        reporter = SupabaseReporter()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopBeacon()
            else -> startBeacon()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        stopAdvertisingInternal()
        unregisterBluetoothStateReceiver()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ start flow

    private fun startBeacon() {
        if (BeaconState.status.value == BeaconState.Status.ADVERTISING || startJob?.isActive == true) {
            return
        }

        startForegroundWithNotification()
        BeaconState.setStatus(BeaconState.Status.STARTING)
        BeaconState.addLog("Beacon started")
        registerBluetoothStateReceiver()

        startJob = serviceScope.launch {
            // 1) Fresh 128-bit identifier per session.
            val session = sessionManager.newSession()
            BeaconState.newSession(session)
            BeaconState.addLog("UUID generated: ${session.uuid}")
            BeaconState.addLog("Device name: ${session.deviceName}, Bluetooth MAC: ${session.bluetoothMac}")

            // 2) Report to Supabase (like the ESP32's POST before it turns Wi-Fi off).
            if (SupabaseConfig.ENABLED) {
                BeaconState.setStatus(BeaconState.Status.REPORTING)
                BeaconState.addLog("Sending beacon info to Supabase")
                val reported = reportWithRetry(session)
                BeaconState.setSupabaseStatus(
                    if (reported) "Reported to Supabase" else "Supabase report FAILED"
                )
                if (!reported && SupabaseConfig.WAIT_FOR_SUPABASE_BEFORE_ADVERTISING) {
                    BeaconState.setError("Supabase unreachable after ${SupabaseConfig.MAX_REPORT_ATTEMPTS} attempts - beacon not started")
                    BeaconState.addLog("Advertising error: Supabase unreachable, refusing to start beacon")
                    stopSelf()
                    return@launch
                }
            } else {
                BeaconState.setSupabaseStatus("Supabase disabled in config")
            }

            // 3) Configure and start the advertisement.
            BeaconState.setStatus(BeaconState.Status.STARTING)
            BeaconState.setRequestedTxPower(
                if (advertiser.isExtendedAdvertisingSupported) {
                    "${BleBeaconAdvertiser.DEFAULT_TX_POWER_DBM} dBm (extended advertising)"
                } else {
                    "HIGH (legacy preset, device-dependent)"
                }
            )
            BeaconState.addLog(
                "Advertisement configured: manufacturer 0x" +
                    BlePayload.MANUFACTURER_ID.toString(16) + " + 16-byte UUID, non-connectable"
            )
            acquireWakeLock()

            advertiser.start(
                session.uuid,
                BleBeaconAdvertiser.DEFAULT_TX_POWER_DBM,
                object : BleBeaconAdvertiser.Callback {
                    override fun onStarted(actualTxPower: String, modeDescription: String) {
                        BeaconState.setAdvertisingMode(modeDescription)
                        BeaconState.setActualTxPower(actualTxPower)
                        BeaconState.setStatus(BeaconState.Status.ADVERTISING)
                        BeaconState.addLog("Advertisement started (TX power: $actualTxPower)")
                        updateNotification()
                    }

                    override fun onStopped() {
                        // Keep an ERROR status (e.g. Bluetooth switched off) instead of
                        // overwriting it with STOPPED when the stop was caused by a fault.
                        val current = BeaconState.status.value
                        if (current == BeaconState.Status.ADVERTISING || current == BeaconState.Status.STARTING) {
                            BeaconState.setStatus(BeaconState.Status.STOPPED)
                        }
                        BeaconState.addLog("Advertisement stopped")
                        releaseWakeLock()
                        updateNotification()
                        stopSelf()
                    }

                    override fun onError(message: String) {
                        BeaconState.setError(message)
                        BeaconState.addLog("Advertising error: $message")
                        releaseWakeLock()
                        stopSelf()
                    }
                }
            )
        }
    }

    private suspend fun reportWithRetry(session: BeaconSession): Boolean {
        var attempt = 1
        while (attempt <= SupabaseConfig.MAX_REPORT_ATTEMPTS) {
            BeaconState.addLog("Supabase attempt $attempt/${SupabaseConfig.MAX_REPORT_ATTEMPTS}")
            when (val result = reporter.reportSession(session)) {
                is SupabaseReporter.ReportResult.Success -> {
                    BeaconState.addLog("Supabase report successful")
                    return true
                }
                is SupabaseReporter.ReportResult.Failure -> {
                    BeaconState.addLog("Supabase report failed: ${result.message}")
                    if (attempt < SupabaseConfig.MAX_REPORT_ATTEMPTS) {
                        delay(SUPABASE_RETRY_DELAY_MS)
                    }
                }
            }
            attempt++
        }
        return false
    }

    // ------------------------------------------------------------------- stop flow

    private fun stopBeacon() {
        startJob?.cancel()
        BeaconState.addLog("Stopping beacon...")
        stopAdvertisingInternal()
    }

    private fun stopAdvertisingInternal() {
        val wasAdvertising = advertiser.isAdvertising
        advertiser.stop()
        releaseWakeLock()
        if (!wasAdvertising) {
            // Nothing was broadcasting; the advertiser callback will not fire.
            BeaconState.setStatus(BeaconState.Status.STOPPED)
            BeaconState.addLog("Advertisement stopped")
            stopSelf()
        }
    }

    // ----------------------------------------------------------- foreground notification

    private fun startForegroundWithNotification() {
        createNotificationChannel()
        val notification = buildNotification("Starting beacon...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = getString(R.string.notification_channel_description)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BeaconService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_beacon)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.stop_beacon), stopIntent)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(currentStatusText()))
    }

    private fun currentStatusText(): String = when (BeaconState.status.value) {
        BeaconState.Status.ADVERTISING ->
            "Advertising: ${BeaconState.beaconUuid.value ?: "..."}"
        BeaconState.Status.REPORTING -> "Reporting to Supabase..."
        BeaconState.Status.ERROR ->
            "Error: ${BeaconState.lastError.value ?: "unknown"}"
        else -> "Status: ${BeaconState.status.value.label}"
    }

    // ---------------------------------------------------------------------- wake lock

    /** A partial wake lock helps keep the radio advertising with the screen off. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    // ----------------------------------------------------------- bluetooth state changes

    private fun registerBluetoothStateReceiver() {
        if (bluetoothStateReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                        BeaconState.addLog("Bluetooth turned off - stopping beacon")
                        BeaconState.setError("Bluetooth was turned off")
                        stopBeacon()
                    }
                }
            }
        }
        registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        bluetoothStateReceiver = receiver
    }

    private fun unregisterBluetoothStateReceiver() {
        bluetoothStateReceiver?.let { runCatching { unregisterReceiver(it) } }
        bluetoothStateReceiver = null
    }
}