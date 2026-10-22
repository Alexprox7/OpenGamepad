using QRCoder;

namespace OpenGamepad.Companion;

public sealed class MainForm : Form
{
    private static readonly Color Background = Color.Black;
    private static readonly Color Foreground = Color.FromArgb(220, 224, 232);
    private static readonly Color Muted = Color.FromArgb(130, 138, 152);

    private readonly CompanionServer _server;
    private readonly AdbBridge _adb;
    private readonly ListBox _sessions = new();
    private readonly TextBox _log = new();
    private readonly Label _usb = new();

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
        ClientSize = new Size(860, 520);
        MinimumSize = new Size(760, 480);

        var addresses = CompanionServer.LocalAddresses();
        var target = $"{string.Join("_", addresses)}_{server.Port}";

        var qr = new PictureBox
        {
            Image = QrImage($"opengamepad://connect?c={target}"),
            SizeMode = PictureBoxSizeMode.Zoom,
            Location = new Point(24, 64),
            Size = new Size(280, 280),
            BackColor = Color.White,
        };
        var title = new Label
        {
            Text = "Collega il telefono",
            Font = new Font("Segoe UI Semibold", 16f),
            Location = new Point(20, 18),
            AutoSize = true,
        };
        var manual = new Label
        {
            Text = "Wi‑Fi: scansiona il codice con OpenGamepad\noppure inserisci " +
                   string.Join(" / ", addresses.Select(a => $"{a}:{server.Port}")),
            ForeColor = Muted,
            Location = new Point(20, 352),
            Size = new Size(310, 72),
        };
        _usb.ForeColor = Muted;
        _usb.Location = new Point(20, 432);
        _usb.Size = new Size(300, 60);

        var devicesTitle = new Label
        {
            Text = "Dispositivi collegati",
            Font = new Font("Segoe UI Semibold", 12f),
            Location = new Point(340, 22),
            AutoSize = true,
        };
        _sessions.BackColor = Background;
        _sessions.ForeColor = Foreground;
        _sessions.BorderStyle = BorderStyle.FixedSingle;
        _sessions.Location = new Point(340, 64);
        _sessions.Size = new Size(496, 150);
        _sessions.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;

        _log.Multiline = true;
        _log.ReadOnly = true;
        _log.ScrollBars = ScrollBars.Vertical;
        _log.BackColor = Background;
        _log.ForeColor = Muted;
        _log.BorderStyle = BorderStyle.FixedSingle;
        _log.Font = new Font("Consolas", 9f);
        _log.Location = new Point(340, 230);
        _log.Size = new Size(496, 266);
        _log.Anchor = AnchorStyles.Top | AnchorStyles.Bottom | AnchorStyles.Left | AnchorStyles.Right;

        Controls.AddRange([title, qr, manual, _usb, devicesTitle, _sessions, _log]);

        server.SessionsChanged += () => UiThread(RefreshSessions);
        adb.StatusChanged += () => UiThread(() => _usb.Text = _adb.Status);
        Log.Written += line => UiThread(() => _log.AppendText(line + Environment.NewLine));
        _usb.Text = adb.Status;
        RefreshSessions();
        Log.Write($"In ascolto sulla porta {server.Port} ({string.Join(", ", addresses)})");
    }

    private void RefreshSessions()
    {
        _sessions.Items.Clear();
        foreach (var session in _server.Sessions) _sessions.Items.Add(session.Describe());
        if (_sessions.Items.Count == 0) _sessions.Items.Add("Nessun dispositivo");
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
