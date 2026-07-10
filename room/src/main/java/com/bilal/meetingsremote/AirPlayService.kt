package com.bilal.meetingsremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Foreground service that owns the [MediaProjection] and the AirPlay mirror.
 * Android 14+ requires a running `mediaProjection`-type foreground service
 * before a projection can start, so the capture pipeline lives here rather than
 * in the Activity.
 *
 * Start with [start] (passing the screen-capture consent result); stop with
 * [stop]. Creds/target come from BuildConfig (local.properties).
 */
class AirPlayService : Service() {

    private var caster: AirPlayCaster? = null
    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startForegroundNotification()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (data == null) {
                    Log.w(TAG, "no projection result data; stopping")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startCasting(resultCode, data)
            }
        }
        return START_NOT_STICKY
    }

    private fun startCasting(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data)
        if (proj == null) {
            Log.w(TAG, "getMediaProjection returned null; stopping")
            stopSelf()
            return
        }
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "projection stopped by system/user")
                teardown()
                stopSelf()
            }
        }, null)

        // Mirror as a landscape 16:9-ish frame so it fills the TV instead of
        // showing a small portrait box. Long side = width, capped at 1920.
        val dm = resources.displayMetrics
        var w = maxOf(dm.widthPixels, dm.heightPixels)
        var h = minOf(dm.widthPixels, dm.heightPixels)
        if (w > 1920) {
            h = h * 1920 / w
            w = 1920
        }
        w = w and 1.inv() // even dimensions for H.264
        h = h and 1.inv()
        val c = AirPlayCaster(proj, w, h, dm.densityDpi)
        caster = c

        // Mobile.start blocks on pair-verify + SETUP, so connect off the main thread.
        Thread({
            try {
                c.start(
                    host = BuildConfig.AIRPLAY_HOST,
                    port = BuildConfig.AIRPLAY_PORT,
                    pairingId = BuildConfig.AIRPLAY_PAIRING_ID,
                    ed25519Seed = hexToBytes(BuildConfig.AIRPLAY_SEED_HEX),
                )
                Log.i(TAG, "AirPlay mirror started -> ${BuildConfig.AIRPLAY_HOST}")
            } catch (e: Exception) {
                Log.e(TAG, "AirPlay start failed: ${e.message}", e)
                teardown()
                stopSelf()
            }
        }, "airplay-start").start()
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Screen mirroring", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Casting to TV")
            .setContentText("Mirroring this meeting via AirPlay")
            .setSmallIcon(R.drawable.ic_cast)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun teardown() {
        runCatching { caster?.stop() }
        caster = null
        runCatching { projection?.stop() }
        projection = null
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AirPlayService"
        private const val CHANNEL = "airplay_cast"
        private const val NOTIF_ID = 42
        private const val ACTION_START = "com.bilal.meetingsremote.AIRPLAY_START"
        private const val ACTION_STOP = "com.bilal.meetingsremote.AIRPLAY_STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"

        /** True once a mirror has been requested; cleared on stop. UI convenience. */
        @Volatile var active: Boolean = false
            private set

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            active = true
            val i = Intent(ctx, AirPlayService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            active = false
            ctx.startService(Intent(ctx, AirPlayService::class.java).apply { action = ACTION_STOP })
        }

        private fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte() }
    }
}
