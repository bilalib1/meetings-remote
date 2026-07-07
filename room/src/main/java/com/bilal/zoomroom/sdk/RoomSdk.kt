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

    fun initialize(
        context: Context,
        clientId: String,
        clientSecret: String,
        onResult: (errorCode: Int, internalErrorCode: Int) -> Unit,
        presignedJwt: String? = null,
        meetingNo: String = "",
    ) {
        val params = ZoomSDKInitParams().apply {
            jwtToken = presignedJwt ?: JwtSigner.sign(clientId, clientSecret, meetingNo)
            domain = "zoom.us"
            enableLog = true
        }
        ZoomSDK.getInstance().initialize(
            context,
            object : ZoomSDKInitializeListener {
                override fun onZoomSDKInitializeResult(errorCode: Int, internalErrorCode: Int) {
                    Log.i(TAG, "init result: $errorCode / $internalErrorCode")
                    if (errorCode == 0) {
                        // The join preview page grabs the physical camera; skip
                        // it so the meeting uses our external source directly.
                        runCatching {
                            ZoomSDK.getInstance().meetingSettingsHelper
                                ?.disableShowVideoPreviewWhenJoinMeeting(true)
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

    /** Register (or swap) the external camera source. Call after init. */
    fun setVideoSource(provider: VideoSourceProvider): String {
        val existing = videoSource
        if (existing != null) {
            existing.swapProvider(provider)
            return "OK (swapped)"
        }
        val source = ExternalVideoSource(provider)
        val err = ZoomSDK.getInstance().videoSourceHelper.setExternalVideoSource(source)
        videoSource = source
        Log.i(TAG, "setExternalVideoSource -> ${err.name}")
        return err.name
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

    /** Start sending our (external-source) video; returns SDK error name. */
    fun startMyVideo(): String {
        val ctrl = ZoomSDK.getInstance().inMeetingService?.inMeetingVideoController
            ?: return "no controller"
        if (!ctrl.isMyVideoMuted) return "already on"
        return ctrl.muteMyVideo(false).name
    }
}
