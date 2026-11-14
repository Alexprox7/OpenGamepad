using System.Runtime.InteropServices;
using System.Text;

namespace OpenGamepad.Companion;

/// <summary>
/// Minimal /dev/uinput wrapper (Linux 4.5+ setup API). Struct sizes are for 64-bit kernels.
/// </summary>
internal sealed class Uinput : IDisposable
{
    public const ushort EvSyn = 0x00, EvKey = 0x01, EvAbs = 0x03, EvFf = 0x15, EvUinput = 0x0101;
    public const ushort FfRumble = 0x50;
    public const ushort UiFfUpload = 1, UiFfErase = 2;

    private const uint UiDevCreate = 0x5501;
    private const uint UiDevDestroy = 0x5502;
    private const uint UiDevSetup = 0x405C5503;
    private const uint UiAbsSetup = 0x401C5504;
    private const uint UiSetEvBit = 0x40045564;
    private const uint UiSetKeyBit = 0x40045565;
    private const uint UiSetAbsBit = 0x40045567;
    private const uint UiSetFfBit = 0x4004556B;
    private const uint UiBeginFfUpload = 0xC06855C8;
    private const uint UiEndFfUpload = 0x406855C9;
    private const uint UiBeginFfErase = 0xC00C55CA;
    private const uint UiEndFfErase = 0x400C55CB;

    private const int ORdWr = 2, ONonBlock = 0x800;
    private const int EventSize = 24; // struct input_event: timeval + u16 type + u16 code + s32 value
    private const int FfUploadSize = 104; // struct uinput_ff_upload
    private const int FfEraseSize = 12; // struct uinput_ff_erase

    private readonly int _fd;
    private bool _created;

    public Uinput()
    {
        _fd = open("/dev/uinput", ORdWr | ONonBlock);
        if (_fd < 0)
            throw new IOException(
                L.T($"/dev/uinput non accessibile (errno {Marshal.GetLastPInvokeError()}): " +
                    "carica il modulo uinput e installa la regola udev (vedi README)",
                    $"/dev/uinput not accessible (errno {Marshal.GetLastPInvokeError()}): " +
                    "load the uinput module and install the udev rule (see README)", $"无法访问 /dev/uinput（errno {Marshal.GetLastPInvokeError()}）：请加载 uinput 模块并安装 udev 规则（见 README）", $"無法存取 /dev/uinput（errno {Marshal.GetLastPInvokeError()}）：請載入 uinput 模組並安裝 udev 規則（見 README）", $"/dev/uinput にアクセスできません（errno {Marshal.GetLastPInvokeError()}）：uinput モジュールを読み込み、udev ルールをインストールしてください（README 参照）"));
    }

    public void EnableEvent(ushort type) => Check(ioctl(_fd, UiSetEvBit, type), "UI_SET_EVBIT");
    public void EnableKey(int code) => Check(ioctl(_fd, UiSetKeyBit, code), "UI_SET_KEYBIT");
    public void EnableFf(int code) => Check(ioctl(_fd, UiSetFfBit, code), "UI_SET_FFBIT");

    public unsafe void EnableAbs(ushort code, int min, int max, int fuzz = 0, int flat = 0)
    {
        Check(ioctl(_fd, UiSetAbsBit, code), "UI_SET_ABSBIT");
        var setup = stackalloc byte[28];
        new Span<byte>(setup, 28).Clear();
        *(ushort*)setup = code;
        var info = (int*)(setup + 4); // value, minimum, maximum, fuzz, flat, resolution
        info[1] = min;
        info[2] = max;
        info[3] = fuzz;
        info[4] = flat;
        Check(ioctl(_fd, UiAbsSetup, (nint)setup), "UI_ABS_SETUP");
    }

    public unsafe void Create(string name, ushort vendor, ushort product, ushort version, uint ffEffects = 0)
    {
        var setup = stackalloc byte[92]; // input_id (8) + name[80] + ff_effects_max
        new Span<byte>(setup, 92).Clear();
        var id = (ushort*)setup;
        id[0] = 0x03; // BUS_USB
        id[1] = vendor;
        id[2] = product;
        id[3] = version;
        var bytes = Encoding.ASCII.GetBytes(name);
        bytes.AsSpan(0, Math.Min(bytes.Length, 79)).CopyTo(new Span<byte>(setup + 8, 79));
        *(uint*)(setup + 88) = ffEffects;
        Check(ioctl(_fd, UiDevSetup, (nint)setup), "UI_DEV_SETUP");
        Check(ioctl(_fd, UiDevCreate, 0), "UI_DEV_CREATE");
        _created = true;
    }

    public unsafe void Emit(ushort type, ushort code, int value)
    {
        var ev = stackalloc byte[EventSize];
        new Span<byte>(ev, EventSize).Clear();
        *(ushort*)(ev + 16) = type;
        *(ushort*)(ev + 18) = code;
        *(int*)(ev + 20) = value;
        write(_fd, (nint)ev, EventSize);
    }

    public void Sync() => Emit(EvSyn, 0, 0);

    /// <summary>Next pending event, or false after <paramref name="timeoutMs"/>.</summary>
    public unsafe bool Read(int timeoutMs, out ushort type, out ushort code, out int value)
    {
        type = code = 0;
        value = 0;
        var pfd = stackalloc int[2];
        pfd[0] = _fd;
        pfd[1] = 1; // events = POLLIN, revents = 0
        if (poll((nint)pfd, 1, timeoutMs) <= 0) return false;
        var ev = stackalloc byte[EventSize];
        if (read(_fd, (nint)ev, EventSize) != EventSize) return false;
        type = *(ushort*)(ev + 16);
        code = *(ushort*)(ev + 18);
        value = *(int*)(ev + 20);
        return true;
    }

    /// <summary>
    /// Completes a force-feedback upload request; returns the effect id and, for rumble
    /// effects, the strong/weak magnitudes (0-65535).
    /// </summary>
    public unsafe (short Id, ushort Strong, ushort Weak)? AcceptUpload(int requestId)
    {
        var upload = stackalloc byte[FfUploadSize];
        new Span<byte>(upload, FfUploadSize).Clear();
        *(uint*)upload = (uint)requestId;
        if (ioctl(_fd, UiBeginFfUpload, (nint)upload) < 0) return null;
        var effect = upload + 8; // struct ff_effect: type, id, direction, trigger, replay, union @16
        var result = (*(short*)(effect + 2),
            *(ushort*)effect == FfRumble ? *(ushort*)(effect + 16) : (ushort)0,
            *(ushort*)effect == FfRumble ? *(ushort*)(effect + 18) : (ushort)0);
        *(int*)(upload + 4) = 0; // retval
        ioctl(_fd, UiEndFfUpload, (nint)upload);
        return result;
    }

    public unsafe void AcceptErase(int requestId)
    {
        var erase = stackalloc byte[FfEraseSize];
        new Span<byte>(erase, FfEraseSize).Clear();
        *(uint*)erase = (uint)requestId;
        if (ioctl(_fd, UiBeginFfErase, (nint)erase) < 0) return;
        *(int*)(erase + 4) = 0;
        ioctl(_fd, UiEndFfErase, (nint)erase);
    }

    public void Dispose()
    {
        if (_created) ioctl(_fd, UiDevDestroy, 0);
        close(_fd);
    }

    private static void Check(int result, string what)
    {
        if (result < 0) throw new IOException(L.T($"{what} fallito (errno {Marshal.GetLastPInvokeError()})", $"{what} failed (errno {Marshal.GetLastPInvokeError()})", $"{what} 失败（errno {Marshal.GetLastPInvokeError()}）", $"{what} 失敗（errno {Marshal.GetLastPInvokeError()}）", $"{what} に失敗（errno {Marshal.GetLastPInvokeError()}）"));
    }

    [DllImport("libc", SetLastError = true)] private static extern int open(string path, int flags);
    [DllImport("libc", SetLastError = true)] private static extern int close(int fd);
    [DllImport("libc", SetLastError = true)] private static extern nint write(int fd, nint buffer, nint count);
    [DllImport("libc", SetLastError = true)] private static extern nint read(int fd, nint buffer, nint count);
    [DllImport("libc", SetLastError = true)] private static extern int poll(nint fds, nuint count, int timeout);
    [DllImport("libc", SetLastError = true)] private static extern int ioctl(int fd, nuint request, nint argument);
}
