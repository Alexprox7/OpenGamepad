using System.Diagnostics;

namespace OpenGamepad.Companion;

/// <summary>
/// USB support: for every phone authorized over ADB, maps the phone's
/// 127.0.0.1:port to this PC with "adb reverse", so the app connects over the cable.
/// </summary>
public sealed class AdbBridge : IDisposable
{
    private readonly int _port;
    private readonly HashSet<string> _reversed = [];
    private readonly CancellationTokenSource _cts = new();

    public string? AdbPath { get; } = FindAdb();
    public string Status { get; private set; } = "";
    public event Action? StatusChanged;

    public AdbBridge(int port)
    {
        _port = port;
        if (AdbPath is null)
        {
            Status = "ADB non trovato: USB non disponibile (installa Android platform-tools)";
            return;
        }
        _ = PollAsync();
    }

    private async Task PollAsync()
    {
        while (!_cts.IsCancellationRequested)
        {
            try { await RefreshAsync(); }
            catch (Exception e) { SetStatus($"ADB: {e.Message}"); }
            try { await Task.Delay(2000, _cts.Token); }
            catch (OperationCanceledException) { return; }
        }
    }

    private async Task RefreshAsync()
    {
        var output = await RunAsync("devices");
        var devices = output.Split('\n')
            .Select(l => l.Trim().Split('\t'))
            .Where(p => p.Length == 2)
            .ToList();
        var ready = devices.Where(p => p[1] == "device").Select(p => p[0]).ToList();
        _reversed.IntersectWith(ready);

        foreach (var serial in ready)
        {
            // The rule disappears when ADB or the cable reconnects: check it every time.
            var rules = await RunAsync($"-s {serial} reverse --list");
            if (rules.Contains($"tcp:{_port} tcp:{_port}")) continue;
            await RunAsync($"-s {serial} reverse tcp:{_port} tcp:{_port}");
            if (_reversed.Add(serial)) Log.Write($"USB pronto su {serial}");
            else Log.Write($"USB ripristinato su {serial}");
        }

        var unauthorized = devices.Count(p => p[1] == "unauthorized");
        SetStatus(ready.Count switch
        {
            0 when unauthorized > 0 => "USB: autorizza il debug USB sul telefono",
            0 => "USB: nessun telefono collegato",
            _ => $"USB pronto: {string.Join(", ", ready)} → nell'app tocca \"USB\"",
        });
    }

    private void SetStatus(string status)
    {
        if (status == Status) return;
        Status = status;
        StatusChanged?.Invoke();
    }

    private async Task<string> RunAsync(string arguments)
    {
        using var process = Process.Start(new ProcessStartInfo(AdbPath!, arguments)
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        })!;
        var output = await process.StandardOutput.ReadToEndAsync(_cts.Token);
        await process.WaitForExitAsync(_cts.Token);
        return output;
    }

    private static string? FindAdb()
    {
        var candidates = new List<string>
        {
            Path.Combine(AppContext.BaseDirectory, "adb", "adb.exe"),
            Path.Combine(AppContext.BaseDirectory, "adb.exe"),
        };
        candidates.AddRange((Environment.GetEnvironmentVariable("PATH") ?? "")
            .Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries)
            .Select(dir => Path.Combine(dir.Trim(), "adb.exe")));
        foreach (var sdk in new[] { "ANDROID_HOME", "ANDROID_SDK_ROOT" }.Select(Environment.GetEnvironmentVariable))
            if (!string.IsNullOrEmpty(sdk)) candidates.Add(Path.Combine(sdk, "platform-tools", "adb.exe"));
        candidates.Add(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Android", "Sdk", "platform-tools", "adb.exe"));
        candidates.Add(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            "Android", "Sdk", "platform-tools", "adb.exe"));
        candidates.Add(@"C:\platform-tools\adb.exe");
        return candidates.FirstOrDefault(File.Exists);
    }

    public void Dispose() => _cts.Cancel();
}
