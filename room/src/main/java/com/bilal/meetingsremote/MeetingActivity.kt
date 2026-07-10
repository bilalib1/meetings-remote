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
    private lateinit var emptyText: TextView
    private lateinit var offlinePill: TextView
    private lateinit var selfPreview: SelfPreviewView
    private lateinit var muteCtl: Ctl
    private lateinit var videoCtl: Ctl
    private lateinit var participantsCtl: Ctl
    private lateinit var castCtl: Ctl
    private lateinit var participantsCount: TextView
    private var cast: CastController? = null
    private var castDialog: android.app.AlertDialog? = null
    private var castList: LinearLayout? = null
    private var activeShown = false
    private var selfExpanded = false
    private var selfAnimator: android.animation.ValueAnimator? = null
    private var selfScrim: View? = null
    private var participantsDialog: android.app.AlertDialog? = null
    private var participantsList: LinearLayout? = null
    private var controlBar: LinearLayout? = null
    private val participantsPoll = object : Runnable {
        override fun run() {
            if (participantsDialog?.isShowing != true) return
            fillParticipants()
            participantsCtl.root.postDelayed(this, 1500)
        }
    }

    // Surfaces the RTSP camera dropping mid-meeting (§12B): the recovery logic
    // lives in RoomSdk; this only shows/hides the indicator.
    private val offlinePoll = object : Runnable {
        override fun run() {
            offlinePill.visibility = if (RoomSdk.cameraOffline()) View.VISIBLE else View.GONE
            offlinePill.postDelayed(this, 1000)
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

    // The SDK prefers whoever it deems the active video user; track it so the
    // full-screen unit follows the speaker in multi-party meetings.
    @Volatile private var preferredVideoUser = -1L

    // Every roster/media event that should refresh the far-end video and the
    // control bar (counts, mute states, host labels). The rest of the ~90
    // callbacks — chat, recording, webinar, whiteboard, captions, free-meeting
    // nags, file transfer, AI companion — have no surface in this UI and are
    // deliberate no-ops.
    private val refreshEvents = setOf(
        "onMeetingUserJoin", "onMeetingUserLeave", "onMeetingUserUpdated",
        "onUserVideoStatusChanged", "onUserAudioStatusChanged",
        "onUserAudioTypeChanged", "onMyAudioSourceTypeChanged",
        "onUserNamesChanged", "onMeetingHostChanged", "onMeetingCoHostChange",
        "onSpotlightVideoChanged", "onSilentModeChanged",
        "onHostVideoOrderUpdated", "onFollowHostVideoOrderChanged",
    )

    // Re-attach the far-end video when participants join/leave or toggle their
    // camera — fixed-delay retries at join time miss anyone who arrives (or
    // starts video) later. InMeetingServiceListener has ~90 void methods and
    // no adapter class in SDK 7.0.5, so a reflective proxy routes the events
    // we care about.
    private val inMeetingEvents = java.lang.reflect.Proxy.newProxyInstance(
        us.zoom.sdk.InMeetingServiceListener::class.java.classLoader,
        arrayOf(us.zoom.sdk.InMeetingServiceListener::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            // Object methods also route through the handler; the SDK keeps
            // listeners in a Vector, whose indexOf() calls equals().
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "MeetingActivity.inMeetingEvents"
            "onActiveVideoUserChanged", "onActiveSpeakerVideoUserChanged",
            "onMeetingActiveVideo" -> {
                preferredVideoUser = (args?.get(0) as? Long) ?: -1L
                runOnUiThread { showActiveVideo(); render() }; null
            }
            // We leaving (or the meeting dying) also lands here — the
            // MeetingServiceListener ENDED/FAILED path doesn't always fire
            // for a leave we didn't initiate.
            "onMeetingLeaveComplete", "onMeetingFail" -> {
                runOnUiThread { finish() }; null
            }
            in refreshEvents -> {
                runOnUiThread { showActiveVideo(); render() }; null
            }
            else -> null
        }
    } as us.zoom.sdk.InMeetingServiceListener

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cast = CastController(this)
        setContentView(buildUi())
        videoView.postDelayed(statsPoll, 3000)
        offlinePill.postDelayed(offlinePoll, 1000)
        RoomSdk.setPreviewSink { buf, w, h -> selfPreview.submit(buf, w, h) }
        RoomSdk.addMeetingListener(this)
        RoomSdk.addInMeetingListener(inMeetingEvents)
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
        offlinePill.removeCallbacks(offlinePoll)
        RoomSdk.removeInMeetingListener(inMeetingEvents)
        RoomSdk.setPreviewSink(null)
        participantsDialog?.dismiss()
        castDialog?.dismiss()
        cast?.stopScan()
        if (AirPlayService.active) AirPlayService.stop(this)
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

        emptyText = TextView(this).apply {
            text = "No one else is here yet"
            setTextColor(MUTED); textSize = 16f; gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(emptyText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        offlinePill = TextView(this).apply {
            text = "Camera offline — reconnecting…"
            setTextColor(Color.WHITE); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { cornerRadius = dpf(18f); setColor(RED) }
            setPadding(dp(16), dp(8), dp(16), dp(8))
            elevation = dpf(6f)
            visibility = View.GONE
        }
        root.addView(offlinePill, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(48) })

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
        val inviteCtl = ctl("invite", R.drawable.ic_invite, "Invite") { showInvite() }
        castCtl = ctl("cast", R.drawable.ic_cast, "Cast") { onCastTapped() }
        val leave = leaveButton()
        for (c in listOf(muteCtl, videoCtl, participantsCtl, inviteCtl, castCtl)) {
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
        controlBar = bar
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
            val remote = RoomSdk.bestRemoteUserId(preferredVideoUser)
            if (remote == null) {
                // Last participant left: clear the unit or the final frame
                // stays frozen on screen.
                if (activeShown || shownUserId != -1L) {
                    runCatching { mgr.removeAllVideoUnits() }
                }
                activeShown = false; shownUserId = -1L
                emptyText.visibility = View.VISIBLE
                return@post
            }
            emptyText.visibility = View.GONE
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
        updateCastButton()
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
        panel.addView(MaxHeightScrollView(sheetBodyMax()).apply { addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply {
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

        participantsDialog = sheetDialog(panel, dp(340)) {
            participantsCtl.root.removeCallbacks(participantsPoll)
            participantsList = null; participantsDialog = null
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

    // ------------------------------------------------------------- invite

    /** Invite sheet: the meeting ID up top (big enough to read out or type),
     *  then Email / Copy link, built from the SDK's own invite content. */
    private fun showInvite() {
        val inv = RoomSdk.invite()
        if (inv == null) {
            android.widget.Toast.makeText(this, "Invite unavailable", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dpf(20f); setColor(0xFF12151C.toInt()) }
            setPadding(dp(8), dp(16), dp(8), dp(8))
        }
        panel.addView(TextView(this).apply {
            text = "Invite"; setTextColor(TEXT); textSize = 18f
            typeface = Typeface.DEFAULT_BOLD; setPadding(dp(16), 0, dp(16), dp(12))
        })
        var dialog: android.app.AlertDialog? = null

        // Meeting ID card — the quick-to-type identifier, tap anywhere to copy.
        val idCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dpf(14f); setColor(0xFF1B2029.toInt()) }
            foreground = ripple(dpf(14f)); isClickable = true
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener {
                val cb = getSystemService(android.content.ClipboardManager::class.java)
                cb.setPrimaryClip(android.content.ClipData.newPlainText("Meeting ID", inv.meetingId.replace(" ", "")))
                android.widget.Toast.makeText(this@MeetingActivity, "Meeting ID copied", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        idCard.addView(TextView(this).apply {
            text = "MEETING ID"; setTextColor(MUTED); textSize = 12f
            letterSpacing = 0.08f; typeface = Typeface.DEFAULT_BOLD
        })
        idCard.addView(TextView(this).apply {
            text = inv.meetingId; setTextColor(TEXT); textSize = 26f
            typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(4), 0, 0)
            setTextIsSelectable(true)
        })
        panel.addView(idCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8); rightMargin = dp(8); bottomMargin = dp(6)
        })

        fun row(text: String, onTap: () -> Unit) = TextView(this).apply {
            this.text = text; setTextColor(TEXT); textSize = 16f
            setPadding(dp(16), dp(14), dp(16), dp(14)); isClickable = true
            foreground = ripple(dpf(12f))
            setOnClickListener { dialog?.dismiss(); onTap() }
        }
        panel.addView(row("Email invite") {
            val i = android.content.Intent(android.content.Intent.ACTION_SENDTO,
                android.net.Uri.parse("mailto:")).apply {
                putExtra(android.content.Intent.EXTRA_SUBJECT, inv.subject)
                putExtra(android.content.Intent.EXTRA_TEXT, inv.body)
            }
            runCatching { startActivity(android.content.Intent.createChooser(i, "Send invite")) }
                .onFailure { toastNoApp() }
        })
        panel.addView(row("Copy invite link") {
            val cb = getSystemService(android.content.ClipboardManager::class.java)
            cb.setPrimaryClip(android.content.ClipData.newPlainText("Zoom invite", inv.url))
            android.widget.Toast.makeText(this, "Invite link copied", android.widget.Toast.LENGTH_SHORT).show()
        })
        panel.addView(TextView(this).apply {
            text = "Cancel"; setTextColor(0xFF4C8DFF.toInt()); textSize = 16f
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14)); isClickable = true
            foreground = ripple(dpf(12f))
            setOnClickListener { dialog?.dismiss() }
        })
        dialog = sheetDialog(panel, dp(340))
    }

    private fun toastNoApp() = android.widget.Toast.makeText(
        this, "No app available for that", android.widget.Toast.LENGTH_SHORT).show()

    // --------------------------------------------------------------- cast

    private val CAST_BLUE = 0xFF2C6BE0.toInt()
    private val REQ_AIRPLAY = 7001

    /** Highlight the Cast control while we're mirroring to a TV. */
    private fun updateCastButton() {
        val casting = cast?.connectedRoute() != null || AirPlayService.active
        castCtl.label.text = if (casting) "Casting" else "Cast"
        (castCtl.circle.background as GradientDrawable).setColor(if (casting) CAST_BLUE else TILE)
    }

    /** While casting, the Cast button becomes a stop button (with confirm);
     *  otherwise it opens the picker. */
    private fun onCastTapped() {
        val casting = cast?.connectedRoute() != null || AirPlayService.active
        if (casting) confirmStopCast() else showCast()
    }

    private fun confirmStopCast() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Stop casting?")
            .setPositiveButton("Stop") { _, _ -> stopCasting() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Tear down whichever mirror is live: the AirPlay service or a router route. */
    private fun stopCasting() {
        if (AirPlayService.active) AirPlayService.stop(this)
        if (cast?.connectedRoute() != null) cast?.disconnect()
        updateCastButton()
    }

    /** "Cast to TV" picker: mirror the whole screen to a wireless display, or
     *  jump to the system Cast panel for anything we can't reach directly. */
    private fun showCast() {
        val ctrl = cast ?: return
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dpf(20f); setColor(0xFF12151C.toInt()) }
            setPadding(dp(8), dp(16), dp(8), dp(8))
        }
        panel.addView(TextView(this).apply {
            text = "Cast to TV"; setTextColor(TEXT); textSize = 18f
            typeface = Typeface.DEFAULT_BOLD; setPadding(dp(16), 0, dp(16), dp(2))
        })
        panel.addView(TextView(this).apply {
            text = "Mirror this screen to a nearby TV"; setTextColor(MUTED); textSize = 13f
            setPadding(dp(16), 0, dp(16), dp(10))
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        castList = list
        panel.addView(MaxHeightScrollView(sheetBodyMax()).apply { addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))

        panel.addView(divider())
        // Catch-all: Chromecast/Google TV and OEM casts the framework router
        // doesn't surface live in the system Cast panel.
        panel.addView(actionRow("Other devices (system Cast)…") {
            castDialog?.dismiss()
            runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_CAST_SETTINGS)) }
                .onFailure { toastNoApp() }
        })
        panel.addView(TextView(this).apply {
            text = "Cancel"; setTextColor(0xFF4C8DFF.toInt()); textSize = 16f
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14)); isClickable = true
            foreground = ripple(dpf(12f))
            setOnClickListener { castDialog?.dismiss() }
        })

        castDialog = sheetDialog(panel, dp(340)) {
            ctrl.stopScan(); castList = null; castDialog = null; updateCastButton()
        }
        ctrl.startScan { runOnUiThread { fillCast() } }
        fillCast()
    }

    private fun fillCast() {
        val list = castList ?: return
        val ctrl = cast ?: return
        list.removeAllViews()
        // AirPlay row first — the known room TV (low-latency, in-app, no dongle).
        list.addView(airplayRow(AirPlayService.active))
        val connected = ctrl.connectedRoute()
        val routes = ctrl.routes()
        if (routes.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Searching for other TVs…"; setTextColor(MUTED); textSize = 15f
                setPadding(dp(16), dp(16), dp(16), dp(16))
            })
        } else {
            for (route in routes) list.addView(castRow(route, route == connected))
        }
    }

    /** Row for the AirPlay-2 mirror to the room TV. Tap toggles it on/off. */
    private fun airplayRow(isOn: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14)); isClickable = true
            foreground = ripple(dpf(12f))
            setOnClickListener {
                if (isOn) {
                    AirPlayService.stop(this@MeetingActivity)
                    fillCast(); updateCastButton()
                } else {
                    castDialog?.dismiss(); startAirPlay()
                }
            }
        }
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_cast)
            imageTintList = ColorStateList.valueOf(if (isOn) CAST_BLUE else TEXT)
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(16) })
        val label = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        label.addView(TextView(this).apply {
            text = "AirPlay TV (Roku)"; setTextColor(TEXT); textSize = 16f; maxLines = 1
        })
        label.addView(TextView(this).apply {
            text = if (isOn) "Mirroring — tap to stop" else "Low-latency mirror to the room TV"
            setTextColor(if (isOn) CAST_BLUE else MUTED); textSize = 12f; maxLines = 1
        })
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    /** Ask for screen-capture consent; onActivityResult starts [AirPlayService]. */
    private fun startAirPlay() {
        val mpm = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_AIRPLAY)
    }

    @Deprecated("classic result API; MeetingActivity is a plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_AIRPLAY) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                AirPlayService.start(this, resultCode, data)
            }
            updateCastButton()
        }
    }

    private fun castRow(route: android.media.MediaRouter.RouteInfo, isConnected: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14)); isClickable = true
            foreground = ripple(dpf(12f))
            setOnClickListener {
                if (isConnected) cast?.disconnect() else cast?.connect(route)
                fillCast()
            }
        }
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_cast)
            imageTintList = ColorStateList.valueOf(if (isConnected) CAST_BLUE else TEXT)
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(16) })

        val status = route.status?.toString()?.takeUnless { it.isBlank() }
        val label = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        label.addView(TextView(this).apply {
            text = route.name?.toString() ?: "TV"; setTextColor(TEXT); textSize = 16f; maxLines = 1
        })
        if (isConnected || status != null) label.addView(TextView(this).apply {
            text = if (isConnected) "Connected — tap to stop" else status
            setTextColor(if (isConnected) CAST_BLUE else MUTED); textSize = 12f; maxLines = 1
        })
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun actionRow(text: String, onTap: () -> Unit) = TextView(this).apply {
        this.text = text; setTextColor(TEXT); textSize = 16f
        setPadding(dp(16), dp(14), dp(16), dp(14)); isClickable = true
        foreground = ripple(dpf(12f))
        setOnClickListener { onTap() }
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(0xFF232833.toInt())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(4); bottomMargin = dp(4); leftMargin = dp(16); rightMargin = dp(16)
        }
    }

    // -------------------------------------------------------- sheet framing

    /** A ScrollView that wraps its content but never grows past [maxH], so a
     *  sheet's list can scroll internally instead of pushing the card into the
     *  control bar. */
    private inner class MaxHeightScrollView(private val maxH: Int)
        : android.widget.ScrollView(this) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            super.onMeasure(widthSpec,
                MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
        }
    }

    /** Max height for a sheet's scrollable body. Sized so that even a full card
     *  — while centered on screen — stops short of the control bar: a centered
     *  card of height H clears the bar only when H ≤ screen − 2·(bar + gap), so
     *  the body budget is that minus rough room for the pinned title/footer. */
    private fun sheetBodyMax(): Int {
        val barH = controlBar?.height?.takeIf { it > 0 } ?: dp(140)
        val maxCard = resources.displayMetrics.heightPixels - 2 * (barH + dp(16))
        return (maxCard - dp(150)).coerceAtLeast(dp(160))
    }

    /** Show [card] centered on screen. The card sizes to its content and, once
     *  its body hits [sheetBodyMax], scrolls in place — so it never reaches the
     *  buttons. Uniform framing for every sheet. */
    private fun sheetDialog(card: View, width: Int, onDismiss: (() -> Unit)? = null)
        : android.app.AlertDialog {
        val builder = android.app.AlertDialog.Builder(this).setView(card)
        if (onDismiss != null) builder.setOnDismissListener { onDismiss() }
        return builder.create().apply {
            window?.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            window?.setGravity(Gravity.CENTER)
            show()
            window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
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
            elevation = dpf(8f) // above everything except the self-view (10dp)
            // Collapse on any tap but DON'T consume it (return false), so a tap
            // on a control both shrinks the self-view and activates the button.
            // Collapse is posted: removing the scrim mid-dispatch would mutate
            // the touch-target list while the parent iterates it.
            setOnTouchListener { v, ev ->
                if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    v.post { setSelfExpanded(false) }
                }
                false
            }
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
