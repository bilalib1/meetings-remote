package com.bilal.meetingsremote.sdk

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Client for our token backend, which holds all Zoom developer secrets so the
 * tablet never does. The app fetches the Meeting SDK JWT (to init the SDK and
 * join meetings) and, for hosting, drives per-user Zoom OAuth (PKCE) and gets
 * that user's ZAK.
 *
 * Auth model: the tablet holds only an opaque, revocable session id (`sid`).
 * The backend keeps the Zoom refresh token keyed by `sid` and derives the ZAK
 * on demand. See plan 2026-07-08-playstore-and-oauth.md §7.
 *
 * Call these off the main thread.
 */
class RoomBackend(base: String) {
    private val base = base.trimEnd('/')

    /** Meeting SDK JWT for ZoomSDK.initialize — all that's needed to join. */
    fun sdkJwt(): String? = get("/sdk-jwt")?.optString("token")?.ifBlank { null }

    /** Host identity for the signed-in user: their display name, ZAK, and PMI. */
    data class Host(val name: String, val zak: String, val pmi: String)

    /**
     * Browser URL for "Sign in with Zoom". The backend generates the PKCE
     * verifier server-side (keyed by [sid]) and 302s to Zoom's login; on
     * success it bounces back to the app via the /return App Link.
     */
    fun oauthStartUrl(sid: String): String = "$base/oauth/start?sid=${enc(sid)}"

    /**
     * Current session for [sid]: (name, zak, pmi) if signed in, else null.
     * The backend auto-refreshes an expired ZAK, so this is enough to host.
     */
    fun session(sid: String): Host? = hostFrom(get("/session?sid=${enc(sid)}"))

    /** Force a fresh ZAK via the stored (rotating) refresh token; no login. */
    fun refresh(sid: String): Host? = hostFrom(get("/refresh?sid=${enc(sid)}"))

    /** Revoke the Zoom token and drop the server-side session. */
    fun signOut(sid: String): Boolean = get("/signout?sid=${enc(sid)}")?.optBoolean("ok") == true

    /** Force-end the signed-in user's PMI on Zoom's side. Recovers a meeting
     *  stranded "in progress" by a crash, which blocks starts with error 100/80. */
    fun endStuckMeeting(sid: String): Boolean =
        get("/end-stuck-meeting?sid=${enc(sid)}")?.optBoolean("ok") == true

    private fun hostFrom(j: JSONObject?): Host? {
        if (j == null || !j.optBoolean("ready")) return null
        val zak = j.optString("zak").ifBlank { null } ?: return null
        return Host(j.optString("name", "Meeting Room"), zak, j.optString("pmi", ""))
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

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
