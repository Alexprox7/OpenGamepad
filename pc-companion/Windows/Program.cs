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
                L.T("Il driver ViGEmBus non è installato.\n\nServe per creare il controller Xbox virtuale: " +
                    "installalo da https://github.com/nefarius/ViGEmBus/releases e riavvia il companion.",
                    "The ViGEmBus driver is not installed.\n\nIt creates the virtual Xbox controller: " +
                    "install it from https://github.com/nefarius/ViGEmBus/releases and restart the companion.", "未安装 ViGEmBus 驱动。\n\n它用于创建虚拟 Xbox 控制器：请从 https://github.com/nefarius/ViGEmBus/releases 安装，然后重新启动伴侣程序。", "未安裝 ViGEmBus 驅動程式。\n\n它用於建立虛擬 Xbox 控制器：請從 https://github.com/nefarius/ViGEmBus/releases 安裝，然後重新啟動伴侶程式。", "ViGEmBus ドライバーがインストールされていません。\n\n仮想 Xbox コントローラーの作成に必要です：https://github.com/nefarius/ViGEmBus/releases からインストールし、コンパニオンを再起動してください。"),
                "OpenGamepad Companion", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return;
        }

        using (vigem)
        using (var server = new CompanionServer(new WindowsPlatform(vigem)))
        using (var adb = new AdbBridge(server.Port))
        {
            Application.Run(new MainForm(server, adb));
        }
    }
}
