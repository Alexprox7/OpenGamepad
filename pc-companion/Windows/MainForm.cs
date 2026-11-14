using System.Runtime.InteropServices;
using QRCoder;

namespace OpenGamepad.Companion;

public sealed class MainForm : Form
{
    // Same palette as the app's dark theme.
    private static readonly Color Background = Color.FromArgb(19, 21, 25);
    private static readonly Color Surface = Color.FromArgb(27, 30, 36);
    private static readonly Color Outline = Color.FromArgb(47, 52, 62);
    private static readonly Color Foreground = Color.FromArgb(236, 238, 242);
    private static readonly Color Muted = Color.FromArgb(155, 162, 175);
    private static readonly Color Accent = Color.FromArgb(124, 156, 255);
    private static readonly Color Ok = Color.FromArgb(74, 222, 128);

    private readonly CompanionServer _server;
    private readonly AdbBridge _adb;
    private readonly ListBox _sessions = new();
    private readonly TextBox _log = new();
    private readonly Label _usb = new();
    private readonly Label _count = new();

    public MainForm(CompanionServer server, AdbBridge adb)
    {
        _server = server;
        _adb = adb;

        AutoScaleDimensions = new SizeF(96f, 96f);
        AutoScaleMode = AutoScaleMode.Dpi;
        Text = $"OpenGamepad Companion {Application.ProductVersion.Split('+')[0]}";
        Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);
        BackColor = Background;
        ForeColor = Foreground;
        Font = new Font("Segoe UI", 10f);
        ClientSize = new Size(880, 560);
        MinimumSize = new Size(800, 520);

        var addresses = CompanionServer.LocalAddresses();
        var target = $"{string.Join("_", addresses)}_{server.Port}";

        // Left: how to connect.
        var connect = Panel(new Point(20, 20), new Size(320, 520), AnchorStyles.Top | AnchorStyles.Bottom | AnchorStyles.Left);
        var title = new Label
        {
            Text = L.T("Collega il telefono", "Connect your phone", "连接手机", "連接手機", "スマホを接続"),
            Font = new Font("Segoe UI Semibold", 15f),
            Location = new Point(18, 16),
            AutoSize = true,
        };
        // White frame: phone cameras read a QR code better with a quiet zone around it.
        var qrFrame = new Panel { BackColor = Color.White, Location = new Point(18, 60), Size = new Size(284, 284), Padding = new Padding(10) };
        qrFrame.Controls.Add(new PictureBox
        {
            Image = QrImage($"opengamepad://connect?c={target}"),
            SizeMode = PictureBoxSizeMode.Zoom,
            Dock = DockStyle.Fill,
            BackColor = Color.White,
        });
        var wifi = Caption("WI‑FI", new Point(18, 358));
        var manual = new Label
        {
            Text = L.T("Nell'app: \"Scansiona QR\" o \"Cerca\", oppure inserisci\n",
                       "In the app: \"Scan QR\" or \"Search\", or enter\n", "在应用中：点“扫描二维码”或“搜索”，或输入\n", "在 App 中：點「掃描 QR 碼」或「搜尋」，或輸入\n", "アプリで「QR を読み取る」か「検索」、または次を入力\n") +
                   string.Join("\n", addresses.Select(a => $"{a}:{server.Port}")),
            ForeColor = Muted,
            Location = new Point(18, 380),
            Size = new Size(290, 70),
        };
        var usb = Caption("USB", new Point(18, 452));
        _usb.ForeColor = Muted;
        _usb.Location = new Point(18, 474);
        _usb.Size = new Size(290, 40);
        connect.Controls.AddRange([title, qrFrame, wifi, manual, usb, _usb]);

        // Right: connected phones and log.
        var right = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
        var devices = Panel(new Point(356, 20), new Size(504, 190), right);
        var devicesTitle = new Label
        {
            Text = L.T("Dispositivi collegati", "Connected devices", "已连接的设备", "已連接的裝置", "接続中の機器"),
            Font = new Font("Segoe UI Semibold", 12f),
            Location = new Point(16, 14),
            AutoSize = true,
        };
        _count.ForeColor = Muted;
        _count.AutoSize = true;
        _count.Location = new Point(16, 40);
        _sessions.BackColor = Surface;
        _sessions.ForeColor = Foreground;
        _sessions.BorderStyle = BorderStyle.None;
        _sessions.Font = new Font("Segoe UI", 10.5f);
        _sessions.ItemHeight = 26;
        _sessions.Location = new Point(16, 68);
        _sessions.Size = new Size(472, 108);
        _sessions.Anchor = right;
        _sessions.TabStop = false; // no focus rectangle on a read-only list
        devices.Controls.AddRange([devicesTitle, _count, _sessions]);

        var logPanel = Panel(new Point(356, 226), new Size(504, 314), AnchorStyles.Top | AnchorStyles.Bottom | AnchorStyles.Left | AnchorStyles.Right);
        var logTitle = Caption(L.T("REGISTRO", "LOG", "日志", "記錄", "ログ"), new Point(16, 14));
        _log.Multiline = true;
        _log.ReadOnly = true;
        _log.ScrollBars = ScrollBars.Vertical;
        _log.BackColor = Surface;
        _log.ForeColor = Muted;
        _log.BorderStyle = BorderStyle.None;
        _log.Font = new Font("Consolas", 9.5f);
        _log.Location = new Point(16, 40);
        _log.Size = new Size(472, 260);
        _log.Anchor = AnchorStyles.Top | AnchorStyles.Bottom | AnchorStyles.Left | AnchorStyles.Right;
        logPanel.Controls.AddRange([logTitle, _log]);

        Controls.AddRange([connect, devices, logPanel]);

        server.SessionsChanged += () => UiThread(RefreshSessions);
        adb.StatusChanged += () => UiThread(() => _usb.Text = _adb.Status);
        Log.Written += line => UiThread(() => _log.AppendText(line + Environment.NewLine));
        _usb.Text = adb.Status;
        RefreshSessions();
        Log.Write(L.T($"In ascolto sulla porta {server.Port} ({string.Join(", ", addresses)})",
                      $"Listening on port {server.Port} ({string.Join(", ", addresses)})", $"正在监听端口 {server.Port}（{string.Join(", ", addresses)}）", $"正在監聽連接埠 {server.Port}（{string.Join(", ", addresses)}）", $"ポート {server.Port} で待機中（{string.Join(", ", addresses)}）"));
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        // Dark title bar on Windows 10 2004+ and 11; ignored elsewhere.
        var on = 1;
        _ = DwmSetWindowAttribute(Handle, 20, ref on, sizeof(int));
    }

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);

    /// <summary>Card: surface fill with a 1 px outline.</summary>
    private static Panel Panel(Point location, Size size, AnchorStyles anchor)
    {
        var panel = new Panel { BackColor = Surface, Location = location, Size = size, Anchor = anchor };
        panel.Paint += (_, e) =>
        {
            using var pen = new Pen(Outline);
            e.Graphics.DrawRectangle(pen, 0, 0, panel.Width - 1, panel.Height - 1);
        };
        return panel;
    }

    private static Label Caption(string text, Point location) => new()
    {
        Text = text,
        ForeColor = Accent,
        Font = new Font("Segoe UI Semibold", 8.5f),
        Location = location,
        AutoSize = true,
    };

    private void RefreshSessions()
    {
        _sessions.Items.Clear();
        foreach (var session in _server.Sessions) _sessions.Items.Add("●  " + session.Describe());
        var count = _server.Sessions.Count();
        _count.Text = count switch
        {
            0 => L.T("Nessun dispositivo: collega il telefono dall'app", "No devices: connect your phone from the app", "没有设备：请在应用中连接手机", "沒有裝置：請在 App 中連接手機", "機器なし：アプリからスマホを接続してください"),
            1 => L.T("1 telefono", "1 phone", "1 部手机", "1 支手機", "スマホ 1 台"),
            _ => L.T($"{count} telefoni", $"{count} phones", $"{count} 部手机", $"{count} 支手機", $"スマホ {count} 台"),
        };
        _count.ForeColor = count == 0 ? Muted : Ok;
    }

    private void UiThread(Action action)
    {
        if (IsDisposed) return;
        if (InvokeRequired) BeginInvoke(action);
        else action();
    }

    private static Image QrImage(string text)
    {
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(text, QRCodeGenerator.ECCLevel.M);
        var png = new PngByteQRCode(data).GetGraphic(10);
        return Image.FromStream(new MemoryStream(png));
    }
}
