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
            Console.Error.WriteLine(e.Message);
            Console.Error.WriteLine("  sudo modprobe uinput");
            Console.Error.WriteLine("  sudo cp 70-opengamepad-uinput.rules /etc/udev/rules.d/ && sudo udevadm control --reload && sudo udevadm trigger");
            Console.Error.WriteLine("  sudo usermod -aG input $USER   (poi esci e rientra)");
            return 1;
        }

        using var server = new CompanionServer(new LinuxPlatform());
        using var adb = new AdbBridge(server.Port);

        var addresses = CompanionServer.LocalAddresses();
        var url = $"opengamepad://connect?c={string.Join("_", addresses)}_{server.Port}";
        Console.WriteLine("OpenGamepad Companion per Linux");
        Console.WriteLine();
        using (var qr = QRCodeGenerator.GenerateQrCode(url, QRCodeGenerator.ECCLevel.M))
            Console.WriteLine(new AsciiQRCode(qr).GetGraphicSmall());
        Console.WriteLine("Nell'app: \"Scansiona QR\", \"Cerca\" oppure inserisci " +
                          string.Join(" / ", addresses.Select(a => $"{a}:{server.Port}")));
        Console.WriteLine(adb.Status);
        Console.WriteLine("Ctrl+C per uscire.");
        Console.WriteLine();

        Log.Written += Console.WriteLine;
        adb.StatusChanged += () => Console.WriteLine(adb.Status);
        Log.Write($"In ascolto sulla porta {server.Port} ({string.Join(", ", addresses)})");

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
}
