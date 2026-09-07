package com.facultybeacon.network

/**
 * Supabase configuration - EDIT THESE to match your existing ESP32 backend.
 *
 * The ESP32 posted a row with: UUID, Bluetooth MAC, device name and device secret.
 * Point these constants at the same project/table/columns so the database keeps
 * receiving the exact same information.
 */
object SupabaseConfig {

    /** e.g. "https://abcdefghijklm.supabase.co" (no trailing slash) */
    const val SUPABASE_URL = "https://mqzwholysrukuhbdqisc.supabase.co"

    /** The public anon key from Supabase > Project Settings > API. */
    const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im1xendob2x5c3J1a3VoYmRxaXNjIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NTA3NTczMjksImV4cCI6MjA2NjMzMzMyOX0.GJO-guz1oy3BzO0tj19TbgCu0Aa2WtfLH8LrZ8u3YRI"

    /** Table the ESP32 writes beacon sessions to. */
    const val TABLE_NAME = "test_connections"

    // Column names - adjust if the ESP32 table uses different names.
    // A column set to null is simply omitted from the POST body (useful when the
    // table does not have that column, e.g. device_name below).
    const val COL_UUID: String? = "test_message"
    const val COL_BLUETOOTH_MAC: String? = "device_id"
    const val COL_DEVICE_NAME: String? = null
    const val COL_DEVICE_SECRET: String? = "device_secret"

    /** Master switch - set false to run the beacon without a backend (e.g. lab testing). */
    const val ENABLED = true

    /**
     * The ESP32 only starts advertising AFTER successfully reporting to Supabase
     * (it turns Wi-Fi off only after the POST succeeds). Set to false to start
     * advertising even when Supabase is unreachable.
     */
    const val WAIT_FOR_SUPABASE_BEFORE_ADVERTISING = true

    /** Number of POST attempts before giving up on Supabase. */
    const val MAX_REPORT_ATTEMPTS = 3
}