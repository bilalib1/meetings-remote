package com.bilal.zoomroom.sdk

import android.content.Context
import android.util.Log
import com.bilal.zoomroom.source.VideoSourceProvider
import us.zoom.sdk.JoinMeetingOptions
import us.zoom.sdk.JoinMeetingParams
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
        Log.d(TAG, "init jwt=${params.jwtToken}") // dev build only; remove before ship
        ZoomSDK.getInstance().initialize(
            context,
            object : ZoomSDKInitializeListener {
                override fun onZoomSDKInitializeResult(errorCode: Int, internalErrorCode: Int) {
                    Log.i(TAG, "init result: $errorCode / $internalErrorCode")
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
        return meetingService()?.joinMeetingWithParams(context, params, JoinMeetingOptions())
            ?: -1
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
