using System.Runtime.InteropServices;

namespace OpenGamepad.Companion;

/// <summary>
/// Turns HID keyboard reports from the phone (macros) into Windows key presses.
/// Uses scan codes, which games read reliably, and only sends the differences
/// against the previous report.
/// </summary>
public sealed class KeyboardInjector
{
    private HashSet<ushort> _down = [];

    /// <summary>Payload: u8 modifiers (HID bits), u8 count, count × u8 HID usage.</summary>
    public void Apply(ReadOnlySpan<byte> payload)
    {
        if (payload.Length < 2) return;
        var now = new HashSet<ushort>();
        byte modifiers = payload[0];
        for (int bit = 0; bit < 8; bit++)
            if ((modifiers & (1 << bit)) != 0) now.Add(ModifierVk[bit]);
        int count = Math.Min(payload[1], payload.Length - 2);
        foreach (var usage in payload.Slice(2, count))
            if (UsageToVk(usage) is ushort vk) now.Add(vk);

        var inputs = new List<INPUT>();
        // Release first, then press (modifiers before keys thanks to the ordering).
        foreach (var vk in _down.Except(now)) inputs.Add(Key(vk, up: true));
        foreach (var vk in now.Except(_down).OrderBy(v => ModifierVk.Contains(v) ? 0 : 1)) inputs.Add(Key(vk, up: false));
        _down = now;
        if (inputs.Count > 0) SendInput((uint)inputs.Count, inputs.ToArray(), Marshal.SizeOf<INPUT>());
    }

    /// <summary>Releases everything (phone disconnected).</summary>
    public void ReleaseAll() => Apply([0, 0]);

    private static INPUT Key(ushort vk, bool up)
    {
        uint scan = MapVirtualKeyW(vk, MAPVK_VK_TO_VSC_EX);
        uint flags = KEYEVENTF_SCANCODE | (up ? KEYEVENTF_KEYUP : 0);
        if ((scan & 0xFF00) == 0xE000) flags |= KEYEVENTF_EXTENDEDKEY;
        // Keys without a scan code (rare) fall back to the virtual key.
        if ((scan & 0xFF) == 0) flags &= ~KEYEVENTF_SCANCODE;
        return new INPUT
        {
            type = INPUT_KEYBOARD,
            u = new InputUnion { ki = new KEYBDINPUT { wVk = vk, wScan = (ushort)(scan & 0xFF), dwFlags = flags } },
        };
    }

    // HID modifier bits: LCtrl, LShift, LAlt, LGui, RCtrl, RShift, RAlt, RGui
    private static readonly ushort[] ModifierVk = [0xA2, 0xA0, 0xA4, 0x5B, 0xA3, 0xA1, 0xA5, 0x5C];

    private static ushort? UsageToVk(byte usage) => usage switch
    {
        >= 0x04 and <= 0x1D => (ushort)('A' + usage - 0x04),
        >= 0x1E and <= 0x26 => (ushort)('1' + usage - 0x1E),
        0x27 => '0',
        0x28 => 0x0D, // Enter
        0x29 => 0x1B, // Esc
        0x2A => 0x08, // Backspace
        0x2B => 0x09, // Tab
        0x2C => 0x20, // Space
        0x2D => 0xBD, // -
        0x2E => 0xBB, // =
        0x2F => 0xDB, // [
        0x30 => 0xDD, // ]
        0x31 => 0xDC, // \
        0x33 => 0xBA, // ;
        0x34 => 0xDE, // '
        0x35 => 0xC0, // `
        0x36 => 0xBC, // ,
        0x37 => 0xBE, // .
        0x38 => 0xBF, // /
        0x39 => 0x14, // Caps Lock
        >= 0x3A and <= 0x45 => (ushort)(0x70 + usage - 0x3A), // F1-F12
        0x46 => 0x2C, // Print Screen
        0x47 => 0x91, // Scroll Lock
        0x48 => 0x13, // Pause
        0x49 => 0x2D, // Insert
        0x4A => 0x24, // Home
        0x4B => 0x21, // Page Up
        0x4C => 0x2E, // Delete
        0x4D => 0x23, // End
        0x4E => 0x22, // Page Down
        0x4F => 0x27, // Right
        0x50 => 0x25, // Left
        0x51 => 0x28, // Down
        0x52 => 0x26, // Up
        0x53 => 0x90, // Num Lock
        >= 0x68 and <= 0x73 => (ushort)(0x7C + usage - 0x68), // F13-F24
        _ => null,
    };

    private const uint INPUT_KEYBOARD = 1;
    private const uint KEYEVENTF_EXTENDEDKEY = 0x1;
    private const uint KEYEVENTF_KEYUP = 0x2;
    private const uint KEYEVENTF_SCANCODE = 0x8;
    private const uint MAPVK_VK_TO_VSC_EX = 4;

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT
    {
        public uint type;
        public InputUnion u;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT
    {
        public ushort wVk;
        public ushort wScan;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int dx, dy;
        public uint mouseData, dwFlags, time;
        public IntPtr dwExtraInfo;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint count, INPUT[] inputs, int size);

    [DllImport("user32.dll")]
    private static extern uint MapVirtualKeyW(uint code, uint mapType);
}
