"""Platform-agnostic Zoom control contract.

The HTTP server (zoom_control_server.py) and the tablet app know nothing about
how Zoom is driven — they only speak to a ZoomController. Each OS ships one
implementation that drives its native Zoom desktop client:

    macOS   -> MacZoomController      (mac_controller.py, AppleScript UI scripting)
    Windows -> WindowsZoomController  (windows_controller.py, TODO — UI Automation)

To add Windows support you implement ZoomController once and register it in
get_controller(); nothing else in the server or the Android app changes.
"""

from abc import ABC, abstractmethod
import platform


class ZoomController(ABC):
    """Drives the official Zoom desktop client on this machine.

    Every action method returns a JSON-able dict:
        {"ok": True,  "action": "<what happened>"}
        {"ok": False, "error":  "<machine-readable reason>"}
    Known error codes the app already renders nicely: zoom_not_running,
    accessibility_permission_needed, not_in_meeting, control_unavailable,
    audio_unavailable, leave_not_confirmed, missing_meeting_id.

    status() returns the meeting-state snapshot the tablet UI polls:
        {
          "zoom_running": bool,
          "accessibility": bool,   # is the OS permission to control Zoom granted
          "in_meeting":   bool,
          "audio_joined": bool | None,
          "muted":        bool | None,
          "video_on":     bool | None,
          "sharing":      bool | None,
          "hand_raised":  bool | None,   # None => control not applicable (e.g. solo host)
          "recording":    bool | None,
          "topic":        str  | None,
        }
    Any field the platform can't determine must be None (not omitted), so the
    app can grey out or hide the corresponding control.
    """

    @abstractmethod
    def status(self) -> dict: ...

    @abstractmethod
    def new_meeting(self) -> dict: ...

    @abstractmethod
    def join(self, meeting_id: str, passcode: str = "") -> dict: ...

    @abstractmethod
    def toggle_mute(self) -> dict: ...

    @abstractmethod
    def toggle_video(self) -> dict: ...

    @abstractmethod
    def toggle_share(self) -> dict: ...

    @abstractmethod
    def toggle_participants(self) -> dict: ...

    @abstractmethod
    def toggle_record(self) -> dict: ...

    @abstractmethod
    def toggle_hand(self) -> dict: ...

    @abstractmethod
    def leave(self) -> dict: ...


def get_controller() -> ZoomController:
    """Return the ZoomController for the current OS."""
    system = platform.system()
    if system == "Darwin":
        from mac_controller import MacZoomController
        return MacZoomController()
    if system == "Windows":
        # Implement ZoomController in windows_controller.py, then:
        #   from windows_controller import WindowsZoomController
        #   return WindowsZoomController()
        raise NotImplementedError(
            "Windows Zoom control isn't implemented yet. Create "
            "windows_controller.py implementing the ZoomController interface "
            "(mac_controller.py is the reference). Likely tools: pywinauto / "
            "UIAutomation for the client's controls, and Win+key or the "
            "'zoommtg://' URL scheme for launch/join.")
    raise NotImplementedError("Unsupported platform: " + system)
