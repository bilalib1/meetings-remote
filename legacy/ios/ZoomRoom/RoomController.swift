import SwiftUI

/// Talks to the room-PC server and holds the UI state. Same behaviour as the
/// Android client: adaptive polling, optimistic control updates, and pending
/// Start/Join/Leave transitions that resolve when the PC agrees.
@MainActor
final class RoomController: ObservableObject {
    @Published var status: Status?
    @Published var pending: String?          // "starting" | "joining" | "leaving"
    @Published var meetingStart: Date?
    @Published var lastError: String?
    @Published var host: String {
        didSet { UserDefaults.standard.set(host, forKey: "host") }
    }

    private var pendingUntil = Date()
    private var optimistic: [String: (value: Bool, until: Date)] = [:]
    private var pollTask: Task<Void, Never>?

    init() {
        host = UserDefaults.standard.string(forKey: "host") ?? "192.168.1.50:8765"
    }

    // MARK: derived screen / header

    var screen: Screen {
        guard let s = status else { return .offline }
        if !s.zoomRunning { return .zoomClosed }
        if !s.accessibility { return .needAccess }
        if let p = pending {
            if p == "leaving" && s.inMeeting { return .transition }
            if (p == "starting" || p == "joining") && !s.inMeeting { return .transition }
        }
        return s.inMeeting ? .meeting : .home
    }

    var statusDot: (Color, String) {
        switch screen {
        case .offline: return (.zred, "Offline")
        case .zoomClosed: return (.zamber, "Zoom closed")
        case .needAccess: return (.zamber, "Needs permission")
        case .transition: return (.zamber, "Connecting")
        case .meeting: return (.zgreen, "In meeting")
        case .home: return (.zgreen, "Ready")
        }
    }

    var transitionText: String {
        switch pending {
        case "starting": return "Starting meeting…"
        case "joining": return "Joining meeting…"
        case "leaving": return "Leaving meeting…"
        default: return "…"
        }
    }

    // MARK: optimistic control state

    func setOpt(_ key: String, _ v: Bool) {
        optimistic[key] = (v, Date().addingTimeInterval(2.5))
    }
    /// Optimistic value if still fresh, else the server value.
    func val(_ key: String, _ server: Bool?) -> Bool? {
        if let o = optimistic[key] {
            if Date() < o.until { return o.value }
            optimistic[key] = nil
        }
        return server
    }
    private func reconcile(_ key: String, _ server: Bool?) {
        if let o = optimistic[key], o.value == server { optimistic[key] = nil }
    }

    // MARK: polling

    func start() {
        pollTask?.cancel()
        pollTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                guard let self else { break }
                await self.poll()
                let fast = self.pending != nil
                try? await Task.sleep(nanoseconds: UInt64((fast ? 0.45 : 1.4) * 1_000_000_000))
            }
        }
    }
    func stop() { pollTask?.cancel() }

    func poll() async {
        let data = await request("status", method: "GET")
        let s = data.flatMap { try? JSONDecoder().decode(Status.self, from: $0) }
        apply(s)
    }

    private func apply(_ s: Status?) {
        if pending != nil && Date() > pendingUntil { pending = nil }
        let wasInMeeting = status?.inMeeting ?? false
        if let s = s {
            reconcile("muted", s.muted); reconcile("video_on", s.videoOn)
            reconcile("audio_joined", s.audioJoined); reconcile("hand_raised", s.handRaised)
            if pending == "leaving" && !s.inMeeting { pending = nil }
            if (pending == "starting" || pending == "joining") && s.inMeeting { pending = nil }
            if s.inMeeting && !wasInMeeting { meetingStart = Date() }
            if !s.inMeeting { meetingStart = nil }
        } else {
            pending = nil
        }
        status = s
    }

    // MARK: actions

    private func beginTransition(_ tag: String, _ timeout: TimeInterval) {
        pending = tag
        pendingUntil = Date().addingTimeInterval(timeout)
    }

    private func fire(_ path: String) {
        Task {
            let data = await request(path, method: "POST")
            if let data = data,
               let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               (obj["ok"] as? Bool) == false, let err = obj["error"] as? String {
                self.lastError = friendly(err)
            }
            await poll()
        }
    }

    func newMeeting() { beginTransition("starting", 8); fire("new") }

    func join(id: String, pwd: String) {
        let digits = id.filter(\.isNumber)
        guard !digits.isEmpty else { return }
        beginTransition("joining", 12)
        let pass = pwd.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        fire("join?id=\(digits)&pwd=\(pass)")
    }

    func leave() { beginTransition("leaving", 8); fire("leave") }

    func toggleMute() {
        if val("audio_joined", status?.audioJoined) != true {
            setOpt("audio_joined", true); setOpt("muted", true)
        } else {
            setOpt("muted", !(val("muted", status?.muted) ?? false))
        }
        objectWillChange.send(); fire("mute")
    }
    func toggleVideo() {
        setOpt("video_on", !(val("video_on", status?.videoOn) ?? true))
        objectWillChange.send(); fire("video")
    }
    func toggleHand() {
        if let cur = val("hand_raised", status?.handRaised) {
            setOpt("hand_raised", !cur); objectWillChange.send()
        }
        fire("hand")
    }
    func participants() { fire("participants") }

    // MARK: networking

    private func request(_ path: String, method: String) async -> Data? {
        guard let url = URL(string: "http://\(host)/api/\(path)") else { return nil }
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.timeoutInterval = method == "GET" ? 4 : 15
        do {
            let (data, _) = try await URLSession.shared.data(for: req)
            return data
        } catch { return nil }
    }
}

func friendly(_ err: String) -> String {
    switch err {
    case "zoom_not_running": return "Zoom isn't open on the PC"
    case "accessibility_permission_needed": return "Grant Accessibility on the PC"
    case "not_in_meeting": return "Not in a meeting"
    case "control_unavailable", "audio_unavailable": return "That control isn't available right now"
    case "leave_not_confirmed": return "Couldn't confirm leave — try again"
    case "missing_meeting_id": return "Enter a valid meeting ID"
    default: return "Error: \(err)"
    }
}
