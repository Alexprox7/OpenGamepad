import GameController

/// Touch controls plus the first physical controller (MFi, Xbox, PlayStation, Switch, Backbone,
/// GameSir…), merged like on Android: buttons are OR-ed, the larger stick deflection wins.
@MainActor
final class InputModel: ObservableObject {
    @Published private(set) var controllerName: String?
    var onChange: (@MainActor (ControllerState) -> Void)?

    private var touch = ControllerState()
    private var physical = ControllerState()
    private var observers: [NSObjectProtocol] = []

    init() {
        for name in [Notification.Name.GCControllerDidConnect, .GCControllerDidDisconnect] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.attachController() }
            })
        }
        attachController()
    }

    var state: ControllerState { Self.merge(touch, physical) }

    func setButton(_ mask: UInt16, pressed: Bool) {
        if pressed { touch.buttons |= mask } else { touch.buttons &= ~mask }
        emit()
    }

    func setStick(left: Bool, x: CGFloat, y: CGFloat) {
        let sx = Self.axis(Float(x)), sy = Self.axis(Float(y))
        if left { touch.leftX = sx; touch.leftY = sy } else { touch.rightX = sx; touch.rightY = sy }
        emit()
    }

    func setTrigger(left: Bool, pressed: Bool) {
        if left { touch.leftTrigger = pressed ? 255 : 0 } else { touch.rightTrigger = pressed ? 255 : 0 }
        emit()
    }

    func resetTouch() {
        touch = ControllerState()
        emit()
    }

    private func emit() {
        onChange?(state)
    }

    private func attachController() {
        guard let controller = GCController.controllers().first(where: { $0.extendedGamepad != nil }),
              let gamepad = controller.extendedGamepad else {
            controllerName = nil
            physical = ControllerState()
            emit()
            return
        }
        controllerName = controller.vendorName ?? "Controller"
        gamepad.valueChangedHandler = { [weak self] gamepad, _ in
            let state = Self.read(gamepad)
            Task { @MainActor in
                self?.physical = state
                self?.emit()
            }
        }
        physical = Self.read(gamepad)
        emit()
    }

    nonisolated private static func read(_ g: GCExtendedGamepad) -> ControllerState {
        var b: UInt16 = 0
        func set(_ pressed: Bool?, _ mask: UInt16) { if pressed == true { b |= mask } }
        set(g.dpad.up.isPressed, Buttons.dpadUp)
        set(g.dpad.down.isPressed, Buttons.dpadDown)
        set(g.dpad.left.isPressed, Buttons.dpadLeft)
        set(g.dpad.right.isPressed, Buttons.dpadRight)
        set(g.buttonA.isPressed, Buttons.a)
        set(g.buttonB.isPressed, Buttons.b)
        set(g.buttonX.isPressed, Buttons.x)
        set(g.buttonY.isPressed, Buttons.y)
        set(g.leftShoulder.isPressed, Buttons.leftShoulder)
        set(g.rightShoulder.isPressed, Buttons.rightShoulder)
        set(g.buttonMenu.isPressed, Buttons.start)
        set(g.buttonOptions?.isPressed, Buttons.back)
        set(g.buttonHome?.isPressed, Buttons.guide)
        set(g.leftThumbstickButton?.isPressed, Buttons.leftThumb)
        set(g.rightThumbstickButton?.isPressed, Buttons.rightThumb)
        return ControllerState(
            buttons: b,
            leftX: axis(g.leftThumbstick.xAxis.value), leftY: axis(g.leftThumbstick.yAxis.value),
            rightX: axis(g.rightThumbstick.xAxis.value), rightY: axis(g.rightThumbstick.yAxis.value),
            leftTrigger: UInt8(max(0, min(255, g.leftTrigger.value * 255))),
            rightTrigger: UInt8(max(0, min(255, g.rightTrigger.value * 255))))
    }

    nonisolated private static func axis(_ value: Float) -> Int16 {
        Int16(max(-1, min(1, value)) * 32767)
    }

    nonisolated private static func merge(_ a: ControllerState, _ b: ControllerState) -> ControllerState {
        func stick(_ ax: Int16, _ ay: Int16, _ bx: Int16, _ by: Int16) -> (Int16, Int16) {
            let ma = Int(ax) * Int(ax) + Int(ay) * Int(ay)
            let mb = Int(bx) * Int(bx) + Int(by) * Int(by)
            return ma >= mb ? (ax, ay) : (bx, by)
        }
        let left = stick(a.leftX, a.leftY, b.leftX, b.leftY)
        let right = stick(a.rightX, a.rightY, b.rightX, b.rightY)
        return ControllerState(
            buttons: a.buttons | b.buttons,
            leftX: left.0, leftY: left.1, rightX: right.0, rightY: right.1,
            leftTrigger: max(a.leftTrigger, b.leftTrigger),
            rightTrigger: max(a.rightTrigger, b.rightTrigger))
    }
}
