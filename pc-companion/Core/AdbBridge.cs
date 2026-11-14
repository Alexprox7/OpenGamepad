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
            Status = L.T("ADB non trovato: USB non disponibile (installa Android platform-tools)",
                "ADB not found: USB unavailable (install Android platform-tools)", "未找到 ADB：USB 不可用（请安装 Android platform-tools）", "找不到 ADB：無法使用 USB（請安裝 Android platform-tools）", "ADB が見つかりません：USB は使えません（Android platform-tools をインストールしてください）");
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
            if (_reversed.Add(serial)) Log.Write(L.T($"USB pronto su {serial}", $"USB ready on {serial}", $"USB 已就绪：{serial}", $"USB 已就緒：{serial}", $"USB 準備完了：{serial}"));
            else Log.Write(L.T($"USB ripristinato su {serial}", $"USB restored on {serial}", $"USB 已恢复：{serial}", $"USB 已恢復：{serial}", $"USB を復旧：{serial}"));
        }

        var unauthorized = devices.Count(p => p[1] == "unauthorized");
        SetStatus(ready.Count switch
        {
            0 when unauthorized > 0 => L.T("USB: autorizza il debug USB sul telefono", "USB: allow USB debugging on the phone", "USB：请在手机上允许 USB 调试", "USB：請在手機上允許 USB 偵錯", "USB：スマホで USB デバッグを許可してください"),
            0 => L.T("USB: nessun telefono collegato", "USB: no phone connected", "USB：未连接手机", "USB：未連接手機", "USB：スマホが接続されていません"),
            _ => L.T($"USB pronto: {string.Join(", ", ready)} → nell'app tocca \"USB\"",
                $"USB ready: {string.Join(", ", ready)} → tap \"USB\" in the app", $"USB 已就绪：{string.Join(", ", ready)} → 在应用中点按“USB”", $"USB 已就緒：{string.Join(", ", ready)} → 在 App 中點按「USB」", $"USB 準備完了：{string.Join(", ", ready)} → アプリで「USB」をタップ"),
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
        var exe = OperatingSystem.IsWindows() ? "adb.exe" : "adb";
        var candidates = new List<string>
        {
            Path.Combine(AppContext.BaseDirectory, "adb", exe),
            Path.Combine(AppContext.BaseDirectory, exe),
        };
        candidates.AddRange((Environment.GetEnvironmentVariable("PATH") ?? "")
            .Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries)
            .Select(dir => Path.Combine(dir.Trim(), exe)));
        foreach (var sdk in new[] { "ANDROID_HOME", "ANDROID_SDK_ROOT" }.Select(Environment.GetEnvironmentVariable))
            if (!string.IsNullOrEmpty(sdk)) candidates.Add(Path.Combine(sdk, "platform-tools", exe));
        candidates.Add(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Android", "Sdk", "platform-tools", exe));
        candidates.Add(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            "Android", "Sdk", "platform-tools", exe));
        if (OperatingSystem.IsWindows()) candidates.Add(@"C:\platform-tools\adb.exe");
        return candidates.FirstOrDefault(File.Exists);
    }

    public void Dispose() => _cts.Cancel();
}
