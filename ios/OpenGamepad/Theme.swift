import SwiftUI

/// Same palette as the Android app (ui/Theme.kt): anthracite by default, pure black with "OLED black".
struct Palette {
    /// Screen background.
    let background: Color
    /// Cards and panels.
    let surface: Color
    /// Controls inside a card (fields, list rows).
    let surfaceHigh: Color
    /// Borders and separators.
    let outline: Color
    let text: Color
    let muted: Color
    let faint: Color
    /// Controller buttons while pressed, and the stick knob.
    let pressed: Color
    let knob: Color

    static let dark = Palette(
        background: Color(hex: 0x131519),
        surface: Color(hex: 0x1B1E24),
        surfaceHigh: Color(hex: 0x252932),
        outline: Color(hex: 0x2F343E),
        text: Color(hex: 0xECEEF2),
        muted: Color(hex: 0x9BA2AF),
        faint: Color(hex: 0x6E7582),
        pressed: Color(hex: 0x353B47),
        knob: Color(hex: 0x4A515E)
    )

    /// Same hierarchy on pure black: OLED pixels stay off, cards are told apart by their outline.
    static let oled = Palette(
        background: .black,
        surface: Color(hex: 0x0A0B0D),
        surfaceHigh: Color(hex: 0x15171B),
        outline: Color(hex: 0x262A31),
        text: Color(hex: 0xECEEF2),
        muted: Color(hex: 0x9BA2AF),
        faint: Color(hex: 0x6E7582),
        pressed: Color(hex: 0x262A33),
        knob: Color(hex: 0x3A3F4A)
    )

    // Accents, states and face buttons are shared by both themes.
    static let accent = Color(hex: 0x7C9CFF)
    static let success = Color(hex: 0x4ADE80)
    static let danger = Color(hex: 0xF87171)
    static let faceY = Color(hex: 0xF2C94C)
    static let faceX = Color(hex: 0x56A8F5)
    static let faceB = Color(hex: 0xEB5757)
    static let faceA = Color(hex: 0x6FCF97)

    /// UserDefaults key of the "OLED black" switch.
    static let oledKey = "oledTheme"
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double(hex >> 16 & 0xFF) / 255, green: Double(hex >> 8 & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}

private struct PaletteKey: EnvironmentKey {
    static let defaultValue = Palette.dark
}

extension EnvironmentValues {
    var palette: Palette {
        get { self[PaletteKey.self] }
        set { self[PaletteKey.self] = newValue }
    }
}
