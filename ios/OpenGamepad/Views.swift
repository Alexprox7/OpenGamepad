import SwiftUI
import UIKit

private let outline = Color.white.opacity(0.55)

struct ContentView: View {
    @EnvironmentObject private var connection: GamepadConnection
    @EnvironmentObject private var input: InputModel
    @State private var haptics = UIImpactFeedbackGenerator(style: .heavy)

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if connection.isConnected {
                ControllerView()
            } else {
                ConnectView()
            }
        }
        .onAppear {
            input.onChange = { [weak connection] state in connection?.send(state) }
            connection.onFeedback = { feedback in
                let strength = max(feedback.largeMotor, feedback.smallMotor)
                if strength > 0 { haptics.impactOccurred(intensity: CGFloat(strength) / 255) }
            }
        }
        .onChange(of: connection.isConnected) { connected in
            UIApplication.shared.isIdleTimerDisabled = connected
            if connected { connection.send(input.state) } else { input.resetTouch() }
        }
    }
}

// MARK: - Connection screen

struct ConnectView: View {
    @EnvironmentObject private var connection: GamepadConnection
    @EnvironmentObject private var input: InputModel
    @AppStorage("lastTarget") private var address = ""
    @State private var found: [FoundHost] = []
    @State private var searching = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text("OpenGamepad").font(.largeTitle.bold())
                Text("Inquadra il codice QR del companion con la Fotocamera, cerca nella rete o scrivi l'indirizzo.")
                    .foregroundColor(.secondary)

                HStack {
                    TextField("IP:porta (es. 192.168.1.10:47800)", text: $address)
                        .keyboardType(.numbersAndPunctuation)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .padding(12)
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(outline))
                    Button("Connetti") {
                        if let target = ConnectTarget.parse(address) { connection.connect(target) }
                    }
                    .buttonStyle(OutlineButtonStyle())
                    .disabled(ConnectTarget.parse(address) == nil)
                }

                Button(searching ? "Ricerca in corso…" : "Cerca nella rete") {
                    searching = true
                    Task {
                        found = await Discovery.search()
                        searching = false
                    }
                }
                .buttonStyle(OutlineButtonStyle())
                .disabled(searching)

                ForEach(found) { host in
                    Button {
                        address = "\(host.host):\(host.port)"
                        connection.connect(host.target)
                    } label: {
                        HStack {
                            Image(systemName: host.name == "Nintendo Switch" ? "gamecontroller" : "desktopcomputer")
                            VStack(alignment: .leading) {
                                Text(host.name).bold()
                                Text("\(host.host):\(host.port)").font(.caption).foregroundColor(.secondary)
                            }
                            Spacer()
                        }
                        .padding(12)
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(outline))
                    }
                    .foregroundColor(.white)
                }
                if !searching && found.isEmpty {
                    Text("Serve il companion (Windows, Linux) o il sysmodule Switch sulla stessa rete Wi‑Fi.")
                        .font(.footnote).foregroundColor(.secondary)
                }

                statusText

                if let name = input.controllerName {
                    Label("Controller collegato: \(name)", systemImage: "gamecontroller.fill")
                        .foregroundColor(.green)
                }
            }
            .padding(24)
            .frame(maxWidth: 640, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .foregroundColor(.white)
    }

    @ViewBuilder private var statusText: some View {
        switch connection.status {
        case .connecting(let target): Text("Connessione a \(target)…").foregroundColor(.secondary)
        case .failed(let reason): Text(reason).foregroundColor(.red)
        default: EmptyView()
        }
    }
}

struct OutlineButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .foregroundColor(.white)
            .background(Capsule().fill(Color.white.opacity(configuration.isPressed ? 0.2 : 0)))
            .overlay(Capsule().stroke(outline))
    }
}

// MARK: - Controller screen

struct ControllerView: View {
    @EnvironmentObject private var connection: GamepadConnection
    @EnvironmentObject private var input: InputModel

    var body: some View {
        let nintendo = connection.isSwitch
        HStack(alignment: .center) {
            VStack(spacing: 18) {
                HStack(spacing: 10) {
                    PadButton(label: nintendo ? "ZL" : "LT", width: 64, height: 40) { input.setTrigger(left: true, pressed: $0) }
                    PadButton(label: nintendo ? "L" : "LB", width: 64, height: 40) { input.setButton(Buttons.leftShoulder, pressed: $0) }
                }
                StickView { input.setStick(left: true, x: $0, y: $1) }
                HStack(spacing: 18) {
                    DPadView(input: input)
                    PadButton(label: "L3", width: 44, height: 44) { input.setButton(Buttons.leftThumb, pressed: $0) }
                }
            }
            Spacer()
            VStack(spacing: 14) {
                Text(statusLine).font(.caption).foregroundColor(.secondary).multilineTextAlignment(.center)
                HStack(spacing: 14) {
                    PadButton(label: nintendo ? "−" : "⧉", width: 44, height: 44) { input.setButton(Buttons.back, pressed: $0) }
                    PadButton(label: "⌂", width: 44, height: 44) { input.setButton(Buttons.guide, pressed: $0) }
                    PadButton(label: nintendo ? "+" : "☰", width: 44, height: 44) { input.setButton(Buttons.start, pressed: $0) }
                }
                Button("Disconnetti") { connection.disconnect() }
                    .buttonStyle(OutlineButtonStyle())
                    .font(.caption)
            }
            Spacer()
            VStack(spacing: 18) {
                HStack(spacing: 10) {
                    PadButton(label: nintendo ? "R" : "RB", width: 64, height: 40) { input.setButton(Buttons.rightShoulder, pressed: $0) }
                    PadButton(label: nintendo ? "ZR" : "RT", width: 64, height: 40) { input.setTrigger(left: false, pressed: $0) }
                }
                FaceButtons(input: input, nintendo: nintendo)
                HStack(spacing: 18) {
                    PadButton(label: "R3", width: 44, height: 44) { input.setButton(Buttons.rightThumb, pressed: $0) }
                    StickView { input.setStick(left: false, x: $0, y: $1) }
                }
            }
        }
        .padding(.horizontal, 28)
        .padding(.vertical, 12)
        .foregroundColor(.white)
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
    }

    private var statusLine: String {
        guard case .connected(let target, let host) = connection.status else { return "" }
        var line = "\(target) (\(host))"
        if connection.player > 0 { line += " · giocatore \(connection.player)" }
        if let name = input.controllerName { line += "\n\(name)" }
        return line
    }
}

/// Positions follow the Xbox layout; on the Switch the labels follow the Nintendo one.
struct FaceButtons: View {
    let input: InputModel
    let nintendo: Bool

    var body: some View {
        let size: CGFloat = 56
        VStack(spacing: 4) {
            PadButton(label: nintendo ? "X" : "Y", color: Color(red: 0.95, green: 0.79, blue: 0.30), width: size, height: size) { input.setButton(Buttons.y, pressed: $0) }
            HStack(spacing: size) {
                PadButton(label: nintendo ? "Y" : "X", color: Color(red: 0.34, green: 0.66, blue: 0.96), width: size, height: size) { input.setButton(Buttons.x, pressed: $0) }
                PadButton(label: nintendo ? "A" : "B", color: Color(red: 0.92, green: 0.34, blue: 0.34), width: size, height: size) { input.setButton(Buttons.b, pressed: $0) }
            }
            PadButton(label: nintendo ? "B" : "A", color: Color(red: 0.44, green: 0.81, blue: 0.59), width: size, height: size) { input.setButton(Buttons.a, pressed: $0) }
        }
    }
}

struct DPadView: View {
    let input: InputModel

    var body: some View {
        let size: CGFloat = 44
        VStack(spacing: 0) {
            PadButton(label: "▲", width: size, height: size, rounded: true) { input.setButton(Buttons.dpadUp, pressed: $0) }
            HStack(spacing: size) {
                PadButton(label: "◀", width: size, height: size, rounded: true) { input.setButton(Buttons.dpadLeft, pressed: $0) }
                PadButton(label: "▶", width: size, height: size, rounded: true) { input.setButton(Buttons.dpadRight, pressed: $0) }
            }
            PadButton(label: "▼", width: size, height: size, rounded: true) { input.setButton(Buttons.dpadDown, pressed: $0) }
        }
    }
}

/// Outline button that reports press and release (multi-touch: each button tracks its own finger).
struct PadButton: View {
    let label: String
    var color: Color = .white
    var width: CGFloat
    var height: CGFloat
    var rounded = false
    let onChange: (Bool) -> Void
    @State private var pressed = false

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: rounded ? 10 : min(width, height) / 2)
        shape
            .fill(color.opacity(pressed ? 0.35 : 0))
            .overlay(shape.stroke(color.opacity(0.8), lineWidth: 2))
            .overlay(Text(label).font(.system(size: min(width, height) * 0.36, weight: .semibold)).foregroundColor(color))
            .frame(width: width, height: height)
            .contentShape(shape)
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in
                        if !pressed {
                            pressed = true
                            UISelectionFeedbackGenerator().selectionChanged()
                            onChange(true)
                        }
                    }
                    .onEnded { _ in
                        pressed = false
                        onChange(false)
                    }
            )
    }
}

/// Analog stick: x and y in -1…1, y up positive.
struct StickView: View {
    let onMove: (CGFloat, CGFloat) -> Void
    @State private var offset: CGSize = .zero
    private let radius: CGFloat = 64

    var body: some View {
        ZStack {
            Circle().stroke(outline, lineWidth: 2)
            Circle()
                .fill(Color.white.opacity(0.18))
                .overlay(Circle().stroke(Color.white.opacity(0.8), lineWidth: 2))
                .frame(width: radius, height: radius)
                .offset(offset)
        }
        .frame(width: radius * 2, height: radius * 2)
        .contentShape(Circle())
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { value in
                    var dx = value.location.x - radius
                    var dy = value.location.y - radius
                    let length = (dx * dx + dy * dy).squareRoot()
                    if length > radius {
                        dx = dx / length * radius
                        dy = dy / length * radius
                    }
                    offset = CGSize(width: dx, height: dy)
                    onMove(dx / radius, -dy / radius)
                }
                .onEnded { _ in
                    offset = .zero
                    onMove(0, 0)
                }
        )
    }
}
