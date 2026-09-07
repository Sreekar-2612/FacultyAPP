package com.facultybeacon.network

import com.facultybeacon.beacon.BeaconSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Reports a beacon session to the existing Supabase backend using the PostgREST REST API -
 * the same endpoint style the ESP32 uses (POST to /rest/v1/<table> with the anon key).
 */
class SupabaseReporter(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
) {

    sealed class ReportResult {
        object Success : ReportResult()
        data class Failure(val message: String) : ReportResult()
    }

    /** Inserts one row for the session (columns set to null in [SupabaseConfig] are omitted). */
    suspend fun reportSession(session: BeaconSession): ReportResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            putIfConfigured(SupabaseConfig.COL_UUID, session.uuid.toString())
            putIfConfigured(SupabaseConfig.COL_BLUETOOTH_MAC, session.bluetoothMac)
            putIfConfigured(SupabaseConfig.COL_DEVICE_NAME, session.deviceName)
            putIfConfigured(SupabaseConfig.COL_DEVICE_SECRET, session.deviceSecret)
        }.toString()

        val request = Request.Builder()
            .url("${SupabaseConfig.SUPABASE_URL}/rest/v1/${SupabaseConfig.TABLE_NAME}")
            .header("apikey", SupabaseConfig.SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer ${SupabaseConfig.SUPABASE_ANON_KEY}")
            .header("Content-Type", "application/json")
            .header("Prefer", "return=minimal")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    ReportResult.Success
                } else {
                    val detail = response.body?.string().orEmpty().take(300)
                    ReportResult.Failure("Supabase returned HTTP ${response.code}: $detail")
                }
            }
        } catch (e: Exception) {
            ReportResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun JSONObject.putIfConfigured(column: String?, value: String) {
        if (column != null) put(column, value)
    }
}