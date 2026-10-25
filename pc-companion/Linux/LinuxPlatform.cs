namespace OpenGamepad.Companion;

/// <summary>Linux backend: uinput devices that look like a wired Xbox 360 pad and a keyboard.</summary>
public sealed class LinuxPlatform : IPlatform
{
    public byte OsCode => Protocol.OsLinux;

    public IVirtualPad CreatePad() => new UinputPad();

    // One uinput keyboard for all phones, created on first use and kept until exit.
    private readonly Lazy<UinputKeyboard> _keyboard = new(() => new UinputKeyboard());

    public IKeyboardOutput CreateKeyboard() => _keyboard.Value;

    /// <summary>Fails early, with a readable message, when /dev/uinput cannot be used.</summary>
    public static void CheckAccess() => new Uinput().Dispose();
}

/// <summary>
/// Same identity and event layout as the kernel xpad driver, so SDL, Steam and Wine
/// treat it as an Xbox 360 controller.
/// </summary>
internal sealed class UinputPad : IVirtualPad
{
    private const ushort AbsX = 0x00, AbsY = 0x01, AbsZ = 0x02, AbsRx = 0x03, AbsRy = 0x04, AbsRz = 0x05;
    private const ushort AbsHat0X = 0x10, AbsHat0Y = 0x11;

    // XUSB bit -> Linux key code.
    private static readonly (ushort Bit, ushort Key)[] Buttons =
    [
        (0x1000, 0x130), // A -> BTN_SOUTH
        (0x2000, 0x131), // B -> BTN_EAST
        (0x4000, 0x133), // X -> BTN_NORTH
        (0x8000, 0x134), // Y -> BTN_WEST
        (0x0100, 0x136), // LB -> BTN_TL
        (0x0200, 0x137), // RB -> BTN_TR
        (0x0020, 0x13A), // Back -> BTN_SELECT
        (0x0010, 0x13B), // Start -> BTN_START
        (0x0400, 0x13C), // Guide -> BTN_MODE
        (0x0040, 0x13D), // LS -> BTN_THUMBL
        (0x0080, 0x13E), // RS -> BTN_THUMBR
    ];

    private readonly Uinput _device = new();
    private readonly Dictionary<short, (ushort Strong, ushort Weak)> _effects = [];
    private readonly CancellationTokenSource _cts = new();
    private ControllerState? _last;

    public event Action<byte, byte, byte>? Feedback;

    public UinputPad()
    {
        _device.EnableEvent(Uinput.EvKey);
        foreach (var (_, key) in Buttons) _device.EnableKey(key);
        _device.EnableEvent(Uinput.EvAbs);
        foreach (var axis in new[] { AbsX, AbsY, AbsRx, AbsRy })
            _device.EnableAbs(axis, -32768, 32767, fuzz: 16, flat: 128);
        _device.EnableAbs(AbsZ, 0, 255);
        _device.EnableAbs(AbsRz, 0, 255);
        _device.EnableAbs(AbsHat0X, -1, 1);
        _device.EnableAbs(AbsHat0Y, -1, 1);
        _device.EnableEvent(Uinput.EvFf);
        _device.EnableFf(Uinput.FfRumble);
        _device.Create("Microsoft X-Box 360 pad", 0x045E, 0x028E, 0x0114, ffEffects: 16);
        new Thread(ForceFeedbackLoop) { IsBackground = true, Name = "uinput-ff" }.Start();
    }

    public void Update(ControllerState s)
    {
        if (_last == s) return;
        _last = s;
        foreach (var (bit, key) in Buttons) _device.Emit(Uinput.EvKey, key, (s.Buttons & bit) != 0 ? 1 : 0);
        _device.Emit(Uinput.EvAbs, AbsX, s.LeftX);
        _device.Emit(Uinput.EvAbs, AbsY, ~s.LeftY); // XInput Y points up, evdev Y points down
        _device.Emit(Uinput.EvAbs, AbsRx, s.RightX);
        _device.Emit(Uinput.EvAbs, AbsRy, ~s.RightY);
        _device.Emit(Uinput.EvAbs, AbsZ, s.LeftTrigger);
        _device.Emit(Uinput.EvAbs, AbsRz, s.RightTrigger);
        _device.Emit(Uinput.EvAbs, AbsHat0X, Axis(s.Buttons, 0x4, 0x8));
        _device.Emit(Uinput.EvAbs, AbsHat0Y, Axis(s.Buttons, 0x1, 0x2));
        _device.Sync();
    }

    private static int Axis(ushort buttons, ushort negative, ushort positive) =>
        ((buttons & positive) != 0 ? 1 : 0) - ((buttons & negative) != 0 ? 1 : 0);

    /// <summary>Games upload rumble effects and then play them by id.</summary>
    private void ForceFeedbackLoop()
    {
        while (!_cts.IsCancellationRequested)
        {
            if (!_device.Read(200, out var type, out var code, out var value)) continue;
            if (type == Uinput.EvUinput && code == Uinput.UiFfUpload)
            {
                if (_device.AcceptUpload(value) is { } effect) _effects[effect.Id] = (effect.Strong, effect.Weak);
            }
            else if (type == Uinput.EvUinput && code == Uinput.UiFfErase)
            {
                _device.AcceptErase(value);
            }
            else if (type == Uinput.EvFf && _effects.TryGetValue((short)code, out var rumble))
            {
                var on = value > 0;
                Feedback?.Invoke(on ? (byte)(rumble.Strong >> 8) : (byte)0, on ? (byte)(rumble.Weak >> 8) : (byte)0, 255);
            }
        }
    }

    public void Dispose()
    {
        _cts.Cancel();
        _device.Dispose();
    }
}

/// <summary>Keyboard for macros: HID usages mapped to Linux key codes, only differences are sent.</summary>
internal sealed class UinputKeyboard : IKeyboardOutput
{
    // HID usage 0x00-0x73 -> Linux key code (same table as the kernel's hid-input).
    private static readonly byte[] UsageToKey =
    [
          0,   0,   0,   0,  30,  48,  46,  32,  18,  33,  34,  35,  23,  36,  37,  38,
         50,  49,  24,  25,  16,  19,  31,  20,  22,  47,  17,  45,  21,  44,   2,   3,
          4,   5,   6,   7,   8,   9,  10,  11,  28,   1,  14,  15,  57,  12,  13,  26,
         27,  43,  43,  39,  40,  41,  51,  52,  53,  58,  59,  60,  61,  62,  63,  64,
         65,  66,  67,  68,  87,  88,  99,  70, 119, 110, 102, 104, 111, 107, 109, 106,
        105, 108, 103,  69,  98,  55,  74,  78,  96,  79,  80,  81,  75,  76,  77,  71,
         72,  73,  82,  83,  86, 127, 116, 117, 183, 184, 185, 186, 187, 188, 189, 190,
        191, 192, 193, 194,
    ];

    // HID modifier bits 0-7 (usages 0xE0-0xE7): LCtrl, LShift, LAlt, LMeta, RCtrl, RShift, RAlt, RMeta.
    private static readonly ushort[] ModifierKeys = [29, 42, 56, 125, 97, 54, 100, 126];

    private readonly Uinput _device = new();
    private HashSet<ushort> _down = [];

    public UinputKeyboard()
    {
        _device.EnableEvent(Uinput.EvKey);
        foreach (var key in UsageToKey.Where(k => k != 0).Distinct()) _device.EnableKey(key);
        foreach (var key in ModifierKeys) _device.EnableKey(key);
        _device.Create("OpenGamepad Keyboard", 0x1209, 0x4750, 1);
    }

    public void Apply(ReadOnlySpan<byte> payload)
    {
        if (payload.Length < 2) return;
        var now = new HashSet<ushort>();
        for (int bit = 0; bit < 8; bit++)
            if ((payload[0] & (1 << bit)) != 0) now.Add(ModifierKeys[bit]);
        int count = Math.Min(payload[1], payload.Length - 2);
        foreach (var usage in payload.Slice(2, count))
        {
            if (usage >= 0xE0 && usage <= 0xE7) now.Add(ModifierKeys[usage - 0xE0]);
            else if (usage < UsageToKey.Length && UsageToKey[usage] != 0) now.Add(UsageToKey[usage]);
        }

        lock (_device)
        {
            foreach (var key in _down.Except(now)) _device.Emit(Uinput.EvKey, key, 0);
            foreach (var key in now.Except(_down).OrderBy(k => ModifierKeys.Contains(k) ? 0 : 1))
                _device.Emit(Uinput.EvKey, key, 1);
            _down = now;
            _device.Sync();
        }
    }

    public void ReleaseAll() => Apply([0, 0]);
}
