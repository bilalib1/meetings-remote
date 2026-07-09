package com.bilal.zoomroom.sdk

import android.content.Context
import android.util.Log
import com.bilal.zoomroom.source.VideoSourceProvider
import us.zoom.sdk.JoinMeetingOptions
import us.zoom.sdk.JoinMeetingParams
import us.zoom.sdk.MeetingViewsOptions
import us.zoom.sdk.StartMeetingOptions
import us.zoom.sdk.StartMeetingParamsWithoutLogin
import us.zoom.sdk.MeetingService
import us.zoom.sdk.MeetingServiceListener
import us.zoom.sdk.ZoomSDK
import us.zoom.sdk.ZoomSDKInitParams
import us.zoom.sdk.ZoomSDKInitializeListener

/** Thin wrapper around ZoomSDK init + join so the activity stays UI-only. */
object RoomSdk {

    private const val TAG = "RoomSdk"
    private var videoSource: ExternalVideoSource? = null

    val isInitialized: Boolean get() = ZoomSDK.getInstance().isInitialized

    /** [jwt] is the Meeting SDK JWT from our backend (/sdk-jwt). */
    fun initialize(
        context: Context,
        jwt: String,
        onResult: (errorCode: Int, internalErrorCode: Int) -> Unit,
    ) {
        val params = ZoomSDKInitParams().apply {
            jwtToken = jwt
            domain = "zoom.us"
            enableLog = true
        }
        ZoomSDK.getInstance().initialize(
            context,
            object : ZoomSDKInitializeListener {
                override fun onZoomSDKInitializeResult(errorCode: Int, internalErrorCode: Int) {
                    Log.i(TAG, "init result: $errorCode / $internalErrorCode")
                    if (errorCode == 0) {
                        runCatching {
                            ZoomSDK.getInstance().meetingSettingsHelper?.apply {
                                // Skip the join preview (it grabs the physical
                                // camera; we feed our external source instead).
                                disableShowVideoPreviewWhenJoinMeeting(true)
                                // Our own in-meeting UI (MeetingActivity) — no
                                // SDK toolbar, so no Share/More/etc.
                                isCustomizedMeetingUIEnabled = true
                            }
                        }
                    }
                    onResult(errorCode, internalErrorCode)
                }

                override fun onZoomAuthIdentityExpired() {
                    Log.w(TAG, "auth identity expired")
                }
            },
            params,
        )
    }

    @Volatile private var previewSink: com.bilal.zoomroom.source.FrameSink? = null

    /** Register (or swap) the external camera source. Call after init. */
    fun setVideoSource(provider: VideoSourceProvider): String {
        val existing = videoSource
        if (existing != null) {
            existing.swapProvider(provider)
            // Re-register every time: after onUninitialized (meeting ended or
            // the SDK dropped the source) the SDK forgets it, and a swap alone
            // leaves the next meeting with no external camera at all — it
            // silently falls back to the device camera (found 2026-07-08).
            val err = ZoomSDK.getInstance().videoSourceHelper.setExternalVideoSource(existing)
            Log.i(TAG, "re-setExternalVideoSource -> ${err.name}")
            return err.name
        }
        val source = ExternalVideoSource(provider)
        source.previewSink = previewSink
        // Camera auto-recovery: if the SDK stops the source while the user
        // still wants video (a camera outage, not a Stop-video tap or the
        // meeting ending), keep the provider's reconnect loop running; when
        // frames flow again, re-register and restart video (§12B).
        source.keepAliveOnStop = { userWantsVideo && inActiveMeeting() }
        source.onOrphanFrame = { maybeRecoverVideo() }
        val err = ZoomSDK.getInstance().videoSourceHelper.setExternalVideoSource(source)
        videoSource = source
        Log.i(TAG, "setExternalVideoSource -> ${err.name}")
        return err.name
    }

    // ------------------------------------------------- camera auto-recovery

    // True while the user intends to send video: set by startMyVideo/toggleVideo,
    // NOT by SDK stop events — that difference is what tells an outage apart
    // from an intentional stop.
    @Volatile private var userWantsVideo = true
    @Volatile private var lastRecoverMs = 0L
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    private fun inActiveMeeting(): Boolean =
        meetingService()?.meetingStatus == us.zoom.sdk.MeetingStatus.MEETING_STATUS_INMEETING

    /** Camera frames stopped while the user wants video on — drives the
     *  "camera offline" indicator. */
    fun cameraOffline(): Boolean {
        val src = videoSource ?: return false
        if (!userWantsVideo) return false
        return System.nanoTime() - src.lastFrameNs > 4_000_000_000L
    }

    /** Frames are flowing again but the SDK gave up on the source during the
     *  outage: re-register it and restart video. Debounced; called from the
     *  decode thread, SDK calls hop to the main thread. */
    private fun maybeRecoverVideo() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRecoverMs < 5_000) return
        lastRecoverMs = now
        mainHandler.post {
            val src = videoSource ?: return@post
            if (src.isSending) return@post
            if (!userWantsVideo || !inActiveMeeting()) {
                // Nobody wants these frames anymore (meeting over or video
                // intentionally off) — shut the keep-alive provider down.
                Thread { src.provider.stop() }.start()
                return@post
            }
            Log.i(TAG, "camera recovered — re-registering external source + restarting video")
            runCatching {
                ZoomSDK.getInstance().videoSourceHelper.setExternalVideoSource(src)
                video()?.muteMyVideo(false)
            }.onFailure { Log.w(TAG, "recovery failed: $it") }
        }
    }

    /** Tap the outgoing frames for a local self-preview (null to detach). */
    fun setPreviewSink(sink: com.bilal.zoomroom.source.FrameSink?) {
        previewSink = sink
        videoSource?.previewSink = sink
    }

    fun meetingService(): MeetingService? = ZoomSDK.getInstance().meetingService

    fun addMeetingListener(listener: MeetingServiceListener) {
        meetingService()?.addListener(listener)
    }

    fun addInMeetingListener(listener: us.zoom.sdk.InMeetingServiceListener) {
        ZoomSDK.getInstance().inMeetingService?.addListener(listener)
    }

    fun removeInMeetingListener(listener: us.zoom.sdk.InMeetingServiceListener) {
        ZoomSDK.getInstance().inMeetingService?.removeListener(listener)
    }

    fun join(context: Context, meetingNo: String, passcode: String, name: String): Int {
        val params = JoinMeetingParams().apply {
            this.meetingNo = meetingNo.replace(" ", "")
            this.password = passcode
            this.displayName = name
        }
        // Hide meeting-UI controls we don't support (share/record/invite) and
        // the More menu, so the appliance only exposes what works.
        val options = JoinMeetingOptions().apply {
            no_share = true
            no_record = true
            no_invite = true
            meeting_views_options =
                MeetingViewsOptions.NO_BUTTON_SHARE or MeetingViewsOptions.NO_BUTTON_MORE
        }
        return meetingService()?.joinMeetingWithParams(context, params, options) ?: -1
    }

    /**
     * Start (host) a meeting using a ZAK (Zoom Access Key) for the host user —
     * the SDK's start-without-login path. [meetingNo] empty starts the host's
     * personal/instant meeting. Returns the SDK error code (0 = ok).
     */
    fun start(context: Context, zak: String, meetingNo: String, name: String): Int {
        val params = StartMeetingParamsWithoutLogin().apply {
            userType = MeetingService.USER_TYPE_API_USER
            zoomAccessToken = zak
            displayName = name
            this.meetingNo = meetingNo.replace(" ", "")
        }
        val options = StartMeetingOptions().apply {
            no_share = true
            no_record = true
            no_invite = true
            meeting_views_options =
                MeetingViewsOptions.NO_BUTTON_SHARE or MeetingViewsOptions.NO_BUTTON_MORE
        }
        return meetingService()?.startMeetingWithParams(context, params, options) ?: -1
    }

    fun leave() {
        // When the room hosts (its own PMI), end the meeting for everyone.
        // Leaving without ending strands the PMI "in progress" on Zoom's side,
        // and a Basic account then can't restart it for ~10 min — every Start
        // fails with error 100/80 until the zombie meeting is reaped
        // (root-caused 2026-07-08, §17).
        val endForAll = runCatching { inMeeting()?.isMeetingHost == true }.getOrDefault(false)
        meetingService()?.leaveCurrentMeeting(endForAll)
    }

    private fun inMeeting() = ZoomSDK.getInstance().inMeetingService
    private fun audio() = inMeeting()?.inMeetingAudioController
    private fun video() = inMeeting()?.inMeetingVideoController

    /** Start sending our (external-source) video; returns SDK error name. */
    fun startMyVideo(): String {
        userWantsVideo = true
        val ctrl = video() ?: return "no controller"
        if (!ctrl.isMyVideoMuted) return "already on"
        return ctrl.muteMyVideo(false).name
    }

    /** Join VoIP audio so the room can hear / be heard without a prompt. */
    fun connectAudio() { runCatching { audio()?.connectAudioWithVoIP() } }

    fun isAudioMuted(): Boolean = audio()?.isMyAudioMuted ?: true
    fun toggleAudio() { audio()?.let { it.muteMyAudio(!it.isMyAudioMuted) } }

    fun isVideoOn(): Boolean = video()?.isMyVideoMuted?.not() ?: false
    fun toggleVideo() {
        video()?.let {
            val mute = !it.isMyVideoMuted
            userWantsVideo = !mute
            it.muteMyVideo(mute)
        }
    }

    // ------------------------------------------------------------- invite

    /** Invite content straight from the SDK (same fields the stock Zoom UI
     *  uses): join URL, email subject, full email body, and the meeting ID
     *  formatted for easy typing (e.g. "123 4567 8901"). */
    data class Invite(
        val url: String,
        val subject: String,
        val body: String,
        val meetingId: String,
    )

    fun invite(): Invite? {
        val svc = inMeeting() ?: return null
        val url = runCatching { svc.currentMeetingUrl }.getOrNull().orEmpty()
        if (url.isEmpty()) return null
        val topic = runCatching { svc.currentMeetingTopic }.getOrNull().orEmpty()
        val number = runCatching { svc.currentMeetingNumber }.getOrNull() ?: 0L
        val subject = runCatching { svc.currentMeetingInviteEmailSubject }.getOrNull()
            .takeUnless { it.isNullOrEmpty() }
            ?: "Please join Zoom meeting in progress".let {
                if (topic.isEmpty()) it else "$topic - $it"
            }
        val body = runCatching { svc.currentMeetingInviteEmailContent }.getOrNull()
            .takeUnless { it.isNullOrEmpty() }
            ?: "Join Zoom Meeting\n$url\n\nMeeting ID: $number"
        return Invite(url, subject, body, formatMeetingId(number))
    }

    /** Group a raw meeting number into Zoom's readable "xxx xxxx xxxx" form. */
    private fun formatMeetingId(number: Long): String {
        val d = number.toString()
        return when (d.length) {
            11 -> "${d.substring(0, 3)} ${d.substring(3, 7)} ${d.substring(7)}"
            10 -> "${d.substring(0, 3)} ${d.substring(3, 6)} ${d.substring(6)}"
            else -> d
        }
    }

    fun participantCount(): Int = inMeeting()?.inMeetingUserList?.size ?: 0

    /** One row in the participants panel. */
    data class Participant(
        val name: String,
        val isMe: Boolean,
        val isHost: Boolean,
        val audioMuted: Boolean,
        val videoOn: Boolean,
    )

    /** Everyone in the meeting, host first then self, for the participants panel. */
    fun participants(): List<Participant> {
        val svc = inMeeting() ?: return emptyList()
        val me = svc.myUserID
        val ids = svc.inMeetingUserList ?: return emptyList()
        val rows = ids.mapNotNull { id ->
            val u = runCatching { svc.getUserInfoById(id) }.getOrNull()
            if (u == null) { Log.i(TAG, "  user $id -> null (unresolved)"); return@mapNotNull null }
            val p = Participant(
                name = u.userName ?: "Guest",
                isMe = id == me,
                isHost = runCatching { u.isHost }.getOrDefault(false),
                audioMuted = runCatching { u.audioStatus?.isMuted != false }.getOrDefault(true),
                videoOn = runCatching { u.videoStatus?.isSending == true }.getOrDefault(false),
            )
            Log.i(TAG, "  user $id name='${p.name}' me=${p.isMe} host=${p.isHost}")
            p
        }
        val sorted = rows.sortedWith(
            compareByDescending<Participant> { it.isHost }.thenByDescending { it.isMe })
        // No name-based dedup: a guest can legitimately share the account's
        // display name (it collapsed a real second participant to a count of 1).
        Log.i(TAG, "participants: raw=${ids.size} resolved=${rows.size} me=$me")
        return sorted
    }

    private fun isVideoOn(svc: us.zoom.sdk.InMeetingService, id: Long): Boolean =
        runCatching { svc.getUserInfoById(id)?.videoStatus?.isSending == true }.getOrDefault(false)

    /** First participant that isn't us — the far end to show on the room screen. */
    fun firstRemoteUserId(): Long? {
        val svc = inMeeting() ?: return null
        val me = svc.myUserID
        val users = svc.inMeetingUserList ?: return null
        return users.firstOrNull { it != me && isVideoOn(svc, it) }
            ?: users.firstOrNull { it != me }
    }

    /** [preferred] (e.g. the SDK's active-video user) if it's a live remote
     *  participant, else the first remote. */
    fun bestRemoteUserId(preferred: Long): Long? {
        val svc = inMeeting() ?: return null
        if (preferred > 0 && preferred != svc.myUserID &&
            svc.inMeetingUserList?.contains(preferred) == true) return preferred
        return firstRemoteUserId()
    }

    fun removeMeetingListener(listener: MeetingServiceListener) {
        meetingService()?.removeListener(listener)
    }
}
