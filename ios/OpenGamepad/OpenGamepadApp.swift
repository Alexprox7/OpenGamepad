import SwiftUI

@main
struct OpenGamepadApp: App {
    @StateObject private var connection = GamepadConnection()
    @StateObject private var input = InputModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(connection)
                .environmentObject(input)
                .preferredColorScheme(.dark)
                // QR codes of the companion are opengamepad:// links: the Camera app opens them here.
                .onOpenURL { url in
                    if let target = ConnectTarget.parse(url.absoluteString) { connection.connect(target) }
                }
        }
    }
}
