package com.bilal.zoomroom.sdk

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for our token backend, which holds all Zoom developer secrets so the
 * tablet never does. The app fetches the Meeting SDK JWT (to init the SDK and
 * join meetings) and, for hosting, drives Zoom OAuth and gets the user's ZAK.
 *
 * Call these off the main thread.
 */
class RoomBackend(base: String) {
    private val base = base.trimEnd('/')

    /** Meeting SDK JWT for ZoomSDK.initialize — all that's needed to join. */
    fun sdkJwt(): String? = get("/sdk-jwt")?.optString("token")?.ifBlank { null }

    /** URL to open in a browser for "Sign in with Zoom" (hosting). */
    fun oauthStartUrl(state: String): String = "$base/oauth/start?state=$state"

    /** Poll after OAuth; returns (name, zak) once the user has signed in. */
    fun session(state: String): Pair<String, String>? {
        val j = get("/session?state=$state") ?: return null
        if (!j.optBoolean("ready")) return null
        return j.optString("name") to j.optString("zak")
    }

    private fun get(path: String): JSONObject? = try {
        val c = (URL("$base$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000; readTimeout = 5000
        }
        if (c.responseCode in 200..299) JSONObject(c.inputStream.bufferedReader().readText())
        else { android.util.Log.w("RoomBackend", "GET $base$path -> ${c.responseCode}"); null }
    } catch (e: Exception) {
        android.util.Log.w("RoomBackend", "GET $base$path failed: $e")
        null
    }
}
