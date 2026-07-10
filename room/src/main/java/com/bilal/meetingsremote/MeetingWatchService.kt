package com.bilal.zoomroom

import android.app.Service
import android.content.Intent
import android.util.Log
import com.bilal.zoomroom.sdk.RoomSdk

/**
 * Exists only so Android tells us when the user swipes the app out of recents
 * mid-meeting: onTaskRemoved fires before the process dies, giving us a beat
 * to end/leave the meeting instead of orphaning it on Zoom's side. A PMI
 * stranded "in progress" blocks Start Meeting with error 100/80 for ~10 min
 * (plan §17). Started on INMEETING, stopped on ENDED/IDLE (MainActivity);
 * stopWithTask=false in the manifest is what makes onTaskRemoved fire.
 */
class MeetingWatchService : Service() {

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i("MeetingWatch", "task removed mid-meeting — ending/leaving")
        runCatching { RoomSdk.leave() }
        // The leave is async; hold the process open briefly so it reaches Zoom.
        try { Thread.sleep(1500) } catch (_: InterruptedException) {}
        stopSelf()
    }
}
