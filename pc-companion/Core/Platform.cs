namespace OpenGamepad.Companion;

/// <summary>A virtual Xbox-style controller created for one phone.</summary>
public interface IVirtualPad : IDisposable
{
    void Update(ControllerState state);

    /// <summary>Rumble (large, small motor) and player LED from games, when the platform reports them.</summary>
    event Action<byte, byte, byte>? Feedback;
}

/// <summary>Turns HID keyboard reports from macros into key presses.</summary>
public interface IKeyboardOutput
{
    /// <summary>Payload: u8 modifiers (HID bits), u8 count, count × u8 HID usage.</summary>
    void Apply(ReadOnlySpan<byte> payload);

    void ReleaseAll();
}

/// <summary>What a companion needs from the operating system.</summary>
public interface IPlatform
{
    /// <summary>OS code sent in the hello reply: 1 Windows, 2 Linux, 3 macOS.</summary>
    byte OsCode { get; }

    IVirtualPad CreatePad();

    IKeyboardOutput CreateKeyboard();
}
