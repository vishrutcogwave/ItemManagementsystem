using System.Text.Json.Serialization;

namespace ItemManagement.PrintBridge;

public sealed class ThermalLine
{
    [JsonPropertyName("text")]
    public string Text { get; set; } = string.Empty;

    [JsonPropertyName("bold")]
    public bool Bold { get; set; }

    [JsonPropertyName("align")]
    public string? Align { get; set; }

    [JsonPropertyName("doubleSize")]
    public bool DoubleSize { get; set; }
}

public sealed class DocumentPayload
{
    [JsonPropertyName("type")]
    public string Type { get; set; } = string.Empty;

    [JsonPropertyName("saleNumber")]
    public string SaleNumber { get; set; } = string.Empty;

    [JsonPropertyName("lines")]
    public List<ThermalLine> Lines { get; set; } = new();
}

public sealed class PrinterTarget
{
    [JsonPropertyName("ipAddress")]
    public string IpAddress { get; set; } = string.Empty;

    [JsonPropertyName("port")]
    public int Port { get; set; }
}

public sealed class PrintJobPayload
{
    [JsonPropertyName("jobId")]
    public long JobId { get; set; }

    [JsonPropertyName("jobKey")]
    public string JobKey { get; set; } = string.Empty;

    [JsonPropertyName("platform")]
    public string Platform { get; set; } = string.Empty;

    [JsonPropertyName("paperWidth")]
    public string PaperWidth { get; set; } = "MM80";

    [JsonPropertyName("printer")]
    public PrinterTarget? Printer { get; set; }

    [JsonPropertyName("document")]
    public DocumentPayload? Document { get; set; }

    [JsonPropertyName("requestedAt")]
    public string? RequestedAt { get; set; }
}

public sealed record PrintResult(bool Success, string Message, string? Printer, string? Mode);

internal static class PayloadValidator
{
    public const int MaxLines = 300;
    public const int MaxTextLength = 200;

    public static string? Validate(PrintJobPayload? payload)
    {
        if (payload is null) return "The print job payload is missing.";
        if (payload.JobId <= 0) return "The print job id is invalid.";
        if (string.IsNullOrWhiteSpace(payload.JobKey)) return "The print job key is missing.";
        if (!string.Equals(payload.Platform, "WINDOWS", StringComparison.OrdinalIgnoreCase)) return "This bridge only prints Windows jobs.";
        if (payload.PaperWidth is not ("MM58" or "MM80")) return "Paper width must be MM58 or MM80.";
        if (payload.Document is null) return "The document is missing.";
        if (!string.Equals(payload.Document.Type, "SALE_RECEIPT", StringComparison.OrdinalIgnoreCase)) return "Unsupported document type.";
        if (payload.Document.Lines is null || payload.Document.Lines.Count == 0) return "The receipt has no lines.";
        if (payload.Document.Lines.Count > MaxLines) return "The receipt has too many lines.";
        var total = 0;
        foreach (var line in payload.Document.Lines)
        {
            if (line is null) return "The receipt contains an invalid line.";
            if (line.Text is null) line.Text = string.Empty;
            if (line.Text.Length > MaxTextLength) return "A receipt line is too long.";
            if (line.Align is not (null or "left" or "center" or "right")) return "A receipt line has an invalid alignment.";
            total += line.Text.Length;
        }
        if (total > 20000) return "The receipt content is too large.";
        return null;
    }
}
