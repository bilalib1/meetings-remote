import SwiftUI

struct ContentView: View {
    @StateObject private var c = RoomController()
    @State private var showJoin = false
    @State private var showAddress = false
    @State private var confirmLeave = false

    var body: some View {
        ZStack {
            Color.bg.ignoresSafeArea()
            VStack(spacing: 0) {
                header
                ZStack {
                    switch c.screen {
                    case .home: HomeView(c: c, showJoin: $showJoin)
                    case .meeting: MeetingView(c: c, confirmLeave: $confirmLeave)
                    case .transition: TransitionView(text: c.transitionText)
                    default: OverlayView(c: c).onTapGesture { showAddress = true }
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .animation(.easeInOut(duration: 0.22), value: c.screen)
            }
            .padding(.horizontal, 22)
            .padding(.vertical, 12)
        }
        .onAppear { c.start() }
        .onDisappear { c.stop() }
        .sheet(isPresented: $showJoin) { JoinSheet(c: c) }
        .sheet(isPresented: $showAddress) { AddressSheet(c: c) }
        .alert("Leave meeting?", isPresented: $confirmLeave) {
            Button("Leave", role: .destructive) { c.leave() }
            Button("Cancel", role: .cancel) {}
        }
        .alert("Error", isPresented: Binding(
            get: { c.lastError != nil }, set: { if !$0 { c.lastError = nil } })) {
            Button("OK", role: .cancel) { c.lastError = nil }
        } message: { Text(c.lastError ?? "") }
    }

    private var header: some View {
        HStack {
            Text("Zoom Room")
                .font(.system(size: 21, weight: .bold)).foregroundColor(.ink)
                .onLongPressGesture { showAddress = true }   // rarely-needed address change
            Spacer()
            Circle().fill(c.statusDot.0).frame(width: 9, height: 9)
            Text(c.statusDot.1).font(.system(size: 14)).foregroundColor(.muted)
        }
        .padding(.bottom, 4)
    }
}

// MARK: Home

struct HomeView: View {
    @ObservedObject var c: RoomController
    @Binding var showJoin: Bool

    var body: some View {
        VStack(spacing: 8) {
            Spacer()
            Text("Ready to meet").font(.system(size: 32, weight: .bold)).foregroundColor(.ink)
            Text("Connected to \(c.host)").font(.system(size: 15)).foregroundColor(.muted)
                .padding(.bottom, 26)
            HStack(spacing: 14) {
                BigCard(icon: "plus", label: "New Meeting", fill: .zorange) { c.newMeeting() }
                BigCard(icon: "arrow.right.to.line", label: "Join", fill: .zblue) { showJoin = true }
            }
            .frame(height: 172)
            Spacer()
        }
    }
}

struct BigCard: View {
    let icon: String, label: String, fill: Color, action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 16) {
                ZStack {
                    Circle().fill(Color.white.opacity(0.2)).frame(width: 56, height: 56)
                    Image(systemName: icon).font(.system(size: 26, weight: .semibold))
                        .foregroundColor(.white)
                }
                Text(label).font(.system(size: 20, weight: .bold)).foregroundColor(.white)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(RoundedRectangle(cornerRadius: 24).fill(fill))
        }
        .buttonStyle(PressStyle())
    }
}

// MARK: Meeting

struct MeetingView: View {
    @ObservedObject var c: RoomController
    @Binding var confirmLeave: Bool

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Spacer()
                Button { confirmLeave = true } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "phone.down.fill").font(.system(size: 16, weight: .bold))
                        Text("Leave").font(.system(size: 16, weight: .bold))
                    }
                    .foregroundColor(.white)
                    .padding(.horizontal, 20).padding(.vertical, 11)
                    .background(Capsule().fill(Color.zred))
                }
                .buttonStyle(PressStyle())
            }

            Spacer()
            VStack(spacing: 6) {
                Text(topic).font(.system(size: 24, weight: .bold)).foregroundColor(.ink)
                if let start = c.meetingStart {
                    TimelineView(.periodic(from: start, by: 1)) { ctx in
                        Text(elapsed(start, ctx.date)).font(.system(size: 16)).foregroundColor(.muted)
                    }
                } else {
                    Text("00:00").font(.system(size: 16)).foregroundColor(.muted)
                }
                Text("Audio and video are on the room display")
                    .font(.system(size: 13)).foregroundColor(Color(white: 0.36))
            }
            Spacer()

            HStack(alignment: .top, spacing: 0) {
                muteControl
                videoControl
                CircleControl(icon: "person.2.fill", label: "Participants", fill: .tile) { c.participants() }
                handControl
            }
            .padding(.bottom, 18)
        }
    }

    private var topic: String {
        let t = c.status?.topic ?? ""
        return t.isEmpty ? "In meeting" : t
    }

    private var muteControl: some View {
        let aj = c.val("audio_joined", c.status?.audioJoined)
        let muted = c.val("muted", c.status?.muted)
        let (icon, label, fill): (String, String, Color) =
            aj != true ? ("mic.slash.fill", "Join Audio", .tile)
            : muted == true ? ("mic.slash.fill", "Unmute", .zred)
            : ("mic.fill", "Mute", .tile)
        return CircleControl(icon: icon, label: label, fill: fill) { c.toggleMute() }
    }

    private var videoControl: some View {
        let on = c.val("video_on", c.status?.videoOn) == true
        return CircleControl(icon: on ? "video.fill" : "video.slash.fill",
                             label: on ? "Stop Video" : "Start Video",
                             fill: on ? .tile : .zred) { c.toggleVideo() }
    }

    private var handControl: some View {
        let hr = c.val("hand_raised", c.status?.handRaised)
        return CircleControl(
            icon: "hand.raised.fill",
            label: hr == true ? "Lower Hand" : "Raise Hand",
            fill: hr == true ? .zblue : .tile,
            enabled: hr != nil) { c.toggleHand() }
    }
}

struct CircleControl: View {
    let icon: String, label: String, fill: Color
    var enabled: Bool = true
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            VStack(spacing: 9) {
                ZStack {
                    Circle().fill(fill).frame(width: 66, height: 66)
                    Image(systemName: icon).font(.system(size: 25)).foregroundColor(.white)
                }
                Text(label).font(.system(size: 13)).foregroundColor(.muted).lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .opacity(enabled ? 1 : 0.35)
        }
        .buttonStyle(PressStyle())
        .disabled(!enabled)
    }
}

// MARK: Transition / Overlay

struct TransitionView: View {
    let text: String
    var body: some View {
        VStack(spacing: 20) {
            ProgressView().controlSize(.large).tint(.zblue)
            Text(text).font(.system(size: 19, weight: .bold)).foregroundColor(.ink)
        }
    }
}

struct OverlayView: View {
    @ObservedObject var c: RoomController
    var body: some View {
        let (icon, title, sub) = content
        return VStack(spacing: 6) {
            Image(systemName: icon).font(.system(size: 42)).foregroundColor(.muted).padding(.bottom, 12)
            Text(title).font(.system(size: 21, weight: .bold)).foregroundColor(.ink)
            Text(sub).font(.system(size: 15)).foregroundColor(.muted)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .contentShape(Rectangle())
    }
    private var content: (String, String, String) {
        switch c.screen {
        case .zoomClosed:
            return ("rectangle.portrait.and.arrow.right", "Zoom isn't open on the PC",
                    "Open Zoom on the room computer to begin.")
        case .needAccess:
            return ("gearshape", "Grant Accessibility on the PC",
                    "System Settings → Privacy & Security → Accessibility → enable the terminal running the server.")
        default:
            return ("gearshape", "Can't reach the room PC",
                    "\(c.host) — check it's on the same Wi-Fi and the server is running.\nTap here to change the address.")
        }
    }
}

// MARK: Sheets

struct JoinSheet: View {
    @ObservedObject var c: RoomController
    @Environment(\.dismiss) var dismiss
    @State private var id = ""
    @State private var pwd = ""
    var body: some View {
        NavigationStack {
            Form {
                TextField("Meeting ID", text: $id).keyboardType(.numberPad)
                TextField("Passcode (optional)", text: $pwd)
            }
            .navigationTitle("Join a meeting")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Join") { c.join(id: id, pwd: pwd); dismiss() }
                        .disabled(id.filter(\.isNumber).isEmpty)
                }
            }
        }
    }
}

struct AddressSheet: View {
    @ObservedObject var c: RoomController
    @Environment(\.dismiss) var dismiss
    @State private var text = ""
    var body: some View {
        NavigationStack {
            Form {
                Section("Room PC address") {
                    TextField("host:port", text: $text).autocorrectionDisabled().textInputAutocapitalization(.never)
                }
                Text("IP:port of the Mac running the controller server.")
                    .font(.footnote).foregroundColor(.secondary)
            }
            .navigationTitle("Room PC address")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        let t = text.trimmingCharacters(in: .whitespaces)
                        if !t.isEmpty { c.host = t }
                        dismiss()
                    }
                }
            }
            .onAppear { text = c.host }
        }
    }
}

func elapsed(_ from: Date, _ to: Date) -> String {
    let s = max(0, Int(to.timeIntervalSince(from)))
    return String(format: "%02d:%02d", s / 60, s % 60)
}
