using QRCoder;

namespace OpenGamepad.Companion;

internal static class Program
{
    private static int Main()
    {
        Console.OutputEncoding = System.Text.Encoding.UTF8;
        try
        {
            LinuxPlatform.CheckAccess();
        }
        catch (IOException e)
        {
            Console.Error.WriteLine(Paint(Red, e.Message));
            Console.Error.WriteLine();
            Console.Error.WriteLine(L.T("Per dare accesso a uinput:", "To give access to uinput:", "授予 uinput 访问权限：", "授予 uinput 存取權限：", "uinput へのアクセスを許可するには："));
            Console.Error.WriteLine("  sudo modprobe uinput");
            Console.Error.WriteLine("  sudo cp 70-opengamepad-uinput.rules /etc/udev/rules.d/ && sudo udevadm control --reload && sudo udevadm trigger");
            Console.Error.WriteLine(L.T("  sudo usermod -aG input $USER   (poi esci e rientra)", "  sudo usermod -aG input $USER   (then log out and back in)", "  sudo usermod -aG input $USER   （然后注销并重新登录）", "  sudo usermod -aG input $USER   （然後登出並重新登入）", "  sudo usermod -aG input $USER   （その後ログアウトして再ログイン）"));
            return 1;
        }

        using var server = new CompanionServer(new LinuxPlatform());
        using var adb = new AdbBridge(server.Port);

        var addresses = CompanionServer.LocalAddresses();
        var url = $"opengamepad://connect?c={string.Join("_", addresses)}_{server.Port}";
        var version = typeof(Program).Assembly.GetName().Version?.ToString(3) ?? "";
        Console.WriteLine(Paint(Bold, $"OpenGamepad Companion {version}") + Paint(Dim, " · Linux"));
        Console.WriteLine();
        using (var qr = QRCodeGenerator.GenerateQrCode(url, QRCodeGenerator.ECCLevel.M))
            Console.WriteLine(new AsciiQRCode(qr).GetGraphicSmall());
        Console.WriteLine(Paint(Accent, "Wi‑Fi ") + L.T("Nell'app: \"Scansiona QR\", \"Cerca\" oppure inserisci ", "In the app: \"Scan QR\", \"Search\" or enter ", "在应用中：“扫描二维码”、“搜索”或输入 ", "在 App 中：「掃描 QR 碼」、「搜尋」或輸入 ", "アプリで「QR を読み取る」「検索」、または入力 ") +
                          string.Join(" / ", addresses.Select(a => Paint(Bold, $"{a}:{server.Port}"))));
        Console.WriteLine(Paint(Accent, "USB   ") + adb.Status);
        Console.WriteLine(Paint(Dim, L.T("Ctrl+C per uscire.", "Ctrl+C to quit.", "按 Ctrl+C 退出。", "按 Ctrl+C 結束。", "Ctrl+C で終了。")));
        Console.WriteLine();

        Log.Written += Console.WriteLine;
        adb.StatusChanged += () => Console.WriteLine(Paint(Accent, "USB   ") + adb.Status);
        Log.Write(L.T($"In ascolto sulla porta {server.Port} ({string.Join(", ", addresses)})",
                      $"Listening on port {server.Port} ({string.Join(", ", addresses)})", $"正在监听端口 {server.Port}（{string.Join(", ", addresses)}）", $"正在監聽連接埠 {server.Port}（{string.Join(", ", addresses)}）", $"ポート {server.Port} で待機中（{string.Join(", ", addresses)}）"));

        var exit = new ManualResetEventSlim();
        Console.CancelKeyPress += (_, e) =>
        {
            e.Cancel = true;
            exit.Set();
        };
        AppDomain.CurrentDomain.ProcessExit += (_, _) => exit.Set();
        exit.Wait();
        return 0;
    }

    // Colours only on a terminal: piped output and log files stay plain.
    private static readonly bool Ansi = !Console.IsOutputRedirected && Environment.GetEnvironmentVariable("NO_COLOR") == null;
    private const string Bold = "1", Dim = "2", Red = "31", Accent = "38;5;111";
    private static string Paint(string style, string text) => Ansi ? $"\u001b[{style}m{text}\u001b[0m" : text;
}
