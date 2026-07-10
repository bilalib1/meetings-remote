package com.bilal.meetingsremote

import android.content.Context
import android.media.MediaRouter
import android.media.MediaRouter.RouteInfo

/**
 * Wireless screen mirroring to a TV via the framework [MediaRouter]. Selecting a
 * live-video route connects a Wi-Fi Display (Miracast) and Android mirrors the
 * whole screen to it — brand-agnostic: any Miracast-capable TV or HDMI adapter,
 * not tied to one vendor.
 *
 * The framework router does NOT surface Google Cast (Chromecast/Google TV) —
 * those need Play Services' route provider — so the picker also offers a jump to
 * the system Cast panel, which aggregates everything the OS can reach.
 */
class CastController(context: Context) {

    private val router =
        context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter

    fun interface Listener { fun onRoutesChanged() }
    private var listener: Listener? = null

    private val callback = object : MediaRouter.SimpleCallback() {
        override fun onRouteAdded(r: MediaRouter, route: RouteInfo) = changed()
        override fun onRouteRemoved(r: MediaRouter, route: RouteInfo) = changed()
        override fun onRouteChanged(r: MediaRouter, route: RouteInfo) = changed()
        override fun onRouteSelected(r: MediaRouter, type: Int, route: RouteInfo) = changed()
        override fun onRouteUnselected(r: MediaRouter, type: Int, route: RouteInfo) = changed()
    }

    private fun changed() { listener?.onRoutesChanged() }

    /** Begin actively scanning for TVs; call while the picker is on screen. */
    fun startScan(listener: Listener) {
        this.listener = listener
        router.addCallback(
            MediaRouter.ROUTE_TYPE_LIVE_VIDEO,
            callback,
            MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN,
        )
    }

    fun stopScan() {
        listener = null
        runCatching { router.removeCallback(callback) }
    }

    /** TVs we can mirror to: live-video routes other than the built-in screen. */
    fun routes(): List<RouteInfo> =
        (0 until router.routeCount)
            .map { router.getRouteAt(it) }
            .filter { it != router.defaultRoute && it.isEnabled }
            .filter { it.supportedTypes and MediaRouter.ROUTE_TYPE_LIVE_VIDEO != 0 }

    /** The TV we're currently mirroring to, or null if on the built-in screen. */
    fun connectedRoute(): RouteInfo? =
        router.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_VIDEO)
            .takeIf { it != router.defaultRoute }

    /** Mirror the screen to [route]. */
    fun connect(route: RouteInfo) =
        router.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_VIDEO, route)

    /** Stop casting: fall back to the tablet's own screen. */
    fun disconnect() =
        router.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_VIDEO, router.defaultRoute)
}
