using System.Diagnostics;

namespace ItemManagement.PrintBridge;

public sealed class KeyForm : Form
{
    private readonly BridgeHost _host;
    private readonly TextBox _keyBox;
    private readonly Label _printerLabel;
    private readonly Label _statusLabel;
    private readonly Label _originsLabel;
    private readonly Label _copyLabel;
    private readonly Button _testButton;
    private readonly System.Windows.Forms.Timer _refreshTimer;

    public KeyForm(BridgeHost host)
    {
        _host = host;
        Text = "Item Management Print Bridge";
        StartPosition = FormStartPosition.CenterScreen;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        ClientSize = new Size(580, 400);
        Font = new Font("Segoe UI", 9f);
        AutoScaleMode = AutoScaleMode.Dpi;
        AutoScroll = true;

        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            Padding = new Padding(18),
            RowCount = 8,
            AutoSize = true,
        };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        for (var row = 0; row < 8; row++)
        {
            layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        }

        var title = new Label
        {
            Text = "Bridge key",
            Font = new Font("Segoe UI", 12f, FontStyle.Bold),
            AutoSize = true,
            Margin = new Padding(0, 0, 0, 4),
        };
        var help = new Label
        {
            Text = "Open the store website on this PC, go to Settings → Printers, paste this key and save. "
                 + "The key stays on this PC and is never stored on the server.",
            AutoSize = true,
            MaximumSize = new Size(510, 0),
            Margin = new Padding(0, 0, 0, 8),
        };

        _keyBox = new TextBox
        {
            ReadOnly = true,
            Dock = DockStyle.Fill,
            Font = new Font("Consolas", 11f),
            Text = host.Config.Key,
            Margin = new Padding(0, 0, 0, 8),
        };

        _copyLabel = new Label { Text = string.Empty, AutoSize = true, ForeColor = Color.DarkGreen, Margin = new Padding(0, 0, 0, 8) };

        var keyPanel = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 2,
            AutoSize = true,
            Margin = new Padding(0, 0, 0, 8),
        };
        keyPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        keyPanel.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        keyPanel.Controls.Add(_keyBox, 0, 0);
        var copyButton = new Button { Text = "Copy", AutoSize = true, Margin = new Padding(8, 0, 0, 0) };
        copyButton.Click += (_, _) =>
        {
            try
            {
                Clipboard.SetText(_host.Config.Key);
                _copyLabel.Text = "Key copied. Paste it in Settings → Printers.";
            }
            catch (Exception ex)
            {
                _copyLabel.Text = $"Could not copy: {ex.Message}";
            }
        };
        keyPanel.Controls.Add(copyButton, 1, 0);

        _printerLabel = new Label { AutoSize = true, MaximumSize = new Size(510, 0), Margin = new Padding(0, 0, 0, 4) };
        _statusLabel = new Label { AutoSize = true, MaximumSize = new Size(510, 0), Margin = new Padding(0, 0, 0, 4), ForeColor = Color.DimGray };
        _originsLabel = new Label { AutoSize = true, MaximumSize = new Size(510, 0), Margin = new Padding(0, 0, 0, 8) };

        var buttons = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.RightToLeft,
            AutoSize = true,
            Margin = new Padding(0, 8, 0, 0),
        };
        var closeButton = new Button { Text = "Close", AutoSize = true };
        closeButton.Click += (_, _) => Hide();
        _testButton = new Button { Text = "Test default printer", AutoSize = true, Margin = new Padding(8, 0, 0, 0) };
        _testButton.Click += async (_, _) =>
        {
            _testButton.Enabled = false;
            _testButton.Text = "Testing...";
            try
            {
                var result = await Task.Run(() => _host.Printer.PrintTest(_host.Config));
                MessageBox.Show(
                    this,
                    result.Success ? $"A test slip was sent to:\n{result.Printer}" : result.Message,
                    "Print bridge test",
                    MessageBoxButtons.OK,
                    result.Success ? MessageBoxIcon.Information : MessageBoxIcon.Warning);
            }
            finally
            {
                _testButton.Text = "Test default printer";
                _testButton.Enabled = true;
                RefreshStatus();
            }
        };
        var logButton = new Button { Text = "Open log", AutoSize = true, Margin = new Padding(8, 0, 0, 0) };
        logButton.Click += (_, _) => OpenLogFolder();
        buttons.Controls.Add(closeButton);
        buttons.Controls.Add(_testButton);
        buttons.Controls.Add(logButton);

        layout.Controls.Add(title, 0, 0);
        layout.Controls.Add(help, 0, 1);
        layout.Controls.Add(keyPanel, 0, 2);
        layout.Controls.Add(_copyLabel, 0, 3);
        layout.Controls.Add(_printerLabel, 0, 4);
        layout.Controls.Add(_statusLabel, 0, 5);
        layout.Controls.Add(_originsLabel, 0, 6);
        layout.Controls.Add(buttons, 0, 7);
        Controls.Add(layout);

        _refreshTimer = new System.Windows.Forms.Timer { Interval = 3000 };
        _refreshTimer.Tick += (_, _) => RefreshStatus();
        _refreshTimer.Start();
        RefreshStatus();
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            Hide();
            return;
        }
        _refreshTimer.Stop();
        base.OnFormClosing(e);
    }

    public void RefreshStatus()
    {
        var printer = _host.Printer.GetDefaultPrinter();
        _printerLabel.Text = string.IsNullOrWhiteSpace(printer)
            ? "Default printer: none installed. Add a printer in Windows."
            : $"Default printer: {printer}";
        _statusLabel.Text = $"Bridge: running on {_host.Url}  |  Last result: {_host.LastResult}";
        _originsLabel.Text = $"Websites allowed to use this bridge: {string.Join(", ", _host.Config.AllowedOrigins)}";
        _keyBox.Text = _host.Config.Key;
    }

    private static void OpenLogFolder()
    {
        try
        {
            Directory.CreateDirectory(BridgeConfig.DataFolder);
            Process.Start(new ProcessStartInfo
            {
                FileName = BridgeConfig.DataFolder,
                UseShellExecute = true,
            });
        }
        catch (Exception ex)
        {
            MessageBox.Show($"Could not open the log folder: {ex.Message}", "Print bridge", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }
}
