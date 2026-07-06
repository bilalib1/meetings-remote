package com.bilal.zoomroom

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.bilal.zoomroom.sdk.RoomSdk
import com.bilal.zoomroom.source.RtspVideoSource
import com.bilal.zoomroom.source.TestPatternSource
import us.zoom.sdk.MeetingParameter
import us.zoom.sdk.MeetingServiceListener
import us.zoom.sdk.MeetingStatus

/**
 * Dev console for the room appliance (plan steps 2-4): enter SDK credentials
 * once, pick a camera source (test pattern / RTSP), join a meeting with the
 * SDK default UI. All settings are also settable via adb intent extras
 * (clientId, clientSecret, meetingNo, passcode, rtspUrl, source, autojoin)
 * for scripted testing.
 */
class MainActivity : Activity(), MeetingServiceListener {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var clientId: EditText
    private lateinit var clientSecret: EditText
    private lateinit var displayName: EditText
    private lateinit var meetingNo: EditText
    private lateinit var passcode: EditText
    private lateinit var rtspUrl: EditText
    private lateinit var sourceGroup: RadioGroup
    private lateinit var testRadio: RadioButton
    private lateinit var rtspRadio: RadioButton
    private lateinit var joinButton: Button
    private lateinit var statusView: TextView
    private var pendingAutojoin = false
    private var pendingSourceTest = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("room", Context.MODE_PRIVATE)
        buildUi()
        loadPrefs()
        applyIntentExtras(intent)
        requestNeededPermissions()
        if (pendingAutojoin) joinFlow()
        if (pendingSourceTest) { pendingSourceTest = false; testSource() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyIntentExtras(intent)
        if (pendingAutojoin) joinFlow()
        if (pendingSourceTest) { pendingSourceTest = false; testSource() }
    }

    // ---------- UI ----------

    private fun buildUi() {
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#101418"))
        }

        root.addView(TextView(this).apply {
            text = "Zoom Room — dev console"
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        })

        statusView = TextView(this).apply {
            text = "Status: idle"
            setTextColor(Color.parseColor("#8fd48f"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(8), 0, dp(16))
        }
        root.addView(statusView)

        root.addView(sectionLabel("SDK credentials (Marketplace Meeting SDK app)"))
        clientId = field(root, "Client ID")
        clientSecret = field(root, "Client secret", password = true)

        root.addView(sectionLabel("Camera source"))
        sourceGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        testRadio = RadioButton(this).apply { text = "Test pattern"; setTextColor(Color.WHITE) }
        rtspRadio = RadioButton(this).apply { text = "RTSP camera"; setTextColor(Color.WHITE) }
        sourceGroup.addView(testRadio)
        sourceGroup.addView(rtspRadio)
        root.addView(sourceGroup)
        rtspUrl = field(root, "rtsp://user:pass@host:554/stream")

        root.addView(sectionLabel("Meeting"))
        displayName = field(root, "Display name")
        meetingNo = field(root, "Meeting ID")
        passcode = field(root, "Passcode", password = true)

        joinButton = Button(this).apply {
            text = "Join meeting"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setOnClickListener { joinFlow() }
        }
        root.addView(joinButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        val testButton = Button(this).apply {
            text = "Test camera source (5 s, no meeting)"
            setOnClickListener { testSource() }
        }
        root.addView(testButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#99aabb"))
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun field(parent: LinearLayout, hint: String, password: Boolean = false): EditText {
        val e = EditText(this).apply {
            this.hint = hint
            setHintTextColor(Color.parseColor("#555566"))
            setTextColor(Color.WHITE)
            inputType = if (password)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT
        }
        parent.addView(e, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return e
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun setStatus(msg: String) {
        runOnUiThread { statusView.text = "Status: $msg" }
    }

    // ---------- Settings ----------

    private fun loadPrefs() {
        clientId.setText(prefs.getString("clientId", ""))
        clientSecret.setText(prefs.getString("clientSecret", ""))
        displayName.setText(prefs.getString("displayName", "Zoom Room"))
        meetingNo.setText(prefs.getString("meetingNo", ""))
        passcode.setText(prefs.getString("passcode", ""))
        rtspUrl.setText(prefs.getString("rtspUrl", ""))
        if (prefs.getString("source", "test") == "rtsp") rtspRadio.isChecked = true
        else testRadio.isChecked = true
    }

    private fun savePrefs() {
        prefs.edit()
            .putString("clientId", clientId.text.toString().trim())
            .putString("clientSecret", clientSecret.text.toString().trim())
            .putString("displayName", displayName.text.toString().trim())
            .putString("meetingNo", meetingNo.text.toString().trim())
            .putString("passcode", passcode.text.toString().trim())
            .putString("rtspUrl", rtspUrl.text.toString().trim())
            .putString("source", if (rtspRadio.isChecked) "rtsp" else "test")
            .apply()
    }

    private fun applyIntentExtras(intent: Intent?) {
        val e = intent?.extras ?: return
        e.getString("clientId")?.let { clientId.setText(it) }
        e.getString("clientSecret")?.let { clientSecret.setText(it) }
        e.getString("displayName")?.let { displayName.setText(it) }
        e.getString("meetingNo")?.let { meetingNo.setText(it) }
        e.getString("passcode")?.let { passcode.setText(it) }
        e.getString("rtspUrl")?.let { rtspUrl.setText(it) }
        e.getString("source")?.let {
            if (it == "rtsp") rtspRadio.isChecked = true else testRadio.isChecked = true
        }
        pendingAutojoin = e.getBoolean("autojoin", false) ||
            e.getString("autojoin") == "true"
        pendingSourceTest = e.getBoolean("testSource", false) ||
            e.getString("testSource") == "true"
        savePrefs()
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

    // ---------- Join flow ----------

    private fun joinFlow() {
        pendingAutojoin = false
        savePrefs()
        val id = clientId.text.toString().trim()
        val secret = clientSecret.text.toString().trim()
        if (id.isEmpty() || secret.isEmpty()) {
            setStatus("enter SDK client ID + secret first")
            return
        }
        if (meetingNo.text.toString().trim().isEmpty()) {
            setStatus("enter a meeting ID")
            return
        }
        if (RoomSdk.isInitialized) {
            registerSourceAndJoin()
            return
        }
        setStatus("initializing SDK…")
        RoomSdk.initialize(this, id, secret) { errorCode, internal ->
            if (errorCode == 0) {
                setStatus("SDK initialized")
                registerSourceAndJoin()
            } else {
                setStatus("SDK init failed: error=$errorCode internal=$internal")
            }
        }
    }

    private fun registerSourceAndJoin() {
        val provider = if (rtspRadio.isChecked) {
            val url = rtspUrl.text.toString().trim()
            if (url.isEmpty()) {
                setStatus("enter an RTSP URL or pick Test pattern")
                return
            }
            RtspVideoSource(url)
        } else {
            TestPatternSource()
        }
        val sourceResult = RoomSdk.setVideoSource(provider)
        RoomSdk.addMeetingListener(this)
        val err = RoomSdk.join(
            this,
            meetingNo.text.toString().trim(),
            passcode.text.toString().trim(),
            displayName.text.toString().ifBlank { "Zoom Room" },
        )
        setStatus("source=$sourceResult, join=$err")
    }

    /** Runs the selected provider without the SDK: proves capture->I420 on-device. */
    private fun testSource() {
        savePrefs()
        val provider = if (rtspRadio.isChecked) {
            val url = rtspUrl.text.toString().trim()
            if (url.isEmpty()) {
                setStatus("enter an RTSP URL or pick Test pattern")
                return
            }
            RtspVideoSource(url)
        } else {
            TestPatternSource()
        }
        setStatus("source test running…")
        val frames = java.util.concurrent.atomic.AtomicInteger()
        val lastSize = java.util.concurrent.atomic.AtomicLong()
        provider.start(com.bilal.zoomroom.source.Negotiated(1280, 720, 30)) { _, w, h ->
            frames.incrementAndGet()
            lastSize.set(w.toLong() shl 32 or h.toLong())
        }
        statusView.postDelayed({
            provider.stop()
            val w = (lastSize.get() ushr 32).toInt()
            val h = lastSize.get().toInt()
            val extra = (provider as? RtspVideoSource)?.let { " (${it.status})" } ?: ""
            setStatus("source test: ${frames.get()} frames in 5 s @ ${w}x$h$extra")
        }, 5000)
    }

    // ---------- MeetingServiceListener ----------

    override fun onMeetingStatusChanged(status: MeetingStatus?, errorCode: Int, internalErrorCode: Int) {
        setStatus("meeting: $status (err=$errorCode/$internalErrorCode)")
    }

    override fun onMeetingParameterNotification(param: MeetingParameter?) {}
}
