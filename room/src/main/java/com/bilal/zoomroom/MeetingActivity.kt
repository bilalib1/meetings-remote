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
    private lateinit var selfPreview: SelfPreviewView
    private lateinit var muteCtl: Ctl
    private lateinit var videoCtl: Ctl
    private lateinit var participantsCtl: Ctl
    private lateinit var participantsCount: TextView
    private var activeShown = false
    private var selfExpanded = false
    private var selfAnimator: android.animation.ValueAnimator? = null
    private var selfScrim: View? = null
    private var participantsDialog: android.app.AlertDialog? = null
    private var participantsList: LinearLayout? = null
    private val participantsPoll = object : Runnable {
        override fun run() {
            if (participantsDialog?.isShowing != true) return
            fillParticipants()
            participantsCtl.root.postDelayed(this, 1500)
        }
    }

    // Ground-truth outgoing fps: what Zoom's encoder actually puts on the wire,
    // as opposed to how often we call sendVideoFrame. Logged for tools/measure_fps.py.
    private val statsPoll = object : Runnable {
        override fun run() {
            runCatching {
                us.zoom.sdk.ZoomSDK.getInstance()
                    .inMeetingService.inMeetingVideoController.meetingVideoStatisticInfo
            }.getOrNull()?.let { s ->
                android.util.Log.i("ZoomStats",
                    "video send=${s.sendFps}fps ${s.sendBandwidth}kbps " +
                        "loss=${s.sendPacketLossAvg}% recv=${s.recvFps}fps")
            }
            videoView.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        videoView.postDelayed(statsPoll, 3000)
        RoomSdk.setPreviewSink { buf, w, h -> selfPreview.submit(buf, w, h) }
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
        videoView.removeCallbacks(statsPoll)
        RoomSdk.setPreviewSink(null)
        participantsDialog?.dismiss()
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

        // Zoom-style self-view (our outgoing camera), floating top-right. Tap to
        // smoothly grow it to a large floating overlay and back. It always floats
        // above the meeting (elevation + rounded corners), never a modal takeover.
        selfPreview = SelfPreviewView(this).apply {
            isClickable = true
            elevation = dpf(10f)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                setSelfExpanded(!selfExpanded)
            }
        }
        root.addView(selfPreview, FrameLayout.LayoutParams(dp(260), dp(146),
            Gravity.TOP or Gravity.END).apply { topMargin = dp(44); rightMargin = dp(16) })

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
        participantsCtl = participantsControl { showParticipants() }
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

    /** Like ctl(), but the live count sits inside the bubble under the icon,
     *  with a static "Participants" label beneath. */
    private fun participantsControl(onTap: () -> Unit): Ctl {
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_participants)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
        }
        participantsCount = TextView(this).apply {
            text = "1"; setTextColor(Color.WHITE); textSize = 11f
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        val stack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(icon, LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(participantsCount, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(1)
            })
        }
        val circle = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(TILE) }
            foreground = ripple(dpf(32f))
            addView(stack, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        val label = TextView(this).apply {
            text = "Participants"; setTextColor(MUTED); textSize = 11f
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

        participantsCount.text = RoomSdk.participants().size.coerceAtLeast(1).toString()
        if (!videoOn && selfExpanded) setSelfExpanded(false)
        selfPreview.visibility = if (videoOn) View.VISIBLE else View.GONE
        if (!activeShown) showActiveVideo()
        if (participantsDialog?.isShowing == true) fillParticipants()
    }

    /** Re-render now and again shortly after (SDK mute state updates async). */
    private fun refresh() {
        render()
        muteCtl.root.postDelayed({ render() }, 300)
    }

    // ------------------------------------------------------- participants

    private fun showParticipants() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dpf(20f); setColor(0xFF12151C.toInt()) }
            setPadding(dp(20), dp(20), dp(20), dp(12))
        }
        panel.addView(TextView(this).apply {
            text = "Participants"; setTextColor(TEXT); textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        participantsList = list
        panel.addView(android.widget.ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(12); bottomMargin = dp(8)
            })
        val close = TextView(this).apply {
            text = "Close"; setTextColor(0xFF4C8DFF.toInt()); textSize = 15f
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.END
            setPadding(dp(12), dp(10), dp(8), dp(6)); isClickable = true
            setOnClickListener { participantsDialog?.dismiss() }
        }
        panel.addView(close, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        participantsDialog = android.app.AlertDialog.Builder(this)
            .setView(panel)
            .setOnDismissListener {
                participantsCtl.root.removeCallbacks(participantsPoll)
                participantsList = null; participantsDialog = null
            }
            .create().apply {
                window?.setBackgroundDrawable(
                    android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                show()
                window?.setLayout(dp(340), (resources.displayMetrics.heightPixels * 0.7f).toInt())
            }
        fillParticipants()
        participantsCtl.root.postDelayed(participantsPoll, 1500)
    }

    private fun fillParticipants() {
        val list = participantsList ?: return
        val people = RoomSdk.participants()
        list.removeAllViews()
        for (p in people) list.addView(participantRow(p))
    }

    private fun participantRow(p: RoomSdk.Participant): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val initial = p.name.trim().firstOrNull()?.uppercase() ?: "?"
        row.addView(TextView(this).apply {
            text = initial; setTextColor(Color.WHITE); textSize = 15f
            gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF2C6BE0.toInt()) }
        }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(14) })

        val label = buildString {
            append(p.name)
            if (p.isMe) append(" (You)")
            if (p.isHost) append(" (Host)")
        }
        row.addView(TextView(this).apply {
            text = label; setTextColor(TEXT); textSize = 15f; maxLines = 1
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(statusIcon(
            if (p.audioMuted) R.drawable.ic_mic_off else R.drawable.ic_mic,
            if (p.audioMuted) RED else MUTED))
        row.addView(statusIcon(
            if (p.videoOn) R.drawable.ic_video else R.drawable.ic_video_off,
            if (p.videoOn) MUTED else RED))
        return row
    }

    private fun statusIcon(res: Int, tint: Int): ImageView = ImageView(this).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { leftMargin = dp(16) }
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

    // Self-view geometry as [x, y, w, h] in the root's pixel coordinates.
    private fun rootW() = (selfPreview.parent as? View)?.width?.takeIf { it > 0 }
        ?: resources.displayMetrics.widthPixels
    private fun rootH() = (selfPreview.parent as? View)?.height?.takeIf { it > 0 }
        ?: resources.displayMetrics.heightPixels

    private fun collapsedRect(): IntArray {
        val w = dp(260); val h = dp(146)
        return intArrayOf(rootW() - dp(16) - w, dp(44), w, h)
    }

    private fun expandedRect(): IntArray {
        val m = dp(20)
        val w = rootW() - 2 * m; val h = w * 9 / 16
        return intArrayOf(m, (rootH() - h) / 2, w, h)
    }

    private fun applySelfRect(x: Int, y: Int, w: Int, h: Int) {
        selfPreview.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.START)
            .apply { leftMargin = x; topMargin = y }
    }

    /** Expand/collapse the self-view. While expanded, a full-screen tap-catcher
     *  sits under it so a tap ANYWHERE returns it to the corner. */
    private fun setSelfExpanded(expanded: Boolean) {
        selfExpanded = expanded
        if (expanded) showSelfScrim() else hideSelfScrim()
        animateSelf(if (expanded) expandedRect() else collapsedRect())
    }

    private fun showSelfScrim() {
        if (selfScrim != null) return
        val parent = selfPreview.parent as? FrameLayout ?: return
        val s = View(this).apply {
            isClickable = true
            elevation = dpf(8f) // above everything except the self-view (10dp)
            setOnClickListener { setSelfExpanded(false) }
        }
        parent.addView(s, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        selfScrim = s
    }

    private fun hideSelfScrim() {
        selfScrim?.let { (it.parent as? ViewGroup)?.removeView(it) }
        selfScrim = null
    }

    private fun animateSelf(to: IntArray) {
        selfAnimator?.cancel()
        val fx = selfPreview.left; val fy = selfPreview.top
        val fw = selfPreview.width; val fh = selfPreview.height
        fun lerp(a: Int, b: Int, t: Float) = (a + (b - a) * t).toInt()
        selfAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener {
                val t = it.animatedValue as Float
                applySelfRect(lerp(fx, to[0], t), lerp(fy, to[1], t),
                    lerp(fw, to[2], t), lerp(fh, to[3], t))
            }
            start()
        }
    }

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
