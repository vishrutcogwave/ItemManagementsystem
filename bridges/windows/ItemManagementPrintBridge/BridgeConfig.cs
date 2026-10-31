using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace ItemManagement.PrintBridge;

/// <summary>
/// Local configuration lives outside the installation folder so an update
/// never overwrites the bridge key or the list of allowed websites.
/// </summary>
public sealed class BridgeConfig
{
    public const int DefaultPort = 17777;
    public const string AppFolderName = "ItemManagementPrintBridge";

    public int Port { get; set; } = DefaultPort;
    public string Key { get; set; } = string.Empty;
    public string PrintMode { get; set; } = "auto";
    public List<string> AllowedOrigins { get; set; } = new()
public List<string> AllowedOrigins { get; set; } = new()
{
    "http://localhost:5173",
    "http://127.0.0.1:5173",
    "http://localhost:4173",
    "http://127.0.0.1:4173",
    "https://hiremathwebstudio.in",
    "https://www.hiremathwebstudio.in"
};
    public bool StartWithWindows { get; set; } = true;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
    };

    public static string DataFolder =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), AppFolderName);

    public static string ConfigPath => Path.Combine(DataFolder, "config.json");

    public static string LogPath => Path.Combine(DataFolder, "bridge.log");

    public static BridgeConfig Load()
    {
        Directory.CreateDirectory(DataFolder);
        BridgeConfig config;
        try
        {
            config = File.Exists(ConfigPath)
                ? JsonSerializer.Deserialize<BridgeConfig>(File.ReadAllText(ConfigPath), JsonOptions) ?? new BridgeConfig()
                : new BridgeConfig();
        }
        catch (Exception ex)
        {
            Log.Write($"Could not read config.json ({ex.Message}); using defaults.");
            config = new BridgeConfig();
        }

        if (config.Port is < 1024 or > 65535) config.Port = DefaultPort;
        config.PrintMode = NormalizeMode(config.PrintMode);
        if (config.AllowedOrigins is null) config.AllowedOrigins = new List<string>();

        var productionOrigins = new[]
{
    "https://hiremathwebstudio.in",
    "https://www.hiremathwebstudio.in"
};

foreach (var origin in productionOrigins)
{
    if (!config.AllowedOrigins.Contains(
        origin,
        StringComparer.OrdinalIgnoreCase))
    {
        config.AllowedOrigins.Add(origin);
    }
}
        config.AllowedOrigins = config.AllowedOrigins
            .Where(origin => !string.IsNullOrWhiteSpace(origin))
            .Select(origin => origin.Trim().TrimEnd('/'))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
        if (config.AllowedOrigins.Count == 0)
        {
            config.AllowedOrigins.Add("http://localhost:5173");
            config.AllowedOrigins.Add("http://127.0.0.1:5173");
        }

        if (string.IsNullOrWhiteSpace(config.Key) || config.Key.Length < 24)
        {
            config.Key = GenerateKey();
        }

        config.Save();
        return config;
    }

    public void Save()
    {
        Directory.CreateDirectory(DataFolder);
        var temp = ConfigPath + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(this, JsonOptions), Encoding.UTF8);
        File.Move(temp, ConfigPath, true);
    }

    public bool IsOriginAllowed(string origin)
    {
        if (string.IsNullOrEmpty(origin)) return true;
        return AllowedOrigins.Contains(origin.TrimEnd('/'), StringComparer.OrdinalIgnoreCase);
    }

    private static string NormalizeMode(string? mode)
    {
        var value = (mode ?? "auto").Trim().ToLowerInvariant();
        return value is "raw" or "gdi" or "auto" ? value : "auto";
    }

    private static string GenerateKey()
    {
        var bytes = RandomNumberGenerator.GetBytes(32);
        return Convert.ToBase64String(bytes)
            .Replace('+', '-')
            .Replace('/', '_')
            .TrimEnd('=');
    }
}
