package com.bilal.zoomremote

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Zoom Room-style controller — a touch console for the Zoom client on the PC.
 *
 * Design goals this file is built around:
 *  - Feels instant. Taps update the UI optimistically and reconcile with the
 *    PC's real state on the next poll. Networking runs on a pool so an action
 *    (e.g. Leave, which the server confirms before replying) never blocks the
 *    status polls.
 *  - No frozen screens. Starting/Joining/Leaving show a brief transition state
 *    immediately, then cross-fade to the resolved screen once the PC agrees —
 *    never a stale meeting screen, never a premature home screen.
 *  - Polling speeds up (~450ms) while a transition is pending, relaxes (~1.4s)
 *    when idle.
 */
class MainActivity : Activity() {

    // ---- palette ----
    private val BG = 0xFF0A0C10.toInt()
    private val TILE = 0xFF1C212A.toInt()      // idle control circle
    private val TEXT = 0xFFF4F6F8.toInt()
    private val MUTED = 0xFF8B929C.toInt()
    private val BLUE = 0xFF2D8CFF.toInt()       // active / share / join
    private val ORANGE = 0xFFFF7A29.toInt()     // new meeting
    private val GREEN = 0xFF2FB86B.toInt()
    private val RED = 0xFFF0453A.toInt()        // muted / leave / attention
    private val AMBER = 0xFFF4A93B.toInt()

    private val net = Executors.newCachedThreadPool()
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }
    private var host = ""

    private lateinit var contentCol: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var homeView: View
    private lateinit var meetingView: View
    private lateinit var overlayView: LinearLayout
    private lateinit var transitionView: LinearLayout
    private var current: View? = null

    private lateinit var overlayIcon: ImageView
    private lateinit var overlayTitle: TextView
    private lateinit var overlaySub: TextView
    private lateinit var transText: TextView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var accountLabel: TextView
    private lateinit var topicLabel: TextView
    private lateinit var timerLabel: TextView

    private val ctls = HashMap<String, Ctl>()

    private var lastStatus: JSONObject? = null
    private var inMeeting = false
    private var meetingStart = 0L

    // transition machine
    private var pending: String? = null     // "starting" | "joining" | "leaving"
    private var pendingUntil = 0L

    // optimistic control state: key -> value, key -> expiry
    private val optV = HashMap<String, Boolean>()
    private val optT = HashMap<String, Long>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density
    private fun now() = SystemClock.elapsedRealtime()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = prefs.getString("host", "192.168.1.50:8765")!!
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = BG
        setContentView(buildRoot())
        applyInsets()
    }

    /** targetSdk 35 draws edge-to-edge on Android 15+, so pad the content by the
     *  system bar / cutout insets — otherwise the header hides under the status
     *  bar and control labels slide under the navigation bar in landscape. */
    private fun applyInsets() {
        val baseL = dp(22); val baseT = dp(12); val baseR = dp(22); val baseB = dp(12)
        contentCol.setOnApplyWindowInsetsListener { v, insets ->
            var l = 0; var t = 0; var r = 0; var b = 0
            if (Build.VERSION.SDK_INT >= 30) {
                val bar = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                l = bar.left; t = bar.top; r = bar.right; b = bar.bottom
            } else {
                @Suppress("DEPRECATION")
                run {
                    l = insets.systemWindowInsetLeft; t = insets.systemWindowInsetTop
                    r = insets.systemWindowInsetRight; b = insets.systemWindowInsetBottom
                }
            }
            v.setPadding(baseL + l, baseT + t, baseR + r, baseB + b)
            insets
        }
        contentCol.requestApplyInsets()
    }

    // ============================================================= UI build

    private fun buildRoot(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(BG) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(12))
        }
        contentCol = col
        col.addView(buildHeader())
        content = FrameLayout(this)
        col.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        homeView = buildHome()
        meetingView = buildMeeting()
        overlayView = buildOverlay()
        transitionView = buildTransition()
        for (v in listOf(homeView, meetingView, overlayView, transitionView)) {
            v.visibility = View.GONE
            content.addView(v)
        }
        root.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }

    private fun buildHeader(): View {
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Long-press the title to change the PC address (rarely needed; the
        // offline screen also offers it exactly when it matters).
        h.addView(TextView(this).apply {
            text = "Zoom Room"; setTextColor(TEXT); textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setOnLongClickListener { showSettings(); true }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        statusDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(MUTED) }
        }
        h.addView(statusDot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(8) })
        statusText = TextView(this).apply { text = "…"; setTextColor(MUTED); textSize = 14f }
        h.addView(statusText)
        return h
    }

    private fun buildHome(): View {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        }
        v.addView(TextView(this).apply {
            text = "Ready to meet"; setTextColor(TEXT); textSize = 32f
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        accountLabel = TextView(this).apply {
            setTextColor(MUTED); textSize = 15f; gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(34))
        }
        v.addView(accountLabel)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(bigCard("new", R.drawable.ic_add, "New Meeting", ORANGE),
            LinearLayout.LayoutParams(0, dp(172), 1f).apply { setMargins(dp(7), 0, dp(7), 0) })
        row.addView(bigCard("join", R.drawable.ic_join, "Join", BLUE),
            LinearLayout.LayoutParams(0, dp(172), 1f).apply { setMargins(dp(7), 0, dp(7), 0) })
        v.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return v
    }

    private fun bigCard(key: String, iconRes: Int, text: String, fill: Int): View {
        // Icon + label as a single horizontally-centered group so the card
        // reads like a real button (a vertical stack leaves the label
        // left-aligned because a vertical LinearLayout defaults children to
        // full width).
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dpf(24f); setColor(fill) }
            foreground = ripple(dpf(24f))
            isClickable = true
            setOnClickListener { onTile(key) }
        }
        val circle = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFFFFF) }
        }
        circle.addView(ImageView(this).apply {
            setImageResource(iconRes); imageTintList = ColorStateList.valueOf(Color.WHITE)
        }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        root.addView(circle, LinearLayout.LayoutParams(dp(54), dp(54)).apply { rightMargin = dp(16) })
        root.addView(TextView(this).apply {
            this.text = text; setTextColor(Color.WHITE); textSize = 20f
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        addPress(root)
        return root
    }

    private fun buildMeeting(): View {
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // top bar with Leave pill on the right (Zoom iOS places Leave top-right)
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(View(this), LinearLayout.LayoutParams(0, dp(1), 1f))
        val leave = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dpf(22f); setColor(RED) }
            foreground = ripple(dpf(22f))
            isClickable = true
            setPadding(dp(20), dp(11), dp(22), dp(11))
            setOnClickListener { confirmLeave() }
        }
        leave.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_leave); imageTintList = ColorStateList.valueOf(Color.WHITE)
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(8) })
        leave.addView(TextView(this).apply {
            text = "Leave"; setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
        })
        addPress(leave)
        top.addView(leave)
        v.addView(top)

        // centered hero: topic + timer + subtitle + rec indicator
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        }
        topicLabel = TextView(this).apply {
            text = "In meeting"; setTextColor(TEXT); textSize = 24f
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        hero.addView(topicLabel)
        timerLabel = TextView(this).apply {
            text = "00:00"; setTextColor(MUTED); textSize = 16f
            gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0)
        }
        hero.addView(timerLabel)
        hero.addView(TextView(this).apply {
            text = "Audio and video are on the room display"
            setTextColor(0xFF5D636D.toInt()); textSize = 13f
            gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0)
        })
        v.addView(hero, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // bottom control row (Zoom iOS-style icon-over-label, circular)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        }
        for (c in listOf(
            buildCtl("mute", R.drawable.ic_mic, "Mute"),
            buildCtl("video", R.drawable.ic_video, "Video"),
            buildCtl("participants", R.drawable.ic_participants, "Participants"),
            buildCtl("hand", R.drawable.ic_hand, "Raise Hand"))) {
            row.addView(c.root, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        v.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(18)
        })
        return v
    }

    private fun buildOverlay(): LinearLayout {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { showSettings() }  // fix the PC address when it can't connect
        }
        overlayIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_settings); imageTintList = ColorStateList.valueOf(MUTED)
        }
        v.addView(overlayIcon, LinearLayout.LayoutParams(dp(46), dp(46)))
        overlayTitle = TextView(this).apply {
            text = "Connecting…"; setTextColor(TEXT); textSize = 21f
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(6))
        }
        v.addView(overlayTitle)
        overlaySub = TextView(this).apply {
            setTextColor(MUTED); textSize = 15f; gravity = Gravity.CENTER
            setLineSpacing(dpf(4f), 1f)
        }
        v.addView(overlaySub)
        return v
    }

    private fun buildTransition(): LinearLayout {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        }
        v.addView(ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(BLUE)
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        transText = TextView(this).apply {
            text = "…"; setTextColor(TEXT); textSize = 19f
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        }
        v.addView(transText)
        return v
    }

    // control button: circle with icon + label under it
    inner class Ctl(val key: String) {
        lateinit var root: LinearLayout
        lateinit var circle: FrameLayout
        lateinit var icon: ImageView
        lateinit var label: TextView
        fun set(iconRes: Int, text: String, circleColor: Int,
                iconColor: Int = Color.WHITE, labelColor: Int = MUTED, enabled: Boolean = true) {
            (circle.background as GradientDrawable).setColor(circleColor)
            icon.setImageResource(iconRes)
            icon.imageTintList = ColorStateList.valueOf(iconColor)
            label.text = text; label.setTextColor(labelColor)
            root.isEnabled = enabled
            root.alpha = if (enabled) 1f else 0.35f
        }
    }

    private fun buildCtl(key: String, iconRes: Int, text: String): Ctl {
        val c = Ctl(key)
        c.root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { onTile(key) }
        }
        c.circle = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(TILE) }
            foreground = ripple(dpf(40f))
        }
        c.icon = ImageView(this).apply {
            setImageResource(iconRes); imageTintList = ColorStateList.valueOf(Color.WHITE)
        }
        c.circle.addView(c.icon, FrameLayout.LayoutParams(dp(27), dp(27), Gravity.CENTER))
        c.root.addView(c.circle, LinearLayout.LayoutParams(dp(66), dp(66)))
        c.label = TextView(this).apply {
            this.text = text; setTextColor(MUTED); textSize = 13f
            gravity = Gravity.CENTER; setPadding(dp(2), dp(9), dp(2), 0)
            maxLines = 1
        }
        c.root.addView(c.label)
        addPress(c.root)
        ctls[key] = c
        return c
    }

    private fun ripple(radius: Float): RippleDrawable {
        val mask = GradientDrawable().apply { cornerRadius = radius; setColor(Color.WHITE) }
        return RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), null, mask)
    }

    private fun addPress(v: View) {
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
            }
            false
        }
    }

    // ============================================================= actions

    private fun onTile(key: String) {
        content.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        when (key) {
            "new" -> { beginTransition("starting", "Starting meeting…", 8000); fire("new") }
            "join" -> showJoin()
            "participants" -> fire("participants")
            "mute" -> {
                if (ov("audio_joined", sb("audio_joined")) != true) {
                    setOpt("audio_joined", true); setOpt("muted", true)
                } else setOpt("muted", !(ov("muted", sb("muted")) ?: false))
                renderMeeting(); fire("mute")
            }
            "video" -> { setOpt("video_on", !(ov("video_on", sb("video_on")) ?: true)); renderMeeting(); fire("video") }
            "hand" -> {
                val cur = ov("hand_raised", sb("hand_raised"))
                if (cur != null) { setOpt("hand_raised", !cur); renderMeeting() }
                fire("hand")
            }
        }
    }

    private fun confirmLeave() {
        content.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        AlertDialog.Builder(this)
            .setTitle("Leave meeting?")
            .setPositiveButton("Leave") { _, _ ->
                beginTransition("leaving", "Leaving meeting…", 8000); fire("leave")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun beginTransition(tag: String, msg: String, timeoutMs: Long) {
        pending = tag; pendingUntil = now() + timeoutMs
        transText.text = msg
        showScreen(transitionView)
        ui.removeCallbacks(poller); ui.post(poller)  // switch to fast polling now
    }

    private fun fire(path: String) {
        net.execute {
            val res = http(path)
            ui.post {
                if (res == null) statusText.text = "Offline"
                else if (!res.optBoolean("ok", false) && res.has("error"))
                    Toast.makeText(this, friendly(res.optString("error")), Toast.LENGTH_SHORT).show()
            }
            pollOnce()
        }
    }

    private fun showJoin() {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0)
        }
        val id = EditText(this).apply { hint = "Meeting ID"; inputType = InputType.TYPE_CLASS_NUMBER }
        val pwd = EditText(this).apply { hint = "Passcode (optional)" }
        wrap.addView(id); wrap.addView(pwd)
        AlertDialog.Builder(this)
            .setTitle("Join a meeting")
            .setView(wrap)
            .setPositiveButton("Join") { _, _ ->
                val mid = id.text.toString().filter { it.isDigit() }
                if (mid.isNotEmpty()) {
                    beginTransition("joining", "Joining meeting…", 12000)
                    fire("join?id=$mid&pwd=${pwd.text}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettings() {
        val input = EditText(this).apply {
            setText(host); hint = "host:port"; inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("Room PC address")
            .setMessage("IP:port of the Mac running the controller server.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                host = input.text.toString().trim()
                prefs.edit().putString("host", host).apply()
                pollOnce()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ============================================================= optimism

    private fun sb(k: String): Boolean? {
        val s = lastStatus ?: return null
        return if (s.isNull(k)) null else s.optBoolean(k)
    }

    private fun setOpt(k: String, v: Boolean) { optV[k] = v; optT[k] = now() + 2500 }

    private fun ov(k: String, server: Boolean?): Boolean? {
        val t = optT[k]
        if (t != null && now() <= t) return optV[k]
        if (t != null) { optV.remove(k); optT.remove(k) }
        return server
    }

    private fun reconcile(s: JSONObject) {
        for (k in listOf("audio_joined", "muted", "video_on", "hand_raised")) {
            val e = optV[k] ?: continue
            val sv = if (s.isNull(k)) null else s.optBoolean(k)
            if (sv == e) { optV.remove(k); optT.remove(k) }
        }
    }

    // ============================================================= polling

    private val poller = object : Runnable {
        override fun run() {
            pollOnce()
            ui.postDelayed(this, if (pending != null) 450 else 1400)
        }
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (inMeeting) {
                val s = (now() - meetingStart) / 1000
                timerLabel.text = String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
            }
            ui.postDelayed(this, 1000)
        }
    }

    override fun onResume() { super.onResume(); ui.post(poller); ui.post(ticker) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(poller); ui.removeCallbacks(ticker) }

    private fun pollOnce() {
        net.execute {
            val s = statusReq()
            ui.post { render(s) }
        }
    }

    // ============================================================= render

    private fun showScreen(v: View) {
        if (current === v) return
        val prev = current
        current = v
        v.alpha = 0f; v.visibility = View.VISIBLE
        v.animate().alpha(1f).setDuration(180).start()
        prev?.animate()?.alpha(0f)?.setDuration(140)?.withEndAction { prev.visibility = View.GONE }?.start()
    }

    private fun setDot(color: Int, text: String) {
        (statusDot.background as GradientDrawable).setColor(color)
        statusText.text = text
    }

    private fun render(s: JSONObject?) {
        lastStatus = s
        if (pending != null && now() > pendingUntil) pending = null

        if (s == null) {
            pending = null; inMeeting = false
            setDot(RED, "Offline")
            overlayIcon.setImageResource(R.drawable.ic_settings)
            overlayTitle.text = "Can't reach the room PC"
            overlaySub.text = "$host — check it's on the same Wi-Fi and the server is running.\n" +
                "Tap here to change the address."
            showScreen(overlayView); return
        }
        if (!s.optBoolean("zoom_running")) {
            pending = null; inMeeting = false
            setDot(AMBER, "Zoom closed")
            overlayIcon.setImageResource(R.drawable.ic_join)
            overlayTitle.text = "Zoom isn't open on the PC"
            overlaySub.text = "Open Zoom on the room computer to begin."
            showScreen(overlayView); return
        }
        if (!s.optBoolean("accessibility", true)) {
            pending = null; inMeeting = false
            setDot(AMBER, "Needs permission")
            overlayIcon.setImageResource(R.drawable.ic_settings)
            overlayTitle.text = "Grant Accessibility on the PC"
            overlaySub.text = "System Settings → Privacy & Security → Accessibility → enable " +
                "the terminal running the server."
            showScreen(overlayView); return
        }

        reconcile(s)
        val im = s.optBoolean("in_meeting")

        // resolve pending transitions
        when (pending) {
            "leaving" -> if (im) { setDot(GREEN, "In meeting"); transText.text = "Leaving meeting…"
                                   showScreen(transitionView); return } else pending = null
            "starting", "joining" -> if (!im) { setDot(AMBER, "Connecting")
                                                showScreen(transitionView); return } else pending = null
        }

        if (im) {
            if (!inMeeting) { inMeeting = true; meetingStart = now() }
            setDot(GREEN, "In meeting")
            renderMeeting()
            showScreen(meetingView)
        } else {
            inMeeting = false
            setDot(GREEN, "Ready")
            accountLabel.text = "Connected to $host"
            showScreen(homeView)
        }
    }

    private fun renderMeeting() {
        val s = lastStatus ?: return
        val topic = s.optString("topic", "")
        topicLabel.text = if (topic.isNotEmpty() && topic != "null") topic else "In meeting"

        val aj = ov("audio_joined", sb("audio_joined"))
        val muted = ov("muted", sb("muted"))
        when {
            aj != true -> ctls["mute"]!!.set(R.drawable.ic_mic_off, "Join Audio", TILE)
            muted == true -> ctls["mute"]!!.set(R.drawable.ic_mic_off, "Unmute", RED, Color.WHITE, TEXT)
            else -> ctls["mute"]!!.set(R.drawable.ic_mic, "Mute", TILE)
        }
        if (ov("video_on", sb("video_on")) == true)
            ctls["video"]!!.set(R.drawable.ic_video, "Stop Video", TILE)
        else
            ctls["video"]!!.set(R.drawable.ic_video_off, "Start Video", RED, Color.WHITE, TEXT)

        ctls["participants"]!!.set(R.drawable.ic_participants, "Participants", TILE)

        when (ov("hand_raised", sb("hand_raised"))) {
            null -> ctls["hand"]!!.set(R.drawable.ic_hand, "Raise Hand", TILE, MUTED, MUTED, false)
            true -> ctls["hand"]!!.set(R.drawable.ic_hand, "Lower Hand", BLUE, Color.WHITE, TEXT)
            else -> ctls["hand"]!!.set(R.drawable.ic_hand, "Raise Hand", TILE)
        }
    }

    private fun friendly(err: String) = when (err) {
        "zoom_not_running" -> "Zoom isn't open on the PC"
        "accessibility_permission_needed" -> "Grant Accessibility on the PC"
        "not_in_meeting" -> "Not in a meeting"
        "control_unavailable", "audio_unavailable" -> "That control isn't available right now"
        "leave_not_confirmed" -> "Couldn't confirm leave — try again"
        "missing_meeting_id" -> "Enter a valid meeting ID"
        else -> "Error: $err"
    }

    // ============================================================= network

    private fun http(path: String): JSONObject? {
        val conn = URL("http://$host/api/$path").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"; conn.connectTimeout = 2500; conn.readTimeout = 15000
            JSONObject(conn.inputStream.bufferedReader().readText())
        } catch (e: Exception) { null } finally { conn.disconnect() }
    }

    private fun statusReq(): JSONObject? {
        val conn = URL("http://$host/api/status").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 1800; conn.readTimeout = 4000
            JSONObject(conn.inputStream.bufferedReader().readText())
        } catch (e: Exception) { null } finally { conn.disconnect() }
    }
}
