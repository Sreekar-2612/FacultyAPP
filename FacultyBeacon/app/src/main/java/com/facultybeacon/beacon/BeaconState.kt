package com.facultybeacon.beacon

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Process-wide, in-memory state shared between the foreground service (which does the
 * actual work) and the UI activities (which display it). Flows are observed by the UI
 * so the screens always reflect what the beacon is currently doing.
 */
object BeaconState {

    const val TAG = "FacultyBeacon"

    enum class Status(val label: String) {
        IDLE("Idle"),
        STARTING("Starting..."),
        REPORTING("Reporting to Supabase..."),
        ADVERTISING("Advertising"),
        ERROR("Error"),
        STOPPED("Stopped")
    }

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _beaconUuid = MutableStateFlow<UUID?>(null)
    val beaconUuid: StateFlow<UUID?> = _beaconUuid.asStateFlow()

    private val _bluetoothMac = MutableStateFlow<String?>(null)
    val bluetoothMac: StateFlow<String?> = _bluetoothMac.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private val _advertisingMode = MutableStateFlow<String?>(null)
    val advertisingMode: StateFlow<String?> = _advertisingMode.asStateFlow()

    private val _requestedTxPower = MutableStateFlow<String?>(null)
    val requestedTxPower: StateFlow<String?> = _requestedTxPower.asStateFlow()

    private val _actualTxPower = MutableStateFlow<String?>(null)
    val actualTxPower: StateFlow<String?> = _actualTxPower.asStateFlow()

    private val _supabaseStatus = MutableStateFlow<String?>(null)
    val supabaseStatus: StateFlow<String?> = _supabaseStatus.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private const val MAX_LOGS = 300

    fun setStatus(status: Status) {
        _status.value = status
    }

    /** Called when a new beacon session (new UUID) is created. */
    fun newSession(session: BeaconSession) {
        _beaconUuid.value = session.uuid
        _bluetoothMac.value = session.bluetoothMac
        _deviceName.value = session.deviceName
        _lastError.value = null
    }

    fun setAdvertisingMode(mode: String?) {
        _advertisingMode.value = mode
    }

    fun setRequestedTxPower(power: String?) {
        _requestedTxPower.value = power
    }

    fun setActualTxPower(power: String?) {
        _actualTxPower.value = power
    }

    fun setSupabaseStatus(status: String?) {
        _supabaseStatus.value = status
    }

    fun setError(message: String) {
        _lastError.value = message
        _status.value = Status.ERROR
    }

    /** Resets per-session values (used when a beacon session ends). */
    fun resetSession() {
        _beaconUuid.value = null
        _bluetoothMac.value = null
        _deviceName.value = null
        _advertisingMode.value = null
        _requestedTxPower.value = null
        _actualTxPower.value = null
        _lastError.value = null
    }

    /** Adds a line to the in-app log (kept bounded) and to logcat. */
    fun addLog(message: String) {
        Log.d(TAG, message)
        _logs.value = (_logs.value + message).takeLast(MAX_LOGS)
    }
}