using System.Diagnostics;

namespace ItemManagement.PrintBridge;

public sealed class TrayApp : ApplicationContext
{
    private readonly BridgeHost _host;
    private readonly NotifyIcon _notifyIcon;
    private readonly ContextMenuStrip _menu;
    private readonly ToolStripMenuItem _statusItem;
    private readonly ToolStripMenuItem _printerItem;
    private readonly ToolStripMenuItem _autostartItem;
    private readonly ToolStripMenuItem _lastResultItem;
    private readonly SynchronizationContext? _ui;
    private KeyForm? _keyForm;

    public TrayApp(BridgeHost host)
    {
        _host = host;
        _ui = SynchronizationContext.Current;
        _menu = new ContextMenuStrip();
        _statusItem = new ToolStripMenuItem("Print bridge: starting...") { Enabled = false };
        _printerItem = new ToolStripMenuItem("Default printer: checking...") { Enabled = false };
        _lastResultItem = new ToolStripMenuItem("No print jobs yet") { Enabled = false };
        var showKeyItem = new ToolStripMenuItem("Show bridge key...");
        var copyKeyItem = new ToolStripMenuItem("Copy bridge key");
        var testItem = new ToolStripMenuItem("Test default printer");
        _autostartItem = new ToolStripMenuItem("Start with Windows") { CheckOnClick = true };
        var originsItem = new ToolStripMenuItem("Edit allowed websites");
        var reloadItem = new ToolStripMenuItem("Reload settings");
        var logItem = new ToolStripMenuItem("Open log folder");
        var exitItem = new ToolStripMenuItem("Exit");

        showKeyItem.Click += (_, _) => ShowKeyForm();
        copyKeyItem.Click += (_, _) => CopyKey();
        testItem.Click += (_, _) => TestPrinter();
        _autostartItem.Click += (_, _) =>
        {
            var enabled = _autostartItem.Checked;
            Autostart.Apply(enabled);
            var config = _host.Config;
            config.StartWithWindows = enabled;
            config.Save();
            ShowBalloon(enabled ? "The bridge will start with Windows." : "Automatic startup is turned off.");
        };
        originsItem.Click += (_, _) => EditOrigins();
        reloadItem.Click += (_, _) =>
        {
            _host.ReloadSettings();
            _autostartItem.Checked = _host.Config.StartWithWindows;
            RefreshMenu();
            ShowBalloon("Settings reloaded.");
        };
        logItem.Click += (_, _) => OpenFolder(BridgeConfig.DataFolder);
        exitItem.Click += (_, _) => ExitBridge();

        _menu.Items.Add(_statusItem);
        _menu.Items.Add(_printerItem);
        _menu.Items.Add(_lastResultItem);
        _menu.Items.Add(new ToolStripSeparator());
        _menu.Items.Add(showKeyItem);
        _menu.Items.Add(copyKeyItem);
        _menu.Items.Add(testItem);
        _menu.Items.Add(new ToolStripSeparator());
        _menu.Items.Add(_autostartItem);
        _menu.Items.Add(originsItem);
        _menu.Items.Add(reloadItem);
        _menu.Items.Add(new ToolStripSeparator());
        _menu.Items.Add(logItem);
        _menu.Items.Add(exitItem);
        _menu.Opening += (_, _) => RefreshMenu();

        _notifyIcon = new NotifyIcon
        {
            Icon = SystemIcons.Application,
            Text = "Item Management Print Bridge",
            Visible = true,
            ContextMenuStrip = _menu,
        };
        _notifyIcon.DoubleClick += (_, _) => ShowKeyForm();
        _host.LastResultChanged += (_, _) => _ui?.Post(_ => RefreshMenu(), null);
        RefreshMenu();
        ShowBalloon("Print bridge is running. Open the tray icon to copy the bridge key.");
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _notifyIcon.Visible = false;
            _notifyIcon.Dispose();
            _menu.Dispose();
            _keyForm?.Dispose();
        }
        base.Dispose(disposing);
    }

    private void RefreshMenu()
    {
        _statusItem.Text = $"Print bridge listening on 127.0.0.1:{_host.Config.Port}";
        var printer = _host.Printer.GetDefaultPrinter();
        _printerItem.Text = string.IsNullOrWhiteSpace(printer)
            ? "Default printer: none"
            : $"Default printer: {printer}";
        _lastResultItem.Text = $"Last: {_host.LastResult}";
        _autostartItem.Checked = Autostart.IsEnabled();
        _notifyIcon.Text = string.IsNullOrWhiteSpace(printer)
            ? "Item Management Print Bridge - no printer"
            : "Item Management Print Bridge";
    }

    private void ShowKeyForm()
    {
        if (_keyForm is null || _keyForm.IsDisposed)
        {
            _keyForm = new KeyForm(_host);
        }
        _keyForm.RefreshStatus();
        _keyForm.Show();
        _keyForm.Activate();
    }

    private void CopyKey()
    {
        try
        {
            Clipboard.SetText(_host.Config.Key);
            ShowBalloon("Bridge key copied. Paste it in Settings → Printers.");
        }
        catch (Exception ex)
        {
            ShowBalloon($"Could not copy the key: {ex.Message}");
        }
    }

    private async void TestPrinter()
    {
        ShowBalloon("Sending a test slip to the default printer...");
        try
        {
            var result = await Task.Run(() => _host.Printer.PrintTest(_host.Config));
            ShowBalloon(result.Success ? $"Test slip sent to {result.Printer}." : result.Message);
        }
        catch (Exception ex)
        {
            ShowBalloon($"Test failed: {ex.Message}");
        }
        RefreshMenu();
    }

    private void EditOrigins()
    {
        try
        {
            BridgeConfig.Load().Save();
            Process.Start(new ProcessStartInfo
            {
                FileName = "notepad.exe",
                Arguments = $"\"{BridgeConfig.ConfigPath}\"",
                UseShellExecute = true,
            });
            ShowBalloon("Add website addresses to allowedOrigins, save, then choose Reload settings.");
        }
        catch (Exception ex)
        {
            ShowBalloon($"Could not open config.json: {ex.Message}");
        }
    }

    private void ShowBalloon(string message)
    {
        try
        {
            _notifyIcon.BalloonTipTitle = "Item Management Print Bridge";
            _notifyIcon.BalloonTipText = message;
            _notifyIcon.ShowBalloonTip(4000);
        }
        catch
        {
            // Balloons are optional.
        }
    }

    private static void OpenFolder(string folder)
    {
        try
        {
            Directory.CreateDirectory(folder);
            Process.Start(new ProcessStartInfo { FileName = folder, UseShellExecute = true });
        }
        catch
        {
            // Ignore shell failures.
        }
    }

    private void ExitBridge()
    {
        Log.Write("Bridge exiting from the tray menu.");
        _notifyIcon.Visible = false;
        try
        {
            _host.StopAsync().GetAwaiter().GetResult();
        }
        catch
        {
            // Exiting anyway.
        }
        ExitThread();
    }
}
