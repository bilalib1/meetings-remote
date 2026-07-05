"""macOS implementation of ZoomController.

Drives the official zoom.us client through macOS Accessibility (AppleScript
"System Events" UI scripting). Reads live state from Zoom's always-available
"Meeting" menu, whose item names spell out the action (e.g. "Unmute audio" vs
"Mute audio") and therefore reveal current state.

Requires the terminal launching the server to hold the Accessibility
permission (System Settings > Privacy & Security > Accessibility).
"""

import re
import subprocess
import threading
import time
import urllib.parse

from zoom_controller import ZoomController

ZOOM_APP = "zoom.us"
ZOOM_PROC = "zoom.us"
OSA_TIMEOUT = 12


def _osa(script):
    """Run an AppleScript; return (ok, stdout, stderr)."""
    try:
        p = subprocess.run(["osascript", "-e", script],
                           capture_output=True, text=True, timeout=OSA_TIMEOUT)
        return p.returncode == 0, p.stdout.strip(), p.stderr.strip()
    except subprocess.TimeoutExpired:
        return False, "", "osascript timed out"


def _needs_accessibility(stderr):
    s = stderr.lower()
    return ("assistive access" in s or "-25211" in s or "-1719" in s
            or "-10006" in s)


class MacZoomController(ZoomController):

    # ---------------------------------------------------------- primitives

    def _zoom_running(self):
        return subprocess.run(["pgrep", "-x", ZOOM_PROC],
                              capture_output=True).returncode == 0

    def _meeting_menu_items(self):
        """(items|None, err). err='accessibility' if permission missing; items
        is None when not in a meeting (the Meeting menu only exists in one)."""
        ok, out, err = _osa(
            'tell application "System Events" to tell process "' + ZOOM_PROC +
            '" to get name of every menu item of menu 1 of menu bar item '
            '"Meeting" of menu bar 1')
        if not ok:
            if _needs_accessibility(err):
                return None, "accessibility"
            return None, None
        return [i.strip() for i in out.split(",")], None

    @staticmethod
    def _has(items, *names):
        lut = {i.lower() for i in items}
        return any(n.lower() in lut for n in names)

    @staticmethod
    def _match(items, *names):
        lut = {i.lower(): i for i in items}
        for n in names:
            if n.lower() in lut:
                return lut[n.lower()]
        return None

    def _click_menu_item(self, name):
        esc = name.replace('"', '\\"')
        return _osa('tell application "System Events" to tell process "' + ZOOM_PROC +
                    '" to click menu item "' + esc +
                    '" of menu 1 of menu bar item "Meeting" of menu bar 1')

    def _keystroke(self, key, mods):
        mstr = ", ".join(m + " down" for m in mods)
        ok, _, err = _osa(
            'tell application "' + ZOOM_APP + '" to activate\n'
            "delay 0.25\n"
            'tell application "System Events" to keystroke "' + key + '" using {' + mstr + '}')
        return ok, err

    def _focus_meeting_window(self):
        _osa('tell application "' + ZOOM_APP + '" to activate\n'
             "delay 0.2\n"
             'tell application "System Events" to tell process "' + ZOOM_PROC + '"\n'
             "  try\n"
             '    perform action "AXRaise" of (first window whose title contains "Zoom Meeting")\n'
             "  end try\n"
             "end tell")

    def _guard(self):
        """(items, error_result|None)."""
        if not self._zoom_running():
            return None, {"ok": False, "error": "zoom_not_running"}
        items, err = self._meeting_menu_items()
        if err == "accessibility":
            return None, {"ok": False, "error": "accessibility_permission_needed"}
        if not items:
            return None, {"ok": False, "error": "not_in_meeting"}
        return items, None

    def _toggle_menu(self, candidates):
        items, errres = self._guard()
        if errres:
            return errres
        target = self._match(items, *candidates)
        if not target:
            return {"ok": False, "error": "control_unavailable"}
        ok, _, err = self._click_menu_item(target)
        if ok:
            return {"ok": True, "action": target}
        if _needs_accessibility(err):
            return {"ok": False, "error": "accessibility_permission_needed"}
        return {"ok": False, "error": err or "click_failed"}

    def _meeting_topic(self):
        ok, out, _ = _osa(
            'tell application "System Events" to tell process "' + ZOOM_PROC + '"\n'
            '  set best to ""\n'
            "  repeat with w in windows\n"
            "    set t to title of w\n"
            '    if t contains "Zoom Meeting" and t does not contain "Participant ID" then\n'
            "      set best to t\n"
            "      exit repeat\n"
            "    end if\n"
            "  end repeat\n"
            "  return best\n"
            "end tell")
        return out if ok and out and out != "missing value" else None

    # ------------------------------------------------------- computer audio

    def _join_audio_window_present(self):
        ok, out, _ = _osa('tell application "System Events" to tell process "' +
                          ZOOM_PROC + '" to (exists window "Join audio")')
        return ok and out.strip() == "true"

    def _click_join_with_computer(self, persist=True):
        if persist:
            _osa('tell application "System Events" to tell process "' + ZOOM_PROC + '"\n'
                 "  try\n"
                 '    set cb to (first UI element of UI element 2 of window "Join audio" '
                 'whose description starts with "Automatically")\n'
                 "    if (value of cb) is 0 then click cb\n"
                 "  end try\n"
                 "end tell")
        ok, _, _ = _osa(
            'tell application "System Events" to tell process "' + ZOOM_PROC +
            '" to click (first UI element of UI element 2 of window "Join audio" '
            'whose description starts with "Join with computer")')
        return ok

    def _join_computer_audio(self):
        if not self._join_audio_window_present():
            self._click_menu_item("Join audio")
            time.sleep(0.8)
        if not self._join_audio_window_present():
            return False
        return self._click_join_with_computer()

    def _ensure_computer_audio(self, timeout=10.0):
        """Auto-join computer audio after a meeting starts (and make it stick),
        so the prompt never blocks the user. Runs in a background thread."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            items, err = self._meeting_menu_items()
            if err == "accessibility":
                return
            if items is not None:
                if self._has(items, "Mute audio", "Unmute audio"):
                    return
                if self._join_audio_window_present():
                    self._click_join_with_computer()
                    time.sleep(1.0)
                    continue
                if self._has(items, "Join audio"):
                    self._click_menu_item("Join audio")
                    time.sleep(1.0)
                    continue
            time.sleep(0.5)

    # ------------------------------------------------------------- status

    def status(self):
        st = {"zoom_running": self._zoom_running(), "accessibility": True,
              "in_meeting": False, "audio_joined": None, "muted": None,
              "video_on": None, "sharing": None, "hand_raised": None,
              "recording": None, "topic": None}
        if not st["zoom_running"]:
            return st
        items, err = self._meeting_menu_items()
        if err == "accessibility":
            st["accessibility"] = False
            return st
        if not items:
            return st
        st["in_meeting"] = True
        st["audio_joined"] = not self._has(items, "Join audio")
        if st["audio_joined"]:
            st["muted"] = self._has(items, "Unmute audio")
        st["video_on"] = self._has(items, "Stop video")
        st["sharing"] = self._has(items, "Stop share", "Stop Share")
        if self._has(items, "Lower hand"):
            st["hand_raised"] = True
        elif self._has(items, "Raise hand"):
            st["hand_raised"] = False
        st["recording"] = self._has(items, "Stop recording", "Pause recording")
        st["topic"] = self._meeting_topic()
        return st

    # ------------------------------------------------------------- actions

    def new_meeting(self):
        if not self._zoom_running():
            subprocess.run(["open", "-a", ZOOM_APP], capture_output=True)
            time.sleep(3)
        _osa('tell application "' + ZOOM_APP + '" to activate')
        time.sleep(0.4)
        ok, _, err = _osa('tell application "System Events" to keystroke "v" '
                          'using {command down, control down}')
        if not ok and _needs_accessibility(err):
            return {"ok": False, "error": "accessibility_permission_needed"}
        if ok:
            threading.Thread(target=self._ensure_computer_audio, daemon=True).start()
        return {"ok": ok, "action": "new_meeting"}

    def join(self, meeting_id, passcode=""):
        mid = re.sub(r"\D", "", meeting_id or "")
        if not mid:
            return {"ok": False, "error": "missing_meeting_id"}
        url = "zoommtg://zoom.us/join?action=join&confno=" + mid
        if passcode:
            url += "&pwd=" + urllib.parse.quote(passcode)
        ok = subprocess.run(["open", url], capture_output=True).returncode == 0
        if ok:
            threading.Thread(target=self._ensure_computer_audio, daemon=True).start()
        return {"ok": ok, "action": "join"}

    def toggle_mute(self):
        items, errres = self._guard()
        if errres:
            return errres
        target = self._match(items, "Unmute audio", "Mute audio")
        if not target and self._has(items, "Join audio"):
            if not self._join_computer_audio():
                return {"ok": False, "error": "join_audio_failed"}
            time.sleep(1.0)
            items, _ = self._meeting_menu_items()
            target = self._match(items or [], "Unmute audio", "Mute audio")
            if not target:
                return {"ok": True, "action": "joined_computer_audio"}
        if not target:
            return {"ok": False, "error": "audio_unavailable"}
        ok, _, err = self._click_menu_item(target)
        if ok:
            return {"ok": True, "action": target}
        if _needs_accessibility(err):
            return {"ok": False, "error": "accessibility_permission_needed"}
        return {"ok": False, "error": err or "click_failed"}

    def toggle_video(self):
        return self._toggle_menu(["Stop video", "Start video"])

    def toggle_share(self):
        return self._toggle_menu(["Stop share", "Stop Share", "Start share", "Start Share"])

    def toggle_participants(self):
        # No Meeting-menu item exists for the participants panel; Zoom toggles
        # it with Cmd+U.
        items, errres = self._guard()
        if errres:
            return errres
        self._focus_meeting_window()
        ok, err = self._keystroke("u", ["command"])
        if ok:
            return {"ok": True, "action": "participants"}
        if _needs_accessibility(err):
            return {"ok": False, "error": "accessibility_permission_needed"}
        return {"ok": False, "error": err or "keystroke_failed"}

    def toggle_record(self):
        return self._toggle_menu(["Stop recording", "Record to the Cloud", "Record"])

    def toggle_hand(self):
        return self._toggle_menu(["Lower hand", "Raise hand"])

    def leave(self):
        """Leave (never end-for-all). Focus the meeting window, Cmd+W to open the
        confirm dialog, then click the 'Leave meeting' element. Verify by
        re-reading state; a non-host may leave immediately with no dialog."""
        _, errres = self._guard()
        if errres:
            return errres
        self._focus_meeting_window()
        _osa('tell application "System Events" to keystroke "w" using {command down}')
        time.sleep(0.7)
        # AppleScript comparisons are case-insensitive; "starts with Leave"
        # matches "Leave meeting"/"Leave Meeting" but never "End meeting for all".
        _osa('tell application "System Events" to tell process "' + ZOOM_PROC + '"\n'
             "  repeat with w in windows\n"
             "    try\n"
             '      click (first UI element of w whose description starts with "Leave")\n'
             "      exit repeat\n"
             "    end try\n"
             "  end repeat\n"
             "end tell")
        time.sleep(1.2)
        items, err = self._meeting_menu_items()
        left = (err is None and not items)
        return {"ok": left, "action": "leave"} if left else \
            {"ok": False, "error": "leave_not_confirmed"}
