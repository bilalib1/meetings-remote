package com.bilal.meetingsremote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bilal.meetingsremote.sdk.RoomSdk

/**
 * adb-scriptable test hooks so meeting cycles can be driven without touching
 * the screen (start is already scriptable via MainActivity intent extras;
 * there was no way to leave). Debug builds only.
 *
 *   adb shell am broadcast -n com.bilal.meetingsremote/.TestHooksReceiver \
 *       -a com.bilal.meetingsremote.DEBUG_CMD --es cmd leave
 *
 * cmd=leave       -> RoomSdk.leave() (what the Leave button does)
 * cmd=leaveNoEnd  -> leave WITHOUT ending — strands a hosted PMI (§17 repro)
 * cmd=audioDelay --ei ms N -> set the virtual-mic delay (AV-sync knob)
 * cmd=audioStats  -> log mic + sync-estimator state
 * cmd=syncNow     -> force a GCC-PHAT estimate on the next tick
 */
class TestHooksReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        when (val cmd = intent.getStringExtra("cmd")) {
            "leave" -> RoomSdk.leave()
            "leaveNoEnd" -> RoomSdk.leaveNoEnd()
            "dump" -> Log.i("TestHooks",
                "status=${RoomSdk.meetingService()?.meetingStatus} " +
                "participants=${RoomSdk.participants()}")
            "audioDelay" -> {
                val ms = intent.getIntExtra("ms", -1)
                if (ms >= 0) RoomSdk.setMicDelayMs(ms)
                Log.i("TestHooks", "audioDelay ms=$ms -> ${RoomSdk.audioStats()}")
            }
            "audioStats" -> Log.i("TestHooks", RoomSdk.audioStats())
            "syncNow" -> { RoomSdk.syncNow(); Log.i("TestHooks", "syncNow requested") }
            "mlNow" -> { RoomSdk.mlNow(); Log.i("TestHooks", "mlNow requested") }
            else -> Log.w("TestHooks", "unknown cmd=$cmd")
        }
    }
}
