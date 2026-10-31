using System.Threading;

namespace ItemManagement.PrintBridge;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        using var singleInstance = new Mutex(true, @"Local\ItemManagementPrintBridge", out var isFirstInstance);
        if (!isFirstInstance)
        {
            MessageBox.Show(
                "Item Management Print Bridge is already running. Use its tray icon to copy the bridge key.",
                "Item Management Print Bridge",
                MessageBoxButtons.OK,
                MessageBoxIcon.Information);
            return;
        }

        ApplicationConfiguration.Initialize();
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        Application.ThreadException += (_, args) =>
        {
            Log.Write($"Unhandled UI error: {args.Exception}");
            MessageBox.Show(
                $"The print bridge hit an unexpected error:\n{args.Exception.Message}",
                "Item Management Print Bridge",
                MessageBoxButtons.OK,
                MessageBoxIcon.Error);
        };
        AppDomain.CurrentDomain.UnhandledException += (_, args) =>
        {
            Log.Write($"Unhandled error: {args.ExceptionObject}");
        };

        BridgeConfig config;
        try
        {
            config = BridgeConfig.Load();
        }
        catch (Exception ex)
        {
            MessageBox.Show(
                $"Could not load the print bridge settings:\n{ex.Message}",
                "Item Management Print Bridge",
                MessageBoxButtons.OK,
                MessageBoxIcon.Error);
            return;
        }

        var host = new BridgeHost(config);
        try
        {
            host.StartAsync().GetAwaiter().GetResult();
        }
        catch (Exception ex)
        {
            Log.Write($"Bridge failed to start: {ex}");
            MessageBox.Show(
                $"The print bridge could not start on 127.0.0.1:{config.Port}.\n\n{ex.Message}\n\n"
                + "Close any program using that port, or change the port in config.json.",
                "Item Management Print Bridge",
                MessageBoxButtons.OK,
                MessageBoxIcon.Error);
            return;
        }

        Autostart.Apply(config.StartWithWindows);
        Log.Write($"Bridge started. Config: {BridgeConfig.ConfigPath}");

        using var tray = new TrayApp(host);
        Application.Run(tray);

        host.StopAsync().GetAwaiter().GetResult();
        Log.Write("Bridge stopped.");
    }
}
