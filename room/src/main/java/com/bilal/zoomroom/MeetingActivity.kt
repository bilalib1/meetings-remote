package com.bilal.zoomroom

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bilal.zoomroom.sdk.RoomSdk
import us.zoom.sdk.MeetingParameter
import us.zoom.sdk.MeetingServiceListener
import us.zoom.sdk.MeetingStatus
import us.zoom.sdk.MobileRTCVideoUnitAspectMode
import us.zoom.sdk.MobileRTCVideoUnitRenderInfo
import us.zoom.sdk.MobileRTCVideoView

/**
 * Our own in-meeting screen (the SDK's customized-UI mode is on, so it shows no
 * toolbar of its own — no Share / More / captions / AI). Full-screen active
 * speaker on the "TV", with a minimal control bar: Mute, Video, Leave, and a
 * live participant count. The RTSP camera keeps feeding via the external video
 * source regardless of this UI.
 */
class MeetingActivity : Activity(), MeetingServiceListener {

    private val BG = 0xFF0A0C10.toInt()
    private val TILE = 0xFF1C212A.toInt()
    private val TEXT = 0xFFF4F6F8.toInt()
    private val MUTED = 0xFF8B929C.toInt()
    private val RED = 0xFFF0453A.toInt()

    private lateinit var videoView: MobileRTCVideoView
    private lateinit var muteCtl: Ctl
    private lateinit var videoCtl: Ctl
    private lateinit var participantsCtl: Ctl
    private var activeShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        RoomSdk.addMeetingListener(this)
        RoomSdk.connectAudio()
        showActiveVideo()
        render()
        // Retry: the remote participant list can populate a beat after join.
        for (d in listOf(600L, 1500L, 3000L)) {
            videoView.postDelayed({ showActiveVideo(); render() }, d)
        }
    }

    override fun onResume() {
        super.onResume()
        runCatching { videoView.onResume() }
        showActiveVideo()
        render()
    }

    override fun onPause() {
        super.onPause()
        runCatching { videoView.onPause() }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { videoView.getVideoViewManager()?.removeAllVideoUnits() }
        RoomSdk.removeMeetingListener(this)
    }

    override fun onBackPressed() { confirmLeave() }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(BG) }

        videoView = MobileRTCVideoView(this).apply {
            setZOrderMediaOverlay(false)
        }
        root.addView(videoView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Bottom control bar. Inset above the system nav bar / Samsung taskbar
        // so the controls aren't cut off (and taps don't fall through to it).
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(20))
            setOnApplyWindowInsetsListener { v, insets ->
                val bottom = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars() or
                        android.view.WindowInsets.Type.displayCutout()).bottom
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, dp(20) + bottom)
                insets
            }
        }
        muteCtl = ctl("mute", R.drawable.ic_mic, "Mute") { RoomSdk.toggleAudio(); refresh() }
        videoCtl = ctl("video", R.drawable.ic_video, "Stop video") { RoomSdk.toggleVideo(); refresh() }
        participantsCtl = ctl("people", R.drawable.ic_participants, "1") { /* count only */ }
        val leave = leaveButton()
        for (c in listOf(muteCtl, videoCtl, participantsCtl)) {
            bar.addView(c.root, LinearLayout.LayoutParams(dp(96),
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        bar.addView(leave, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8)
        })
        root.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        bar.requestApplyInsets()
        return root
    }

    inner class Ctl(val root: LinearLayout, val circle: FrameLayout,
                    val icon: ImageView, val label: TextView)

    private fun ctl(key: String, iconRes: Int, text: String, onTap: () -> Unit): Ctl {
        val icon = ImageView(this).apply {
            setImageResource(iconRes); imageTintList = ColorStateList.valueOf(Color.WHITE)
        }
        val circle = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(TILE) }
            foreground = ripple(dpf(32f))
            addView(icon, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        }
        val label = TextView(this).apply {
            this.text = text; setTextColor(MUTED); textSize = 12f
            gravity = Gravity.CENTER; setPadding(0, dp(7), 0, 0); maxLines = 1
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onTap() }
            addView(circle, LinearLayout.LayoutParams(dp(58), dp(58)))
            addView(label)
        }
        return Ctl(root, circle, icon, label)
    }

    private fun leaveButton(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dpf(22f); setColor(RED) }
            foreground = ripple(dpf(22f))
            isClickable = true
            setPadding(dp(20), dp(12), dp(22), dp(12))
            setOnClickListener { confirmLeave() }
        }
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_leave); imageTintList = ColorStateList.valueOf(Color.WHITE)
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(8) })
        row.addView(TextView(this).apply {
            text = "Leave"; setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
        })
        return row
    }

    private fun ripple(radius: Float): RippleDrawable {
        val mask = GradientDrawable().apply { cornerRadius = radius; setColor(Color.WHITE) }
        return RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), null, mask)
    }

    // ------------------------------------------------------------- video

    private var shownUserId = -1L

    private fun showActiveVideo() {
        // Show the far end (first remote participant), not the active speaker —
        // the active speaker can be us, and the SDK won't render our own
        // external source in that unit (renders black). Post so the view is
        // laid out first (else it renders into a 0x0 surface).
        videoView.post {
            val mgr = videoView.getVideoViewManager() ?: return@post
            val remote = RoomSdk.firstRemoteUserId()
            if (remote == null) { activeShown = false; return@post }
            if (remote == shownUserId && activeShown) return@post
            runCatching { mgr.removeAllVideoUnits() }
            val info = MobileRTCVideoUnitRenderInfo(0, 0, 100, 100).apply {
                is_username_visible = true
                aspect_mode = MobileRTCVideoUnitAspectMode.VIDEO_ASPECT_LETTER_BOX
            }
            activeShown = mgr.addAttendeeVideoUnit(remote, info)
            shownUserId = remote
        }
    }

    private fun render() {
        val muted = RoomSdk.isAudioMuted()
        muteCtl.icon.setImageResource(if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        (muteCtl.circle.background as GradientDrawable).setColor(if (muted) RED else TILE)
        muteCtl.label.text = if (muted) "Unmute" else "Mute"

        val videoOn = RoomSdk.isVideoOn()
        videoCtl.icon.setImageResource(if (videoOn) R.drawable.ic_video else R.drawable.ic_video_off)
        videoCtl.label.text = if (videoOn) "Stop video" else "Start video"

        participantsCtl.label.text = RoomSdk.participantCount().coerceAtLeast(1).toString()
        if (!activeShown) showActiveVideo()
    }

    /** Re-render now and again shortly after (SDK mute state updates async). */
    private fun refresh() {
        render()
        muteCtl.root.postDelayed({ render() }, 300)
    }

    private fun confirmLeave() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Leave meeting?")
            .setPositiveButton("Leave") { _, _ -> RoomSdk.leave() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    // ---------------------------------------------- MeetingServiceListener

    override fun onMeetingStatusChanged(status: MeetingStatus?, errorCode: Int, internalErrorCode: Int) {
        when (status) {
            MeetingStatus.MEETING_STATUS_INMEETING -> runOnUiThread { render() }
            MeetingStatus.MEETING_STATUS_ENDED, MeetingStatus.MEETING_STATUS_IDLE,
            MeetingStatus.MEETING_STATUS_FAILED -> finish()
            else -> {}
        }
    }

    override fun onMeetingParameterNotification(param: MeetingParameter?) {}
}
