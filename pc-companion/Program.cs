using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Exceptions;

namespace OpenGamepad.Companion;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        ApplicationConfiguration.Initialize();

        ViGEmClient vigem;
        try
        {
            vigem = new ViGEmClient();
        }
        catch (VigemBusNotFoundException)
        {
            MessageBox.Show(
                "Il driver ViGEmBus non è installato.\n\nServe per creare il controller Xbox virtuale: " +
                "installalo da https://github.com/nefarius/ViGEmBus/releases e riavvia il companion.",
                "OpenGamepad Companion", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return;
        }

        using (vigem)
        using (var server = new CompanionServer(vigem))
        using (var adb = new AdbBridge(server.Port))
        {
            Application.Run(new MainForm(server, adb));
        }
    }
}
