using System.Text;

namespace ItemManagement.PrintBridge;

/// <summary>
/// Minimal ESC/POS encoder. Receipt text is already formatted by the web app;
/// this class only wraps over-wide lines, applies alignment/style and cuts.
/// </summary>
internal static class EscPos
{
    private const byte Esc = 0x1B;
    private const byte Gs = 0x1D;

    public static int Columns(string paperWidth) => paperWidth == "MM58" ? 32 : 48;

    public static byte[] Build(IReadOnlyList<ThermalLine> lines, string paperWidth)
    {
        var columns = Columns(paperWidth);
        using var stream = new MemoryStream();
        stream.Write(new[] { Esc, (byte)'@' });
        stream.Write(new byte[] { Esc, (byte)'t', 0 }); // PC437

        foreach (var line in lines)
        {
            var doubleSize = line.DoubleSize && Safe(line.Text).Length <= columns / 2;
            var align = line.Align switch
            {
                "center" => (byte)1,
                "right" => (byte)2,
                _ => (byte)0,
            };
            stream.Write(new byte[] { Esc, (byte)'a', align });
            stream.Write(new byte[] { Esc, (byte)'E', line.Bold ? (byte)1 : (byte)0 });
            stream.Write(new byte[] { Gs, (byte)'!', doubleSize ? (byte)0x11 : (byte)0x00 });

            var width = doubleSize ? columns / 2 : columns;
            foreach (var part in Wrap(Safe(line.Text), width))
            {
                stream.Write(Encoding.ASCII.GetBytes(part));
                stream.WriteByte(0x0A);
            }
        }

        stream.Write(new byte[] { Esc, (byte)'E', 0 });
        stream.Write(new byte[] { Gs, (byte)'!', 0 });
        stream.Write(new byte[] { Esc, (byte)'a', 0 });
        stream.Write(Encoding.ASCII.GetBytes("\n\n\n"));
        stream.Write(new byte[] { Gs, (byte)'V', 66, 3 }); // feed + partial cut
        return stream.ToArray();
    }

    public static byte[] TestSlip(string printerName, string paperWidth)
    {
        var columns = Columns(paperWidth);
        var stamp = DateTime.Now.ToString("dd MMM yyyy HH:mm:ss");
        var lines = new List<ThermalLine>
        {
            new() { Text = "ITEM MANAGEMENT", Bold = true, Align = "center", DoubleSize = true },
            new() { Text = "Print bridge test", Align = "center" },
            new() { Text = new string('-', columns) },
            new() { Text = $"Printer: {printerName}" },
            new() { Text = $"Paper: {(paperWidth == "MM58" ? "58 mm" : "80 mm")}" },
            new() { Text = $"Time: {stamp}" },
            new() { Text = new string('-', columns) },
            new() { Text = "If you can read this,", Align = "center" },
            new() { Text = "silent printing is ready.", Align = "center" },
            new() { Text = string.Empty },
        };
        return Build(lines, paperWidth);
    }

    private static IEnumerable<string> Wrap(string text, int width)
    {
        if (text.Length == 0)
        {
            yield return string.Empty;
            yield break;
        }

        var current = new StringBuilder();
        foreach (var rawWord in text.Split(' ', StringSplitOptions.RemoveEmptyEntries))
        {
            var word = rawWord;
            while (word.Length > width)
            {
                if (current.Length > 0)
                {
                    yield return current.ToString();
                    current.Clear();
                }
                yield return word[..width];
                word = word[width..];
            }
            if (current.Length == 0)
            {
                current.Append(word);
            }
            else if (current.Length + 1 + word.Length <= width)
            {
                current.Append(' ').Append(word);
            }
            else
            {
                yield return current.ToString();
                current.Clear();
                current.Append(word);
            }
        }
        if (current.Length > 0) yield return current.ToString();
    }

    private static string Safe(string? text)
    {
        if (string.IsNullOrEmpty(text)) return string.Empty;
        var builder = new StringBuilder(text.Length);
        foreach (var ch in text)
        {
            builder.Append(ch switch
            {
                '\u20B9' => "Rs",
                '\u2013' or '\u2014' => "-",
                '\u2018' or '\u2019' => "'",
                '\u201C' or '\u201D' => "\"",
                '\u2026' => "...",
                '\t' => " ",
                _ => ch is >= ' ' and <= '~' ? ch.ToString() : "?",
            });
        }
        return builder.ToString();
    }
}
