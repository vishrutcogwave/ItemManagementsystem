using System.Collections.Concurrent;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Http.Json;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace ItemManagement.PrintBridge;

public sealed class BridgeHost
{
    private readonly PrintService _printer = new();
    private readonly ConcurrentDictionary<string, DateTime> _printed = new();
    private readonly object _dispatchGate = new();
    private BridgeConfig _config;
    private WebApplication? _app;

    public BridgeHost(BridgeConfig config)
    {
        _config = config;
    }

    public BridgeConfig Config => Volatile.Read(ref _config);

    public PrintService Printer => _printer;

    public string Url => $"http://127.0.0.1:{Config.Port}";

    public string LastResult { get; private set; } = "No print jobs yet.";

    public event EventHandler<string>? LastResultChanged;

    public void ReloadSettings()
    {
        Volatile.Write(ref _config, BridgeConfig.Load());
        Log.Write("Settings reloaded from config.json.");
    }

    public async Task StartAsync(CancellationToken cancellationToken = default)
    {
        var builder = WebApplication.CreateBuilder(new WebApplicationOptions
        {
            Args = Array.Empty<string>(),
            ApplicationName = typeof(BridgeHost).Assembly.GetName().Name,
            EnvironmentName = "Production",
        });
        builder.Logging.ClearProviders();
        builder.WebHost.ConfigureKestrel(options =>
        {
            options.AddServerHeader = false;
            options.Listen(IPAddress.Loopback, Config.Port);
            options.Limits.MaxRequestBodySize = 512 * 1024;
        });
        builder.Services.Configure<JsonOptions>(options =>
        {
            options.SerializerOptions.PropertyNamingPolicy = JsonNamingPolicy.CamelCase;
        });

        var app = builder.Build();
        app.Use(async (context, next) =>
        {
            var origin = context.Request.Headers.Origin.ToString();
            var originAllowed = Config.IsOriginAllowed(origin);
            if (!string.IsNullOrEmpty(origin) && originAllowed)
            {
                context.Response.Headers["Access-Control-Allow-Origin"] = origin;
                context.Response.Headers["Vary"] = "Origin";
                context.Response.Headers["Access-Control-Allow-Headers"] = "Content-Type, X-Bridge-Key";
                context.Response.Headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS";
                context.Response.Headers["Access-Control-Max-Age"] = "600";
            }

            if (HttpMethods.IsOptions(context.Request.Method))
            {
                if (!string.IsNullOrEmpty(origin) && !originAllowed)
                {
                    await WriteError(context, StatusCodes.Status403Forbidden,
                        "This website is not allowed to use the print bridge. Add its address to config.json.");
                    return;
                }
                if (string.Equals(context.Request.Headers["Access-Control-Request-Private-Network"], "true", StringComparison.OrdinalIgnoreCase))
                {
                    context.Response.Headers["Access-Control-Allow-Private-Network"] = "true";
                }
                context.Response.StatusCode = StatusCodes.Status204NoContent;
                return;
            }

            if (!originAllowed)
            {
                await WriteError(context, StatusCodes.Status403Forbidden,
                    "This website is not allowed to use the print bridge. Add its address to config.json.");
                return;
            }

            if (!KeyMatches(context.Request.Headers["X-Bridge-Key"].ToString(), Config.Key))
            {
                await WriteError(context, StatusCodes.Status401Unauthorized, "The bridge key is invalid.");
                return;
            }

            await next();
        });

        app.MapGet("/api/bridge/status", () =>
        {
            var printer = _printer.GetDefaultPrinter();
            return Results.Json(new
            {
                ok = true,
                platform = "WINDOWS",
                version = typeof(BridgeHost).Assembly.GetName().Version?.ToString(3) ?? "1.0.0",
                deviceName = Environment.MachineName,
                defaultPrinter = printer,
                paired = true,
                mode = Config.PrintMode,
                lastResult = LastResult,
            });
        });

        app.MapPost("/api/bridge/test", async (HttpContext context) =>
        {
            using var document = await ReadJsonAsync(context);
            if (document is null) return Results.Json(new { success = false, message = "Invalid request body." }, statusCode: 400);
            var result = _printer.PrintTest(Config);
            SetLastResult(result.Message);
            return Results.Json(new
            {
                success = result.Success,
                message = result.Success
                    ? $"Test slip sent to {result.Printer} ({result.Mode})."
                    : result.Message,
                defaultPrinter = result.Printer,
                mode = result.Mode,
            });
        });

        app.MapPost("/api/bridge/print", async (HttpContext context) =>
        {
            PrintJobPayload? payload;
            try
            {
                using var document = await ReadJsonAsync(context);
                if (document is null)
                {
                    return Results.Json(new { success = false, message = "Invalid request body." }, statusCode: 400);
                }
                payload = JsonSerializer.Deserialize<PrintJobPayload>(document.RootElement.GetRawText(), WebJson.Options);
            }
            catch (JsonException)
            {
                return Results.Json(new { success = false, message = "Invalid request body." }, statusCode: 400);
            }

            var invalid = PayloadValidator.Validate(payload);
            if (invalid is not null)
            {
                return Results.Json(new { success = false, message = invalid }, statusCode: 400);
            }

            lock (_dispatchGate)
            {
                var cacheKey = $"{payload!.JobId}:{payload.JobKey}";
                PruneCache();
                if (_printed.TryGetValue(cacheKey, out var printedAt))
                {
                    Log.Write($"Ignored duplicate job {cacheKey} first sent at {printedAt:O}.");
                    return Results.Json(new
                    {
                        success = true,
                        duplicate = true,
                        message = "This receipt was already printed.",
                        printer = _printer.GetDefaultPrinter(),
                    });
                }

                var result = _printer.PrintReceipt(payload.Document!.Lines, payload.PaperWidth, Config.PrintMode);
                SetLastResult(result.Message);
                if (result.Success)
                {
                    _printed[cacheKey] = DateTime.UtcNow;
                }
                return Results.Json(new
                {
                    success = result.Success,
                    message = result.Message,
                    printer = result.Printer,
                    mode = result.Mode,
                });
            }
        });

        await app.StartAsync(cancellationToken);
        _app = app;
        Log.Write($"Bridge listening on {Url} for allowed websites: {string.Join(", ", Config.AllowedOrigins)}");
    }

    public async Task StopAsync()
    {
        var app = _app;
        _app = null;
        if (app is null) return;
        try
        {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(4));
            await app.StopAsync(timeout.Token);
        }
        catch (Exception ex)
        {
            Log.Write($"Error while stopping the bridge: {ex.Message}");
        }
        await app.DisposeAsync();
    }

    private void SetLastResult(string message)
    {
        LastResult = message;
        try
        {
            LastResultChanged?.Invoke(this, message);
        }
        catch
        {
            // Never let a UI listener break printing.
        }
    }

    private void PruneCache()
    {
        var cutoff = DateTime.UtcNow.AddMinutes(-30);
        foreach (var entry in _printed)
        {
            if (entry.Value < cutoff) _printed.TryRemove(entry.Key, out _);
        }
    }

    private static async Task<JsonDocument?> ReadJsonAsync(HttpContext context)
    {
        try
        {
            if (context.Request.ContentLength > 512 * 1024) return null;
            using var reader = new StreamReader(context.Request.Body, Encoding.UTF8);
            var body = await reader.ReadToEndAsync(context.RequestAborted);
            if (string.IsNullOrWhiteSpace(body)) return JsonDocument.Parse("{}");
            if (body.Length > 200_000) return null;
            return JsonDocument.Parse(body);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static bool KeyMatches(string provided, string expected)
    {
        if (string.IsNullOrEmpty(provided) || string.IsNullOrEmpty(expected)) return false;
        var left = SHA256.HashData(Encoding.UTF8.GetBytes(provided));
        var right = SHA256.HashData(Encoding.UTF8.GetBytes(expected));
        return CryptographicOperations.FixedTimeEquals(left, right);
    }

    private static Task WriteError(HttpContext context, int status, string message)
    {
        context.Response.StatusCode = status;
        return context.Response.WriteAsJsonAsync(new { success = false, message });
    }
}

internal static class WebJson
{
    public static readonly JsonSerializerOptions Options = new(JsonSerializerDefaults.Web);
}
