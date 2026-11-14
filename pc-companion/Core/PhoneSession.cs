using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;

namespace OpenGamepad.Companion;

/// <summary>
/// One connected phone: TCP control channel, optional UDP channel for states,
/// and its own virtual controller from the platform backend.
/// </summary>
public sealed class PhoneSession : IDisposable
{
    private readonly TcpClient _tcp;
    private readonly NetworkStream _stream;
    private readonly IPlatform _platform;
    private readonly CancellationTokenSource _cts = new();
    private readonly SemaphoreSlim _sendLock = new(1, 1);
    private UdpClient? _udp;
    private IVirtualPad? _pad;
    private readonly IKeyboardOutput _keyboard;
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

    public PhoneSession(TcpClient tcp, IPlatform platform)
    {
        _tcp = tcp;
        _tcp.NoDelay = true;
        _stream = tcp.GetStream();
        _platform = platform;
        _keyboard = platform.CreateKeyboard();
        var remote = (IPEndPoint)tcp.Client.RemoteEndPoint!;
        // The listener is dual-stack: IPv4 peers show up as ::ffff:a.b.c.d.
        Remote = remote.Address.IsIPv4MappedToIPv6 ? new IPEndPoint(remote.Address.MapToIPv4(), remote.Port) : remote;
    }

    public string Describe() =>
        $"{(IsUsb ? "USB" : Remote.Address.ToString())} · id {DeviceId:X16}" +
        (PlayerIndex >= 0 ? L.T($" · giocatore {PlayerIndex + 1}", $" · player {PlayerIndex + 1}", $" · 玩家 {PlayerIndex + 1}", $" · 玩家 {PlayerIndex + 1}", $" · プレイヤー {PlayerIndex + 1}") : "") +
        (IsOpenGamepad ? "" : L.T(" · app originale", " · original app", " · 原版应用", " · 原版 App", " · 元のアプリ"));

    public async Task RunAsync()
    {
        try
        {
            var hello = await ReadMessageAsync(_cts.Token);
            if (hello is not { Type: Protocol.TypeHello } || hello.Value.Payload.Length < 8)
                throw new IOException(L.T("handshake non valido", "invalid handshake", "握手无效", "握手無效", "無効なハンドシェイク"));
            var payload = hello.Value.Payload;
            DeviceId = BinaryPrimitives.ReadUInt64BigEndian(payload);
            IsOpenGamepad = payload.Length >= 11 && payload.AsSpan(8, 3).SequenceEqual(Protocol.ExtensionMagic);

            _pad = _platform.CreatePad();
            _pad.Feedback += OnFeedback;

            // UDP only makes sense over the network; over USB (adb reverse) everything uses TCP.
            ushort udpPort = 0;
            if (!IsUsb)
            {
                _udp = new UdpClient(new IPEndPoint(IPAddress.Any, 0));
                udpPort = (ushort)((IPEndPoint)_udp.Client.LocalEndPoint!).Port;
                _ = ReceiveUdpAsync(_udp, _cts.Token);
            }

            var reply = new List<byte>();
            reply.Add((byte)(udpPort & 0xFF));
            reply.Add((byte)(udpPort >> 8));
            reply.Add(_platform.OsCode);
            if (IsOpenGamepad)
            {
                reply.AddRange(Protocol.ExtensionMagic);
                reply.Add(Protocol.ExtensionVersion);
            }
            await SendAsync(Protocol.Frame(Protocol.TypeHello, reply.ToArray()));
            _handshakeDone = true;
            // The pad may report LED/rumble before the reply is out: deliver it now.
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
        catch (Exception e) when (e is not OutOfMemoryException)
        {
            Log.Write(L.T($"Controller virtuale: {e.Message}", $"Virtual controller: {e.Message}", $"虚拟控制器：{e.Message}", $"虛擬控制器：{e.Message}", $"仮想コントローラー：{e.Message}"));
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
        // Everything else is ignored, including motion (TypeMotion): the virtual pads have no gyro.
        if (message.Type != Protocol.TypeState || _pad is null) return;
        if (!ControllerState.TryDecode(message.Payload, out var s)) return;
        LastInput = DateTime.Now;
        lock (_pad) _pad.Update(s);
    }

    private void OnFeedback(byte largeMotor, byte smallMotor, byte led)
    {
        // 0-3 are XInput slots; backends without player numbers (uinput) send 255.
        if (led < 16) PlayerIndex = led;
        Changed?.Invoke(this);
        var packet = Protocol.Frame(Protocol.TypeFeedback, [largeMotor, smallMotor, led]);
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
        if (length < Protocol.RxHeaderSize) throw new IOException(L.T($"pacchetto non valido ({length})", $"invalid packet ({length})", $"数据包无效（{length}）", $"封包無效（{length}）", $"無効なパケット（{length}）"));
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
        try { _pad?.Dispose(); } catch { /* already gone */ }
        _udp?.Dispose();
        _tcp.Dispose();
    }
}
