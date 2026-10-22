using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.Xbox360;

namespace OpenGamepad.Companion;

/// <summary>
/// One connected phone: TCP control channel, optional UDP channel for states,
/// and its own virtual Xbox 360 controller.
/// </summary>
public sealed class PhoneSession : IDisposable
{
    private readonly TcpClient _tcp;
    private readonly NetworkStream _stream;
    private readonly ViGEmClient _vigem;
    private readonly CancellationTokenSource _cts = new();
    private readonly SemaphoreSlim _sendLock = new(1, 1);
    private UdpClient? _udp;
    private IXbox360Controller? _pad;
    private readonly KeyboardInjector _keyboard = new();
    private volatile bool _handshakeDone;
    private byte[]? _pendingFeedback;

    public IPEndPoint Remote { get; }
    public bool IsUsb => IPAddress.IsLoopback(Remote.Address);
    public ulong DeviceId { get; private set; }
    public bool IsOpenGamepad { get; private set; }
    public int PlayerIndex { get; private set; } = -1;
    public DateTime LastInput { get; private set; } = DateTime.Now;

    public event Action<PhoneSession>? Closed;
    public event Action<PhoneSession>? Changed;

    public PhoneSession(TcpClient tcp, ViGEmClient vigem)
    {
        _tcp = tcp;
        _tcp.NoDelay = true;
        _stream = tcp.GetStream();
        _vigem = vigem;
        var remote = (IPEndPoint)tcp.Client.RemoteEndPoint!;
        // The listener is dual-stack: IPv4 peers show up as ::ffff:a.b.c.d.
        Remote = remote.Address.IsIPv4MappedToIPv6 ? new IPEndPoint(remote.Address.MapToIPv4(), remote.Port) : remote;
    }

    public string Describe() =>
        $"{(IsUsb ? "USB" : Remote.Address.ToString())} · id {DeviceId:X16}" +
        (PlayerIndex >= 0 ? $" · giocatore {PlayerIndex + 1}" : "") +
        (IsOpenGamepad ? "" : " · app originale");

    public async Task RunAsync()
    {
        try
        {
            var hello = await ReadMessageAsync(_cts.Token);
            if (hello is not { Type: Protocol.TypeHello } || hello.Value.Payload.Length < 8)
                throw new IOException("handshake non valido");
            var payload = hello.Value.Payload;
            DeviceId = BinaryPrimitives.ReadUInt64BigEndian(payload);
            IsOpenGamepad = payload.Length >= 11 && payload.AsSpan(8, 3).SequenceEqual(Protocol.ExtensionMagic);

            _pad = _vigem.CreateXbox360Controller();
            _pad.AutoSubmitReport = false;
            _pad.FeedbackReceived += OnFeedback;
            _pad.Connect();
            try { PlayerIndex = _pad.UserIndex; } catch { /* not assigned yet */ }

            // UDP only makes sense over the network; over USB (adb reverse) everything uses TCP.
            ushort udpPort = 0;
            if (!IsUsb)
            {
                _udp = new UdpClient(new IPEndPoint(IPAddress.Any, 0));
                udpPort = (ushort)((IPEndPoint)_udp.Client.LocalEndPoint!).Port;
                _ = ReceiveUdpAsync(_udp, _cts.Token);
            }

            var reply = new List<byte>();
            reply.AddRange(BitConverter.GetBytes(udpPort)); // little-endian on Windows
            reply.Add(Protocol.OsWindows);
            if (IsOpenGamepad)
            {
                reply.AddRange(Protocol.ExtensionMagic);
                reply.Add(Protocol.ExtensionVersion);
            }
            await SendAsync(Protocol.Frame(Protocol.TypeHello, reply.ToArray()));
            _handshakeDone = true;
            // ViGEm may report LED/rumble before the reply is out: deliver it now.
            if (Interlocked.Exchange(ref _pendingFeedback, null) is { } pending) await SendAsync(pending);
            Changed?.Invoke(this);

            while (!_cts.IsCancellationRequested)
            {
                var message = await ReadMessageAsync(_cts.Token);
                if (message is null) break;
                Handle(message.Value);
            }
        }
        catch (Exception e) when (e is IOException or SocketException or OperationCanceledException or ObjectDisposedException)
        {
            // connection closed
        }
        catch (Exception e) when (e.GetType().Namespace?.StartsWith("Nefarius") == true)
        {
            Log.Write($"ViGEm: {e.Message}");
        }
        finally
        {
            Dispose();
            Closed?.Invoke(this);
        }
    }

    private async Task ReceiveUdpAsync(UdpClient udp, CancellationToken token)
    {
        try
        {
            while (!token.IsCancellationRequested)
            {
                var result = await udp.ReceiveAsync(token);
                // Accept states only from the phone that opened the TCP session.
                if (!result.RemoteEndPoint.Address.Equals(Remote.Address)) continue;
                if (Protocol.TryParse(result.Buffer, out var message)) Handle(message);
            }
        }
        catch (Exception e) when (e is SocketException or OperationCanceledException or ObjectDisposedException)
        {
        }
    }

    private void Handle(PhoneMessage message)
    {
        if (message.Type == Protocol.TypeKeyboard && IsOpenGamepad)
        {
            _keyboard.Apply(message.Payload);
            return;
        }
        if (message.Type != Protocol.TypeState || _pad is null) return;
        if (!ControllerState.TryDecode(message.Payload, out var s)) return;
        LastInput = DateTime.Now;
        lock (_pad)
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
    }

    private void OnFeedback(object? sender, Xbox360FeedbackReceivedEventArgs e)
    {
        PlayerIndex = e.LedNumber;
        Changed?.Invoke(this);
        var packet = Protocol.Frame(Protocol.TypeFeedback, [e.LargeMotor, e.SmallMotor, e.LedNumber]);
        if (_handshakeDone) _ = SendAsync(packet);
        else _pendingFeedback = packet;
    }

    private async Task SendAsync(byte[] packet)
    {
        await _sendLock.WaitAsync();
        try { await _stream.WriteAsync(packet, _cts.Token); }
        catch (Exception e) when (e is IOException or ObjectDisposedException or OperationCanceledException) { }
        finally { _sendLock.Release(); }
    }

    private async Task<PhoneMessage?> ReadMessageAsync(CancellationToken token)
    {
        var lengthBytes = new byte[2];
        if (!await ReadExactlyAsync(lengthBytes, token)) return null;
        int length = BinaryPrimitives.ReadUInt16LittleEndian(lengthBytes);
        if (length < Protocol.RxHeaderSize) throw new IOException($"pacchetto non valido ({length})");
        var packet = new byte[length];
        lengthBytes.CopyTo(packet, 0);
        if (!await ReadExactlyAsync(packet.AsMemory(2), token)) return null;
        return Protocol.TryParse(packet, out var message) ? message : null;
    }

    private async Task<bool> ReadExactlyAsync(Memory<byte> buffer, CancellationToken token)
    {
        try
        {
            await _stream.ReadExactlyAsync(buffer, token);
            return true;
        }
        catch (EndOfStreamException)
        {
            return false;
        }
    }

    public void Dispose()
    {
        if (_cts.IsCancellationRequested) return;
        _cts.Cancel();
        _keyboard.ReleaseAll();
        try { _pad?.Disconnect(); } catch { /* already gone */ }
        _udp?.Dispose();
        _tcp.Dispose();
    }
}
