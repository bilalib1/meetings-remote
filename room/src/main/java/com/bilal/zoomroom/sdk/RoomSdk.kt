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
            return "OK (swapped)"
        }
        val source = ExternalVideoSource(provider)
        source.previewSink = previewSink
        val err = ZoomSDK.getInstance().videoSourceHelper.setExternalVideoSource(source)
        videoSource = source
        Log.i(TAG, "setExternalVideoSource -> ${err.name}")
        return err.name
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
        meetingService()?.leaveCurrentMeeting(false)
    }

    private fun inMeeting() = ZoomSDK.getInstance().inMeetingService
    private fun audio() = inMeeting()?.inMeetingAudioController
    private fun video() = inMeeting()?.inMeetingVideoController

    /** Start sending our (external-source) video; returns SDK error name. */
    fun startMyVideo(): String {
        val ctrl = video() ?: return "no controller"
        if (!ctrl.isMyVideoMuted) return "already on"
        return ctrl.muteMyVideo(false).name
    }

    /** Join VoIP audio so the room can hear / be heard without a prompt. */
    fun connectAudio() { runCatching { audio()?.connectAudioWithVoIP() } }

    fun isAudioMuted(): Boolean = audio()?.isMyAudioMuted ?: true
    fun toggleAudio() { audio()?.let { it.muteMyAudio(!it.isMyAudioMuted) } }

    fun isVideoOn(): Boolean = video()?.isMyVideoMuted?.not() ?: false
    fun toggleVideo() { video()?.let { it.muteMyVideo(!it.isMyVideoMuted) } }

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
        // The room hosts its own account, so the host name == our name. A stale
        // connection from a previous room session (or the API host placeholder)
        // shows up again under that same name — collapse those duplicates so the
        // count reflects distinct people, not ghost connections.
        val deduped = sorted.distinctBy { it.name.trim().lowercase() }
        Log.i(TAG, "participants: raw=${ids.size} resolved=${rows.size} deduped=${deduped.size} me=$me")
        return deduped
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

    fun removeMeetingListener(listener: MeetingServiceListener) {
        meetingService()?.removeListener(listener)
    }
}
