package com.bilal.meetingsremote

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.provider.Settings
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Build
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
import android.window.OnBackInvokedDispatcher
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
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
    private var signInLaunched = false
    private var pendingStartAfterSignIn = false
    private lateinit var homeStatus: TextView
    private lateinit var homeRow: LinearLayout
    private var titleTaps = 0
    private var lastTapAt = 0L
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val meetingStartGuard = MeetingStartGuard()
    private var meetingFlowGeneration = 0L
    private var meetingFlowActive = false
    private var routingToMeeting = false

    private fun backend() = com.bilal.meetingsremote.sdk.RoomBackend(
        prefs.getString("backendUrl", DEFAULT_BACKEND)!!)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("room", Context.MODE_PRIVATE)
        setContentView(buildRoot())
        installBackHandler()
        applyInsets()
        showScreen(homeView)
        updateHomeStatus()
        applyIntentExtras(intent)
        if (routeToActiveMeeting()) return
        requestNeededPermissions()
        firePendingActions()
        handleReturnIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (routeToActiveMeeting()) return
        applyIntentExtras(intent)
        firePendingActions()
        handleReturnIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (routeToActiveMeeting()) return
        updateHomeStatus()
        // Fallback when the /return App Link didn't deep-link back (e.g. domain
        // verification not yet active): if a sign-in is in flight, quietly poll.
        val pending = prefs.getString("pendingSid", null)
        if (signInLaunched && pending != null && prefs.getString("sid", null) == null) {
            signInLaunched = false
            completeSignIn(pending, announce = false)
        }
    }

    override fun onDestroy() {
        cancelMeetingStartTimeout()
        RoomSdk.clearMeetingFlowListener(this)
        super.onDestroy()
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

    /** Launcher/OAuth re-entry must never strand a live SDK meeting behind Home. */
    private fun routeToActiveMeeting(): Boolean {
        if (!RoomSdk.isInMeeting()) {
            routingToMeeting = false
            return false
        }
        pendingAutojoin = false; pendingStart = false; pendingSourceTest = false
        if (!routingToMeeting) {
            routingToMeeting = true
            startActivity(Intent(this, MeetingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }
        return true
    }

    private fun installBackHandler() {
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { handleBack() }
        }
    }

    private fun handleBack() {
        // A transition/overlay screen replaces Home; Back should return there,
        // not exit the app.
        if (current === transitionView) {
            cancelMeetingFlow("operator pressed Back")
            showScreen(homeView)
        } else if (current !== homeView) showScreen(homeView) else finish()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    @android.annotation.SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        handleBack()
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
            if (Build.VERSION.SDK_INT >= 30) {
                val bar = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(baseL + bar.left, baseT + bar.top,
                    baseR + bar.right, baseB + bar.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(baseL + insets.systemWindowInsetLeft,
                    baseT + insets.systemWindowInsetTop,
                    baseR + insets.systemWindowInsetRight,
                    baseB + insets.systemWindowInsetBottom)
            }
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
            setPadding(0, 0, 0, dp(8))
        })
        homeStatus = TextView(this).apply {
            setTextColor(MUTED); textSize = 14f; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(26))
        }
        v.addView(homeStatus)
        val row = LinearLayout(this)
        homeRow = row
        row.addView(bigCard(R.drawable.ic_add, "Start Meeting", ORANGE) { startMeeting() })
        row.addView(bigCard(R.drawable.ic_join, "Join", BLUE) { showJoin() })
        layoutHomeRow(resources.configuration.orientation)
        v.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return v
    }

    /** Lay the two action cards side-by-side in landscape, stacked in portrait.
     *  API 36 ignores the manifest orientation lock on sw600dp+ tablets, so the
     *  home screen must stay usable either way (B9). */
    private fun layoutHomeRow(orientation: Int) {
        if (!::homeRow.isInitialized) return
        val portrait = orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        homeRow.orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        for (i in 0 until homeRow.childCount) {
            val lp = if (portrait)
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(140))
                    .apply { setMargins(dp(7), dp(7), dp(7), dp(7)) }
            else LinearLayout.LayoutParams(0, dp(172), 1f)
                    .apply { setMargins(dp(7), 0, dp(7), 0) }
            homeRow.getChildAt(i).layoutParams = lp
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        layoutHomeRow(newConfig.orientation)
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
     * Start (host) a meeting as the signed-in Zoom user. Requires a session:
     * if not signed in, kicks off "Sign in with Zoom" and hosts once it returns.
     * The backend derives the user's ZAK+PMI from their stored refresh token.
     */
    private fun startMeeting(fresh: Boolean = true) {
        if (!canBeginMeeting(allowActiveFlow = !fresh)) return
        // One shot at auto-recovery per user-initiated start (fresh=false is
        // the recovery retry itself — don't rearm, or a still-stuck PMI loops).
        if (fresh) recoverTried = false
        val sid = prefs.getString("sid", null)
        if (sid == null) { launchSignIn(thenStart = true); return }
        val flow = beginMeetingFlow()
        hosting = true
        ensureSdkReady {
            if (flow != meetingFlowGeneration || !meetingFlowActive) return@ensureSdkReady
            transText.text = "Starting meeting…"
            showScreen(transitionView)
            io.execute {
                // Always mint a fresh ZAK immediately before hosting. The old
                // S2S path did this on every start and was consistently fast;
                // the first OAuth implementation reused a cached ZAK for up to
                // an hour, even though Zoom recommends obtaining it just before
                // start and it can be invalidated independently of that TTL.
                val authStarted = SystemClock.elapsedRealtime()
                val host = backend().refresh(sid)
                android.util.Log.i("RoomMeeting", "host auth refresh " +
                    "${SystemClock.elapsedRealtime() - authStarted}ms ok=${host != null}")
                runOnUiThread {
                    if (flow != meetingFlowGeneration || !meetingFlowActive) return@runOnUiThread
                    if (host == null) {
                        meetingFlowActive = false
                        showError("Couldn't refresh Zoom sign-in",
                            "Check the internet connection and try again. If it keeps happening, " +
                                "sign out in Room settings and sign in again.")
                        return@runOnUiThread
                    }
                    if (host.pmi.isBlank()) {
                        meetingFlowActive = false
                        showError("Your Zoom account has no PMI",
                            "Enable a Personal Meeting ID in your Zoom profile, then try again.")
                        return@runOnUiThread
                    }
                    val provider = selectedProvider()
                    if (provider == null) {
                        meetingFlowActive = false
                        showScreen(homeView)
                        return@runOnUiThread
                    }
                    RoomSdk.setVideoSource(provider)
                    registerMeetingListener()
                    val attempt = armMeetingStartTimeout(hosting = true)
                    val sdkStarted = SystemClock.elapsedRealtime()
                    val err = RoomSdk.start(this, host.zak, host.pmi, host.name)
                    android.util.Log.i("RoomMeeting", "SDK start returned $err in " +
                        "${SystemClock.elapsedRealtime() - sdkStarted}ms")
                    if (err != 0) {
                        cancelMeetingStartTimeout()
                        meetingFlowActive = false
                        showError("Couldn't start the meeting", "Error $err.")
                    } else {
                        android.util.Log.i("RoomMeeting", "start accepted attempt=$attempt")
                    }
                }
            }
        }
    }

    // ===================================================== Sign in with Zoom

    /** Fresh opaque session id: 256 bits, URL-safe (server requires len ≥ 16). */
    private fun newSid(): String {
        val b = ByteArray(32)
        java.security.SecureRandom().nextBytes(b)
        return android.util.Base64.encodeToString(
            b, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or
               android.util.Base64.NO_PADDING)
    }

    /** Open Zoom's login in a Custom Tab. On return the /return App Link (or the
     *  onResume poll) finalizes the session. [thenStart] hosts once signed in. */
    private fun launchSignIn(thenStart: Boolean) {
        val sid = newSid()
        prefs.edit().putString("pendingSid", sid).apply()
        pendingStartAfterSignIn = thenStart
        signInLaunched = true
        val uri = Uri.parse(backend().oauthStartUrl(sid))
        try {
            CustomTabsIntent.Builder().build().launchUrl(this, uri)
        } catch (e: Exception) {   // no Custom Tabs/browser provider
            try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            catch (e2: Exception) { toast("No browser available to sign in") }
        }
    }

    /** Confirm the returned [sid] resolves to a live session, then persist it. */
    private fun completeSignIn(sid: String, announce: Boolean) {
        if (announce) { transText.text = "Finishing sign-in…"; showScreen(transitionView) }
        io.execute {
            val host = backend().session(sid)
            runOnUiThread {
                if (host == null) {
                    if (announce) showError("Sign-in didn't finish",
                        "Tap Start Meeting to try again.")
                    return@runOnUiThread
                }
                prefs.edit().putString("sid", sid).remove("pendingSid").apply()
                signInLaunched = false
                updateHomeStatus()
                toast("Signed in as ${host.name}")
                if (pendingStartAfterSignIn) { pendingStartAfterSignIn = false; startMeeting() }
                else showScreen(homeView)
            }
        }
    }

    /** Handle the /return App Link that bounces the browser back into the app. */
    private fun handleReturnIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        if (data.path != "/return") return
        val sid = data.getQueryParameter("sid") ?: return
        // Accept only the sid we ourselves initiated (defends against an
        // injected link binding the device to someone else's session).
        if (sid != prefs.getString("pendingSid", null)) return
        completeSignIn(sid, announce = true)
    }

    /** Revoke the Zoom token + drop the server session, and forget the sid. */
    private fun signOutAndForget() {
        val sid = prefs.getString("sid", null) ?: prefs.getString("pendingSid", null)
        prefs.edit().remove("sid").remove("pendingSid").apply()
        signInLaunched = false
        updateHomeStatus()
        if (sid != null) io.execute { backend().signOut(sid) }
        toast("Signed out")
    }

    private fun updateHomeStatus() {
        if (!::homeStatus.isInitialized) return
        homeStatus.text = if (prefs.getString("sid", null) != null)
            "Signed in — Start Meeting hosts your Zoom" else "Not signed in"
    }

    private fun roomName() = prefs.getString("displayName", null)?.ifBlank { null } ?: "Meeting Room"

    private fun showError(title: String, sub: String) {
        overlayTitle.text = title; overlaySub.text = sub; showScreen(overlayView)
    }

    private fun registerMeetingListener() {
        RoomSdk.setMeetingFlowListener(this)
    }

    private fun beginMeetingFlow(): Long {
        meetingFlowGeneration += 1
        meetingFlowActive = true
        return meetingFlowGeneration
    }

    private fun canBeginMeeting(allowActiveFlow: Boolean = false): Boolean {
        if (RoomSdk.isInMeeting()) {
            routeToActiveMeeting()
            return false
        }
        // Disable/debounce synchronously, before OAuth or any backend request.
        if (meetingFlowActive && !allowActiveFlow) return false
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showMicrophoneRequired()
            return false
        }
        return true
    }

    private fun showMicrophoneRequired() {
        AlertDialog.Builder(this)
            .setTitle("Microphone permission required")
            .setMessage("Meetings Remote cannot send room audio without microphone access.")
            .setPositiveButton("Allow") { _, _ ->
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            }
            .setNeutralButton("Open settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun cancelMeetingFlow(reason: String) {
        android.util.Log.i("RoomMeeting", "cancel flow: $reason")
        meetingFlowGeneration += 1
        meetingFlowActive = false
        cancelMeetingStartTimeout()
        RoomSdk.cancelPendingMeeting()
    }

    private fun armMeetingStartTimeout(hosting: Boolean): Long {
        val flow = meetingFlowGeneration
        val attempt = meetingStartGuard.begin(SystemClock.elapsedRealtime())
        mainHandler.postDelayed({
            if (!meetingStartGuard.expired(attempt, SystemClock.elapsedRealtime())) return@postDelayed
            val status = RoomSdk.meetingStatus()
            android.util.Log.e("RoomMeeting",
                "connection timeout attempt=$attempt hosting=$hosting status=$status")
            meetingStartGuard.complete()
            meetingFlowActive = false
            RoomSdk.cancelPendingMeeting()
            if (!hosting) {
                showError("Zoom didn't respond",
                    "The join attempt was stopped after 35 seconds. Check the connection and try again.")
                return@postDelayed
            }

            // A start can strand the user's PMI even when the SDK never emits
            // FAILED. Clean it through the authenticated REST endpoint before
            // returning control; this is the timeout counterpart of error-100
            // recovery below.
            transText.text = "Cleaning up the start attempt…"
            io.execute {
                val sid = prefs.getString("sid", null)
                val cleaned = sid != null && backend().endStuckMeeting(sid)
                android.util.Log.i("RoomMeeting", "timeout cleanup -> $cleaned")
                runOnUiThread {
                    if (flow != meetingFlowGeneration) return@runOnUiThread
                    showError("Zoom didn't respond",
                        "The start attempt was stopped safely after 35 seconds. " +
                            "Press Back, check the connection, then try again.")
                }
            }
        }, MeetingStartGuard.DEFAULT_TIMEOUT_MS)
        return attempt
    }

    private fun cancelMeetingStartTimeout() {
        meetingStartGuard.complete()
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
                    meetingFlowActive = false
                    showError("Can't reach the room server",
                        "Check the server address in settings (hold the title).")
                    return@runOnUiThread
                }
                RoomSdk.initialize(this, jwt) { code, internal ->
                    if (code == 0) onReady()
                    else {
                        meetingFlowActive = false
                        showError("Zoom sign-in failed", "Error $code/$internal.")
                    }
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
        val views = mutableListOf<View>(server, name)
        // "Sign out & delete my data" — revokes the Zoom token and drops the
        // server-side session (Play account-deletion requirement, B4).
        if (prefs.getString("sid", null) != null) {
            views += TextView(this).apply {
                text = "Sign out & delete my data"
                setTextColor(ORANGE); textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(18), 0, dp(2))
                setOnClickListener { confirmSignOut() }
            }
        }
        views += TextView(this).apply {
            text = "Open-source licenses"
            setTextColor(BLUE); textSize = 14f
            setPadding(0, dp(18), 0, dp(2))
            setOnClickListener { showLicenses() }
        }
        AlertDialog.Builder(this)
            .setTitle("Room settings")
            .setMessage("Set once when installing the room. The server holds the Zoom " +
                "SDK credentials so no one signs in just to join a meeting.")
            .setView(dialogWrap(*views.toTypedArray()))
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("backendUrl", server.text.toString().trim())
                    .putString("displayName", name.text.toString().trim()).apply()
            }
            .setNeutralButton("Camera…") { _, _ -> showCamera() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Open-source attribution (Play/Marketplace requirement, B13). */
    private fun showLicenses() {
        val text = """
            Meetings Remote uses these open-source components:

            • FFmpeg — RTSP/camera decode (LGPL-2.1-or-later). Source: ffmpeg.org
            • ONNX Runtime — AV-sync inference (MIT). microsoft/onnxruntime
            • SyncNet models — lip-sync AV offset (MIT). joonson/syncnet
            • MediaPipe / BlazeFace — face detection (Apache-2.0). google/mediapipe
            • AndroidX Browser — sign-in Custom Tabs (Apache-2.0)
            • DoubleTake — AirPlay-compatible sender (LGPL-3.0-or-later),
              modified to remove FairPlay. Source and license:
              github.com/omarroth/doubletake (v0.4.0) and this app's public
              tools/airplay_sender build directory.

            The Zoom Meeting SDK is proprietary to Zoom Video Communications and is
            not open source. "Zoom" is a trademark of Zoom Video Communications;
            this app is not affiliated with or endorsed by Zoom.
        """.trimIndent()
        val body = TextView(this).apply {
            this.text = text; setTextColor(MUTED); textSize = 13f
            setLineSpacing(dpf(3f), 1f)
        }
        AlertDialog.Builder(this)
            .setTitle("Open-source licenses")
            .setView(dialogWrap(body))
            .setPositiveButton("Close", null)
            .show()
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(this)
            .setTitle("Sign out & delete my data")
            .setMessage("This revokes this app's access to your Zoom account and deletes " +
                "your session from our server. You'll sign in again to host.")
            .setPositiveButton("Sign out") { _, _ -> signOutAndForget() }
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
        val wanted = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= 31) wanted += Manifest.permission.BLUETOOTH_CONNECT
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
        if (!canBeginMeeting()) return
        // Joining needs no sign-in — just the SDK JWT from the backend + an ID.
        if (prefs.getString("meetingNo", "").isNullOrBlank()) {
            showJoin()
            return
        }
        val flow = beginMeetingFlow()
        ensureSdkReady {
            if (flow == meetingFlowGeneration && meetingFlowActive) registerSourceAndJoin(flow)
        }
    }

    private fun registerSourceAndJoin(flow: Long) {
        if (flow != meetingFlowGeneration || !meetingFlowActive) return
        hosting = false
        val provider = selectedProvider()
        if (provider == null) {
            meetingFlowActive = false
            showScreen(homeView)
            return
        }
        RoomSdk.setVideoSource(provider)
        registerMeetingListener()
        transText.text = "Joining meeting…"
        showScreen(transitionView)
        val attempt = armMeetingStartTimeout(hosting = false)
        val err = RoomSdk.join(
            this,
            prefs.getString("meetingNo", "") ?: "",
            prefs.getString("passcode", "") ?: "",
            prefs.getString("displayName", null)?.ifBlank { null } ?: "Meeting Room",
        )
        if (err != 0) {
            cancelMeetingStartTimeout()
            meetingFlowActive = false
            overlayTitle.text = "Couldn't join"
            overlaySub.text = "Join error $err. Check the meeting ID."
            showScreen(overlayView)
        } else {
            android.util.Log.i("RoomMeeting", "join accepted attempt=$attempt")
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
            if (!RoomSdk.ownsMeetingFlowListener(this)) {
                android.util.Log.w("RoomMeeting", "ignored callback for stale MainActivity")
                return@runOnUiThread
            }
            if (!meetingFlowActive) {
                // A late callback can arrive after the operator cancels. Never
                // reopen the spinner/UI; if Zoom connected despite cancellation,
                // leave immediately so the room cannot host invisibly.
                if (status == MeetingStatus.MEETING_STATUS_INMEETING) {
                    android.util.Log.w("RoomMeeting", "late INMEETING after cancellation; leaving")
                    RoomSdk.leave()
                }
                return@runOnUiThread
            }
            when (status) {
                MeetingStatus.MEETING_STATUS_CONNECTING -> {
                    transText.text = if (hosting) "Starting meeting…" else "Joining meeting…"
                    showScreen(transitionView)
                }
                MeetingStatus.MEETING_STATUS_WAITINGFORHOST -> {
                    cancelMeetingStartTimeout()
                    transText.text = "Waiting for the host…"
                    showScreen(transitionView)
                }
                MeetingStatus.MEETING_STATUS_IN_WAITING_ROOM -> {
                    cancelMeetingStartTimeout()
                    transText.text = "In the waiting room…"
                    showScreen(transitionView)
                }
                MeetingStatus.MEETING_STATUS_INMEETING -> {
                    cancelMeetingStartTimeout()
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
                    cancelMeetingStartTimeout()
                    // Error 100 while hosting = our PMI is stranded "in
                    // progress" (crashed meeting, §17). The backend can
                    // force-end it via the REST API — recover and retry once
                    // instead of telling the user to wait ~10 min.
                    if (hosting && errorCode == 100 && !recoverTried) {
                        recoverTried = true
                        transText.text = "Recovering the room's meeting…"
                        showScreen(transitionView)
                        io.execute {
                            val sid = prefs.getString("sid", null)
                            val ok = sid != null && backend().endStuckMeeting(sid)
                            android.util.Log.i("RoomMeeting", "end-stuck-meeting -> $ok")
                            Thread.sleep(2000) // let Zoom reconcile the end
                            runOnUiThread { startMeeting(fresh = false) }
                        }
                        return@runOnUiThread
                    }
                    overlayTitle.text = if (hosting) "Couldn't start the meeting" else "Couldn't join"
                    overlaySub.text = meetingErrorText(errorCode)
                    meetingFlowActive = false
                    showScreen(overlayView)
                }
                MeetingStatus.MEETING_STATUS_ENDED, MeetingStatus.MEETING_STATUS_IDLE -> {
                    cancelMeetingStartTimeout()
                    meetingFlowActive = false
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
        // Production token backend (https). Operators can override in Room
        // settings (e.g. a LAN dev box) via long-press on the title.
        private const val DEFAULT_BACKEND = "https://api.meetingsremote.app"
    }
}
