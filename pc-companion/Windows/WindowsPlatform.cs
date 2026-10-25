using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.Xbox360;

namespace OpenGamepad.Companion;

/// <summary>Windows backend: ViGEmBus Xbox 360 controllers and SendInput keyboard.</summary>
public sealed class WindowsPlatform(ViGEmClient vigem) : IPlatform
{
    public byte OsCode => Protocol.OsWindows;

    public IVirtualPad CreatePad() => new ViGEmPad(vigem.CreateXbox360Controller());

    public IKeyboardOutput CreateKeyboard() => new KeyboardInjector();
}

internal sealed class ViGEmPad : IVirtualPad
{
    private readonly IXbox360Controller _pad;

    public event Action<byte, byte, byte>? Feedback;

    public ViGEmPad(IXbox360Controller pad)
    {
        _pad = pad;
        _pad.AutoSubmitReport = false;
        _pad.FeedbackReceived += (_, e) => Feedback?.Invoke(e.LargeMotor, e.SmallMotor, e.LedNumber);
        _pad.Connect();
    }

    public void Update(ControllerState s)
    {
        _pad.SetButtonsFull(s.Buttons);
        _pad.SetAxisValue(Xbox360Axis.LeftThumbX, s.LeftX);
        _pad.SetAxisValue(Xbox360Axis.LeftThumbY, s.LeftY);
        _pad.SetAxisValue(Xbox360Axis.RightThumbX, s.RightX);
        _pad.SetAxisValue(Xbox360Axis.RightThumbY, s.RightY);
        _pad.SetSliderValue(Xbox360Slider.LeftTrigger, s.LeftTrigger);
        _pad.SetSliderValue(Xbox360Slider.RightTrigger, s.RightTrigger);
        _pad.SubmitReport();
    }

    public void Dispose() => _pad.Disconnect();
}
