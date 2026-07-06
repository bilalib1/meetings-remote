import SwiftUI

// Palette shared with the Android client so the two look identical.
extension Color {
    static let bg     = Color(red: 0x0A/255, green: 0x0C/255, blue: 0x10/255)
    static let tile   = Color(red: 0x1C/255, green: 0x21/255, blue: 0x2A/255)
    static let ink    = Color(red: 0xF4/255, green: 0xF6/255, blue: 0xF8/255)
    static let muted  = Color(red: 0x8B/255, green: 0x92/255, blue: 0x9C/255)
    static let zblue  = Color(red: 0x2D/255, green: 0x8C/255, blue: 0xFF/255)
    static let zorange = Color(red: 0xFF/255, green: 0x7A/255, blue: 0x29/255)
    static let zgreen = Color(red: 0x2F/255, green: 0xB8/255, blue: 0x6B/255)
    static let zred   = Color(red: 0xF0/255, green: 0x45/255, blue: 0x3A/255)
    static let zamber = Color(red: 0xF4/255, green: 0xA9/255, blue: 0x3B/255)
}

// Scale-on-press feedback for every tappable control.
struct PressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.94 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}
