using System.Collections.Concurrent;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using Nefarius.ViGEm.Client;

namespace OpenGamepad.Companion;

/// <summary>Accepts phones over Wi-Fi and USB (adb reverse) and gives each one a virtual pad.</summary>
public sealed class CompanionServer : IDisposable
{
    public const int PreferredPort = 47800;

    private readonly ViGEmClient _vigem;
    private readonly TcpListener _listener;
    private readonly ConcurrentDictionary<PhoneSession, byte> _sessions = new();
    private readonly CancellationTokenSource _cts = new();

    public int Port { get; }
    public event Action? SessionsChanged;
    public IReadOnlyCollection<PhoneSession> Sessions => _sessions.Keys.ToList();

    public CompanionServer(ViGEmClient vigem)
    {
        _vigem = vigem;
        _listener = Bind(PreferredPort) ?? Bind(0)!;
        Port = ((IPEndPoint)_listener.LocalEndpoint).Port;
        _ = AcceptLoopAsync();
    }

    private static TcpListener? Bind(int port)
    {
        try
        {
            var listener = new TcpListener(IPAddress.IPv6Any, port);
            listener.Server.DualMode = true;
            listener.Start();
            return listener;
        }
        catch (SocketException)
        {
            return null;
        }
    }

    private async Task AcceptLoopAsync()
    {
        while (!_cts.IsCancellationRequested)
        {
            TcpClient client;
            try { client = await _listener.AcceptTcpClientAsync(_cts.Token); }
            catch (Exception e) when (e is OperationCanceledException or ObjectDisposedException or SocketException) { return; }

            var session = new PhoneSession(client, _vigem);
            _sessions[session] = 0;
            session.Changed += _ => SessionsChanged?.Invoke();
            session.Closed += s =>
            {
                _sessions.TryRemove(s, out _);
                Log.Write($"Disconnesso: {s.Describe()}");
                SessionsChanged?.Invoke();
            };
            Log.Write($"Connessione da {session.Remote}");
            _ = session.RunAsync();
        }
    }

    /// <summary>Private IPv4 addresses of active network adapters, Wi-Fi/Ethernet first.</summary>
    public static List<string> LocalAddresses() =>
        NetworkInterface.GetAllNetworkInterfaces()
            .Where(n => n.OperationalStatus == OperationalStatus.Up &&
                        n.NetworkInterfaceType is not NetworkInterfaceType.Loopback and not NetworkInterfaceType.Tunnel)
            .OrderBy(n => n.NetworkInterfaceType == NetworkInterfaceType.Wireless80211 ? 0 :
                          n.NetworkInterfaceType == NetworkInterfaceType.Ethernet ? 1 : 2)
            .SelectMany(n => n.GetIPProperties().UnicastAddresses)
            .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork && IsPrivate(a.Address))
            .Select(a => a.Address.ToString())
            .Distinct()
            .ToList();

    private static bool IsPrivate(IPAddress address)
    {
        var b = address.GetAddressBytes();
        return b[0] == 10 || (b[0] == 172 && b[1] is >= 16 and <= 31) || (b[0] == 192 && b[1] == 168);
    }

    public void Dispose()
    {
        _cts.Cancel();
        _listener.Stop();
        foreach (var session in _sessions.Keys) session.Dispose();
    }
}
