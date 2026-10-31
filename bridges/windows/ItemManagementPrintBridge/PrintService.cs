using System.ComponentModel;
using System.Drawing.Printing;
using System.Runtime.InteropServices;
using System.Text;

namespace ItemManagement.PrintBridge;

/// <summary>
/// Prints through the Windows default printer only. Thermal style printers are
/// driven with raw ESC/POS so receipts are instant and silent; everything else
/// falls back to GDI, which also uses a non-interactive print controller.
/// </summary>
public sealed class PrintService
{
    private readonly object _gate = new();

    public string? GetDefaultPrinter()
    {
        try
        {
            var size = 0;
            GetDefaultPrinterNames(null, ref size);
            if (size <= 0) return null;
            var buffer = new StringBuilder(size);
            return GetDefaultPrinterNames(buffer, ref size) ? buffer.ToString() : null;
        }
        catch (Exception ex)
        {
            Log.Write($"Could not read the default printer: {ex.Message}");
            return null;
        }
    }

    public bool IsPrinterInstalled(string printerName)
    {
        try
        {
            return new PrinterSettings { PrinterName = printerName }.IsValid;
        }
        catch
        {
            return false;
        }
    }

    public PrintResult PrintTest(BridgeConfig config)
    {
        var printer = GetDefaultPrinter();
        if (string.IsNullOrWhiteSpace(printer))
        {
            return new PrintResult(false, "No default printer is installed or selected in Windows.", null, null);
        }
        if (!IsPrinterInstalled(printer))
        {
            return new PrintResult(false, $"The default printer '{printer}' is not available right now.", printer, null);
        }
        return PrintLines(Array.Empty<ThermalLine>(), "MM80", config.PrintMode, printer, testPrint: true);
    }

    public PrintResult PrintReceipt(IReadOnlyList<ThermalLine> lines, string paperWidth, string requestedMode)
    {
        var printer = GetDefaultPrinter();
        if (string.IsNullOrWhiteSpace(printer))
        {
            return new PrintResult(false, "No default printer is installed or selected in Windows.", null, null);
        }
        if (!IsPrinterInstalled(printer))
        {
            return new PrintResult(false, $"The default printer '{printer}' is not available right now.", printer, null);
        }
        return PrintLines(lines, paperWidth, requestedMode, printer, testPrint: false);
    }

    private PrintResult PrintLines(
        IReadOnlyList<ThermalLine> lines,
        string paperWidth,
        string requestedMode,
        string printer,
        bool testPrint)
    {
        lock (_gate)
        {
            var mode = (requestedMode ?? "auto").ToLowerInvariant();
            var useRaw = mode == "raw" || (mode != "gdi" && LooksLikeThermal(printer));
            var attempts = new List<string>();

            if (useRaw)
            {
                try
                {
                    var data = testPrint ? EscPos.TestSlip(printer, paperWidth) : EscPos.Build(lines, paperWidth);
                    PrintRaw(printer, data);
                    Log.Write($"Printed {(testPrint ? "test slip" : "receipt")} raw to '{printer}' ({data.Length} bytes).");
                    return new PrintResult(true, "Sent to the default printer.", printer, "raw");
                }
                catch (Exception ex)
                {
                    attempts.Add($"ESC/POS: {ex.Message}");
                    Log.Write($"Raw printing to '{printer}' failed: {ex.Message}");
                    if (mode == "raw")
                    {
                        return new PrintResult(false, $"ESC/POS printing failed: {ex.Message}", printer, "raw");
                    }
                }
            }

            try
            {
                PrintGdi(printer, lines, paperWidth, testPrint);
                Log.Write($"Printed {(testPrint ? "test slip" : "receipt")} with GDI to '{printer}'.");
                return new PrintResult(true, "Sent to the default printer.", printer, "gdi");
            }
            catch (Exception ex)
            {
                attempts.Add($"Windows driver: {ex.Message}");
                Log.Write($"GDI printing to '{printer}' failed: {ex.Message}");
            }

            var detail = attempts.Count > 0 ? string.Join(" | ", attempts) : "The printer rejected the job.";
            return new PrintResult(false, $"The default printer '{printer}' could not print. {detail}", printer, useRaw ? "raw" : "gdi");
        }
    }

    private static bool LooksLikeThermal(string printer)
    {
        var name = printer.ToLowerInvariant();
        return new[] { "pos", "thermal", "receipt", "esc/pos", "escpos", "epson", "star", "tm-", "xp-", "80c", "58c", "kot", "billing" }
            .Any(token => name.Contains(token, StringComparison.Ordinal));
    }

    private static void PrintRaw(string printerName, byte[] data)
    {
        if (!OpenPrinterNames(printerName, out var handle, IntPtr.Zero))
        {
            throw new Win32Exception(Marshal.GetLastWin32Error(), "Cannot open the printer.");
        }

        try
        {
            var document = new DocInfo
            {
                DocumentName = "Item Management Receipt",
                DataType = "RAW",
            };
            if (StartDocPrinterNames(handle, 1, document) == 0)
            {
                throw new Win32Exception(Marshal.GetLastWin32Error(), "Cannot start a print job.");
            }

            try
            {
                if (!StartPagePrinter(handle))
                {
                    throw new Win32Exception(Marshal.GetLastWin32Error(), "Cannot start a printer page.");
                }
                try
                {
                    if (!WritePrinter(handle, data, data.Length, out var written) || written != data.Length)
                    {
                        throw new Win32Exception(Marshal.GetLastWin32Error(), "The printer accepted only part of the receipt.");
                    }
                }
                finally
                {
                    EndPagePrinter(handle);
                }
            }
            finally
            {
                EndDocPrinter(handle);
            }
        }
        finally
        {
            ClosePrinter(handle);
        }
    }

    private static void PrintGdi(string printerName, IReadOnlyList<ThermalLine> lines, string paperWidth, bool testPrint)
    {
        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                var docs = testPrint
                    ? new List<ThermalLine>
                    {
                        new() { Text = string.Empty },
                        new() { Text = "ITEM MANAGEMENT", Bold = true, Align = "center", DoubleSize = true },
                        new() { Text = "Print bridge test", Align = "center" },
                        new() { Text = $"Printer: {printerName}" },
                        new() { Text = $"Time: {DateTime.Now:dd MMM yyyy HH:mm:ss}" },
                        new() { Text = "If you can read this, silent printing is ready.", Align = "center" },
                        new() { Text = string.Empty },
                    }
                    : lines.ToList();
                DrawPages(printerName, docs, paperWidth);
            }
            catch (Exception ex)
            {
                failure = ex;
            }
        })
        {
            IsBackground = true,
            Name = "ItemManagementPrintGdi",
        };
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        if (!thread.Join(TimeSpan.FromSeconds(60)))
        {
            throw new TimeoutException("The printer did not accept the job within 60 seconds.");
        }
        if (failure is not null) throw failure;
    }

    private static void DrawPages(string printerName, IReadOnlyList<ThermalLine> lines, string paperWidth)
    {
        var columns = EscPos.Columns(paperWidth);
        using var document = new PrintDocument();
        document.PrinterSettings.PrinterName = printerName;
        if (!document.PrinterSettings.IsValid)
        {
            throw new InvalidOperationException($"The printer '{printerName}' is not available.");
        }

        var width = paperWidth == "MM58" ? 228 : 315;
        var height = Math.Max(700, 140 + lines.Count * 22);
        try
        {
            document.DefaultPageSettings.PaperSize = new PaperSize("Receipt", width, height);
        }
        catch
        {
            // Some drivers only accept their own paper sizes; use the default.
        }
        document.DefaultPageSettings.Margins = new Margins(8, 8, 8, 8);
        document.PrintController = new StandardPrintController();
        document.DocumentName = "Item Management Receipt";

        var index = 0;
        document.PrintPage += (_, args) =>
        {
            var graphics = args.Graphics;
            if (graphics is null)
            {
                args.HasMorePages = false;
                return;
            }
            graphics.TextRenderingHint = System.Drawing.Text.TextRenderingHint.SingleBitPerPixelGridFit;
            var y = (float)args.MarginBounds.Top;
            var bottom = (float)args.MarginBounds.Bottom;

            while (index < lines.Count)
            {
                var line = lines[index];
                var text = line.Text ?? string.Empty;
                var doubleSize = line.DoubleSize && text.Length <= columns / 2;
                using var font = new Font(
                    "Consolas",
                    doubleSize ? 12f : 8.5f,
                    line.Bold ? FontStyle.Bold : FontStyle.Regular,
                    GraphicsUnit.Point);
                var lineHeight = font.GetHeight(graphics) + 1;
                if (y + lineHeight > bottom && index > 0)
                {
                    args.HasMorePages = true;
                    return;
                }

                var measured = graphics.MeasureString(text, font);
                var x = line.Align switch
                {
                    "center" => args.MarginBounds.Left + (args.MarginBounds.Width - measured.Width) / 2,
                    "right" => args.MarginBounds.Right - measured.Width,
                    _ => (float)args.MarginBounds.Left,
                };
                if (text.Length > 0)
                {
                    graphics.DrawString(text, font, Brushes.Black, Math.Max(args.MarginBounds.Left, x), y);
                }
                y += lineHeight;
                index++;
            }

            args.HasMorePages = index < lines.Count;
        };

        document.Print();
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private sealed class DocInfo
    {
        [MarshalAs(UnmanagedType.LPWStr)]
        public string DocumentName = string.Empty;

        [MarshalAs(UnmanagedType.LPWStr)]
        public string? OutputFile;

        [MarshalAs(UnmanagedType.LPWStr)]
        public string DataType = "RAW";
    }

    [DllImport("winspool.drv", EntryPoint = "OpenPrinterW", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool OpenPrinterNames(string printerName, out IntPtr printer, IntPtr defaults);

    [DllImport("winspool.drv", EntryPoint = "ClosePrinter", SetLastError = true)]
    private static extern bool ClosePrinter(IntPtr printer);

    [DllImport("winspool.drv", EntryPoint = "StartDocPrinterW", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern int StartDocPrinterNames(IntPtr printer, int level, DocInfo document);

    [DllImport("winspool.drv", EntryPoint = "EndDocPrinter", SetLastError = true)]
    private static extern bool EndDocPrinter(IntPtr printer);

    [DllImport("winspool.drv", EntryPoint = "StartPagePrinter", SetLastError = true)]
    private static extern bool StartPagePrinter(IntPtr printer);

    [DllImport("winspool.drv", EntryPoint = "EndPagePrinter", SetLastError = true)]
    private static extern bool EndPagePrinter(IntPtr printer);

    [DllImport("winspool.drv", EntryPoint = "WritePrinter", SetLastError = true)]
    private static extern bool WritePrinter(IntPtr printer, byte[] bytes, int count, out int written);

    [DllImport("winspool.drv", EntryPoint = "GetDefaultPrinterW", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool GetDefaultPrinterNames(StringBuilder? buffer, ref int size);
}
