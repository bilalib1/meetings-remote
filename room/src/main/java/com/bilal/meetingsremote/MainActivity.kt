package com.bilal.meetingsremote

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.bilal.meetingsremote.sdk.RoomSdk
import com.bilal.meetingsremote.source.Negotiated
import com.bilal.meetingsremote.source.FfmpegVideoSource
import com.bilal.meetingsremote.source.TestPatternSource
import com.bilal.meetingsremote.source.VideoSourceProvider
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import us.zoom.sdk.MeetingParameter
import us.zoom.sdk.MeetingServiceListener
import us.zoom.sdk.MeetingStatus

/**
 * Meetings Remote console — same design language as the v1 remote (app/), but this
 * app IS the Zoom client: it joins meetings via the Meeting SDK on this
 * tablet, feeding video from an external camera (RTSP) or a test pattern.
 *
 * The home screen is just "Ready to meet" + Start Meeting / Join. All
 * plumbing is hidden: tap the title 5 times for camera (RTSP) setup,
 * long-press it for SDK credentials. The camera source "just works" —
 * whatever was configured is used silently on join.
 *
 * Scriptable via adb intent extras: clientId, clientSecret, displayName,
 * meetingNo, passcode, rtspUrl, source(test|rtsp), jwt, autojoin, testSource.
 */
class MainActivity : Activity(), MeetingServiceListener {

    // ---- palette (matches app/ console) ----
    private val BG = 0xFF0A0C10.toInt()
    private val TEXT = 0xFFF4F6F8.toInt()
    private val MUTED = 0xFF8B929C.toInt()
    private val BLUE = 0xFF2D8CFF.toInt()
    private val ORANGE = 0xFFFF7A29.toInt()

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var contentCol: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var homeView: View
    private lateinit var transitionView: LinearLayout
    private lateinit var overlayView: LinearLayout
    private lateinit var transText: TextView
    private lateinit var overlayTitle: TextView
    private lateinit var overlaySub: TextView
    private var current: View? = null
    private var pendingAutojoin = false
    private var pendingSourceTest = false
    private var testSecs = 5
    private var pendingStart = false
    private var meetingShown = false
    private var hosting = false
    private var recoverTried = false
    private var titleTaps = 0
    private var lastTapAt = 0L
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun backend() = com.bilal.meetingsremote.sdk.RoomBackend(
        prefs.getString("backendUrl", DEFAULT_BACKEND)!!)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("room", Context.MODE_PRIVATE)
        setContentView(buildRoot())
        applyInsets()
        showScreen(homeView)
        applyIntentExtras(intent)
        requestNeededPermissions()
        firePendingActions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntentExtras(intent)
        firePendingActions()
    }

    /**
     * Run one-shot actions requested via adb extras, then clear them from the
     * intent so relaunching from the launcher/recents doesn't replay them
     * (which showed a spurious "Joining meeting…" on open).
     */
    private fun firePendingActions() {
        intent?.apply {
            removeExtra("autojoin"); removeExtra("startMeeting"); removeExtra("testSource")
        }
        if (pendingAutojoin) { pendingAutojoin = false; joinFlow() }
        if (pendingStart) { pendingStart = false; startMeeting() }
        if (pendingSourceTest) { pendingSourceTest = false; testSource() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // A transition/overlay screen replaces Home; Back should return there,
        // not exit the app.
        if (current !== homeView) showScreen(homeView) else super.onBackPressed()
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
        transitionView = buildTransition()
        overlayView = buildOverlay()
        for (v in listOf(homeView, transitionView, overlayView)) {
            v.visibility = View.GONE
            content.addView(v)
        }
        root.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }

    private fun applyInsets() {
        val baseL = contentCol.paddingLeft; val baseT = contentCol.paddingTop
        val baseR = contentCol.paddingRight; val baseB = contentCol.paddingBottom
        contentCol.setOnApplyWindowInsetsListener { v, insets ->
            val bar = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(baseL + bar.left, baseT + bar.top, baseR + bar.right, baseB + bar.bottom)
            insets
        }
        contentCol.requestApplyInsets()
    }

    private fun buildHeader(): View {
        // Title only — no status/debug text. Tap 5x for camera setup,
        // long-press for SDK credentials.
        return TextView(this).apply {
            text = "Meetings Remote"; setTextColor(TEXT); textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            // Bigger, forgiving tap target for the hidden setup gesture.
            setPadding(dp(4), dp(8), dp(40), dp(12))
            setOnClickListener {
                val now = SystemClock.elapsedRealtime()
                titleTaps = if (now - lastTapAt < 2500) titleTaps + 1 else 1
                lastTapAt = now
                if (titleTaps >= 5) { titleTaps = 0; showCamera() }
            }
            setOnLongClickListener { showSettings(); true }
        }
    }

    private fun buildHome(): View {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        }
        v.addView(TextView(this).apply {
            text = "Ready to meet"; setTextColor(TEXT); textSize = 32f
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(34))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(bigCard(R.drawable.ic_add, "Start Meeting", ORANGE) { startMeeting() },
            LinearLayout.LayoutParams(0, dp(172), 1f).apply { setMargins(dp(7), 0, dp(7), 0) })
        row.addView(bigCard(R.drawable.ic_join, "Join", BLUE) { showJoin() },
            LinearLayout.LayoutParams(0, dp(172), 1f).apply { setMargins(dp(7), 0, dp(7), 0) })
        v.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return v
    }

    private fun bigCard(iconRes: Int, text: String, fill: Int, onTap: () -> Unit): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dpf(24f); setColor(fill) }
            foreground = ripple(dpf(24f))
            isClickable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onTap()
            }
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

    private fun buildOverlay(): LinearLayout {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { showSettings() }
        }
        v.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_settings); imageTintList = ColorStateList.valueOf(MUTED)
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        overlayTitle = TextView(this).apply {
            text = "Needs attention"; setTextColor(TEXT); textSize = 21f
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

    private fun showScreen(v: View) {
        if (current === v) return
        val prev = current
        current = v
        v.alpha = 0f; v.visibility = View.VISIBLE
        v.animate().alpha(1f).setDuration(180).start()
        prev?.animate()?.alpha(0f)?.setDuration(140)
            ?.withEndAction { prev.visibility = View.GONE }?.start()
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    // ============================================================= dialogs

    private fun styledField(hint: String, value: String?, password: Boolean = false): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(value ?: "")
            inputType = if (password)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT
        }

    private fun dialogWrap(vararg views: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            views.forEach { addView(it) }
        }

    private fun showJoin() {
        val id = EditText(this).apply {
            hint = "Meeting ID"; inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getString("meetingNo", ""))
        }
        val pwd = styledField("Passcode (optional)", prefs.getString("passcode", ""))
        AlertDialog.Builder(this)
            .setTitle("Join a meeting")
            .setView(dialogWrap(id, pwd))
            .setPositiveButton("Join") { _, _ ->
                val mid = id.text.toString().filter { it.isDigit() }
                if (mid.isNotEmpty()) {
                    prefs.edit().putString("meetingNo", mid)
                        .putString("passcode", pwd.text.toString()).apply()
                    joinFlow()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Start (host) a meeting. The backend mints the host ZAK for the room's
     * Zoom account (Server-to-Server OAuth) — no login screen, no redirect.
     */
    private fun startMeeting(fresh: Boolean = true) {
        // One shot at auto-recovery per user-initiated start (fresh=false is
        // the recovery retry itself — don't rearm, or a still-stuck PMI loops).
        if (fresh) recoverTried = false
        hosting = true
        ensureSdkReady {
            transText.text = "Starting meeting…"
            showScreen(transitionView)
            io.execute {
                val host = backend().hostZak()
                runOnUiThread {
                    if (host == null || host.pmi.isBlank()) {
                        showError("Hosting isn't set up",
                            "The room server can't reach the host account (check setup).")
                        return@runOnUiThread
                    }
                    val provider = selectedProvider() ?: return@runOnUiThread
                    RoomSdk.setVideoSource(provider)
                    RoomSdk.addMeetingListener(this)
                    val err = RoomSdk.start(this, host.zak, host.pmi, host.name)
                    if (err != 0) showError("Couldn't start the meeting", "Error $err.")
                }
            }
        }
    }

    private fun roomName() = prefs.getString("displayName", null)?.ifBlank { null } ?: "Meeting Room"

    private fun showError(title: String, sub: String) {
        overlayTitle.text = title; overlaySub.text = sub; showScreen(overlayView)
    }

    /** Init the Meeting SDK using a JWT fetched from the backend, then continue. */
    private fun ensureSdkReady(onReady: () -> Unit) {
        if (RoomSdk.isInitialized) { onReady(); return }
        transText.text = "Connecting to Zoom…"
        showScreen(transitionView)
        io.execute {
            val jwt = backend().sdkJwt()
            runOnUiThread {
                if (jwt == null) {
                    showError("Can't reach the room server",
                        "Check the server address in settings (hold the title).")
                    return@runOnUiThread
                }
                RoomSdk.initialize(this, jwt) { code, internal ->
                    if (code == 0) onReady()
                    else showError("Zoom sign-in failed", "Error $code/$internal.")
                }
            }
        }
    }


    /** Hidden (5 taps on the title): camera source setup. */
    private fun showCamera() {
        val group = RadioGroup(this)
        val test = RadioButton(this).apply { text = "Test pattern" }
        val rtsp = RadioButton(this).apply { text = "RTSP camera" }
        group.addView(test); group.addView(rtsp)
        val url = styledField("rtsp://user:pass@host:554/stream", prefs.getString("rtspUrl", ""))
        if (prefs.getString("source", "test") == "rtsp") rtsp.isChecked = true else test.isChecked = true
        fun save() {
            prefs.edit().putString("source", if (rtsp.isChecked) "rtsp" else "test")
                .putString("rtspUrl", url.text.toString().trim()).apply()
        }
        AlertDialog.Builder(this)
            .setTitle("Camera source")
            .setView(dialogWrap(group, url))
            .setPositiveButton("Save") { _, _ -> save() }
            .setNeutralButton("Test 5 s") { _, _ -> save(); testSource() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Hidden (long-press the title): room settings — installer/operator only. */
    private fun showSettings() {
        val server = styledField("Room server address", prefs.getString("backendUrl", DEFAULT_BACKEND))
        val name = styledField("Room name (shown to others)", prefs.getString("displayName", "Meeting Room"))
        AlertDialog.Builder(this)
            .setTitle("Room settings")
            .setMessage("Set once when installing the room. The server holds the Zoom " +
                "credentials so no one signs in just to join a meeting.")
            .setView(dialogWrap(server, name))
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("backendUrl", server.text.toString().trim())
                    .putString("displayName", name.text.toString().trim()).apply()
            }
            .setNeutralButton("Camera…") { _, _ -> showCamera() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ============================================================= settings

    private fun applyIntentExtras(intent: Intent?) {
        val e = intent?.extras ?: return
        val edit = prefs.edit()
        for (k in listOf("backendUrl", "displayName", "meetingNo",
                "passcode", "rtspUrl", "source", "hostMeetingNo")) {
            e.getString(k)?.let { edit.putString(k, it) }
        }
        edit.apply()
        pendingAutojoin = e.getBoolean("autojoin", false) || e.getString("autojoin") == "true"
        pendingSourceTest = e.getBoolean("testSource", false) || e.getString("testSource") == "true"
        testSecs = e.getString("testSecs")?.toIntOrNull() ?: e.getInt("testSecs", 5)
        pendingStart = e.getBoolean("startMeeting", false) || e.getString("startMeeting") == "true"
    }

    private fun requestNeededPermissions() {
        val wanted = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        val missing = wanted.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    // ============================================================= flows

    private fun selectedProvider(): VideoSourceProvider? {
        return if (prefs.getString("source", "test") == "rtsp") {
            val url = prefs.getString("rtspUrl", "")?.trim() ?: ""
            if (url.isEmpty()) {
                toast("No RTSP URL configured")
                return null
            }
            FfmpegVideoSource(url)
        } else {
            TestPatternSource()
        }
    }

    private fun joinFlow() {
        // Joining needs no sign-in — just the SDK JWT from the backend + an ID.
        if (prefs.getString("meetingNo", "").isNullOrBlank()) {
            showJoin()
            return
        }
        ensureSdkReady { registerSourceAndJoin() }
    }

    private fun registerSourceAndJoin() {
        hosting = false
        val provider = selectedProvider() ?: return
        RoomSdk.setVideoSource(provider)
        RoomSdk.addMeetingListener(this)
        transText.text = "Joining meeting…"
        showScreen(transitionView)
        val err = RoomSdk.join(
            this,
            prefs.getString("meetingNo", "") ?: "",
            prefs.getString("passcode", "") ?: "",
            prefs.getString("displayName", null)?.ifBlank { null } ?: "Meeting Room",
        )
        if (err != 0) {
            overlayTitle.text = "Couldn't join"
            overlaySub.text = "Join error $err. Check the meeting ID."
            showScreen(overlayView)
        }
    }

    /** Runs the selected provider without the SDK: proves capture->I420 on-device. */
    private fun testSource() {
        val provider = selectedProvider() ?: return
        toast("Testing camera…")
        val frames = AtomicInteger()
        val lastSize = AtomicLong()
        provider.start(Negotiated(1280, 720, 30)) { _, w, h ->
            frames.incrementAndGet()
            lastSize.set(w.toLong() shl 32 or h.toLong())
        }
        contentCol.postDelayed({
            provider.stop()
            val w = (lastSize.get() ushr 32).toInt()
            val h = lastSize.get().toInt()
            val extra = (provider as? FfmpegVideoSource)?.let { " (${it.status})" } ?: ""
            val msg = "Camera test: ${frames.get()} frames @ ${w}x$h$extra"
            android.util.Log.i("RoomMeeting", msg)
            toast(msg)
        }, testSecs * 1000L)
    }

    // ============================================================= meeting

    override fun onMeetingStatusChanged(status: MeetingStatus?, errorCode: Int, internalErrorCode: Int) {
        android.util.Log.i("RoomMeeting", "status=$status err=$errorCode/$internalErrorCode")
        runOnUiThread {
            when (status) {
                MeetingStatus.MEETING_STATUS_CONNECTING -> {
                    transText.text = if (hosting) "Starting meeting…" else "Joining meeting…"
                    showScreen(transitionView)
                }
                MeetingStatus.MEETING_STATUS_WAITINGFORHOST -> {
                    transText.text = "Waiting for the host…"
                    showScreen(transitionView)
                }
                MeetingStatus.MEETING_STATUS_IN_WAITING_ROOM -> {
                    transText.text = "In the waiting room…"
                    showScreen(transitionView)
                }
                MeetingStatus.MEETING_STATUS_INMEETING -> {
                    // Watchdog for swipe-away: ends the meeting from
                    // onTaskRemoved so we never orphan a live PMI (§17).
                    startService(Intent(this, MeetingWatchService::class.java))
                    showScreen(homeView)
                    // External source only pumps while our video is on; start it.
                    contentCol.postDelayed({
                        val r = RoomSdk.startMyVideo()
                        android.util.Log.i("RoomMeeting", "startMyVideo -> $r")
                    }, 1500)
                    // Hand off to our custom in-meeting screen (SDK UI is off).
                    if (!meetingShown) {
                        meetingShown = true
                        startActivity(Intent(this, MeetingActivity::class.java))
                    }
                }
                MeetingStatus.MEETING_STATUS_FAILED -> {
                    // Error 100 while hosting = our PMI is stranded "in
                    // progress" (crashed meeting, §17). The backend can
                    // force-end it via the REST API — recover and retry once
                    // instead of telling the user to wait ~10 min.
                    if (hosting && errorCode == 100 && !recoverTried) {
                        recoverTried = true
                        transText.text = "Recovering the room's meeting…"
                        showScreen(transitionView)
                        io.execute {
                            val ok = backend().endStuckMeeting()
                            android.util.Log.i("RoomMeeting", "end-stuck-meeting -> $ok")
                            Thread.sleep(2000) // let Zoom reconcile the end
                            runOnUiThread { startMeeting(fresh = false) }
                        }
                        return@runOnUiThread
                    }
                    overlayTitle.text = if (hosting) "Couldn't start the meeting" else "Couldn't join"
                    overlaySub.text = meetingErrorText(errorCode)
                    showScreen(overlayView)
                }
                MeetingStatus.MEETING_STATUS_ENDED, MeetingStatus.MEETING_STATUS_IDLE -> {
                    stopService(Intent(this, MeetingWatchService::class.java))
                    meetingShown = false
                    showScreen(homeView)
                }
                else -> {}
            }
        }
    }

    private fun meetingErrorText(code: Int): String = when (code) {
        9 -> "That meeting hasn't started yet."
        8 -> "That meeting is over."
        4 -> "Wrong passcode."
        // Basic accounts can't start their PMI while the previous instance is
        // still winding down on Zoom's side (§17). Only shown when the
        // automatic force-end + retry didn't clear it — time heals this one.
        100 -> "The room's last meeting is still ending on Zoom's side. " +
            "Wait a minute or two and try again."
        else -> "Meeting error $code."
    }

    override fun onMeetingParameterNotification(param: MeetingParameter?) {}

    companion object {
        // Dev default: the token backend on the Mac LAN. Ship builds bake in
        // the real (https) server; operators can override in Room settings.
        private const val DEFAULT_BACKEND = "http://192.168.1.50:8790"
    }
}
