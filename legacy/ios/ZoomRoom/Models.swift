import Foundation

/// Mirrors the server's GET /api/status snapshot. Nullable fields stay optional
/// so controls can grey out / hide when a value is unknown.
struct Status: Decodable, Equatable {
    var zoomRunning: Bool
    var accessibility: Bool
    var inMeeting: Bool
    var audioJoined: Bool?
    var muted: Bool?
    var videoOn: Bool?
    var handRaised: Bool?
    var topic: String?

    enum CodingKeys: String, CodingKey {
        case zoomRunning = "zoom_running"
        case accessibility
        case inMeeting = "in_meeting"
        case audioJoined = "audio_joined"
        case muted
        case videoOn = "video_on"
        case handRaised = "hand_raised"
        case topic
    }
}

/// The single screen the console shows, derived entirely from status + pending.
enum Screen: Equatable { case offline, zoomClosed, needAccess, home, meeting, transition }
