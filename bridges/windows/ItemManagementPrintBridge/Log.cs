namespace ItemManagement.PrintBridge;

internal static class Log
{
    private static readonly object Gate = new();

    public static void Write(string message)
    {
        try
        {
            lock (Gate)
            {
                Directory.CreateDirectory(BridgeConfig.DataFolder);
                var line = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {message}{Environment.NewLine}";
                File.AppendAllText(BridgeConfig.LogPath, line);
                Trim();
            }
        }
        catch
        {
            // Logging must never stop the bridge.
        }
    }

    private static void Trim()
    {
        var info = new FileInfo(BridgeConfig.LogPath);
        if (info.Exists && info.Length > 512 * 1024)
        {
            var lines = File.ReadAllLines(BridgeConfig.LogPath);
            File.WriteAllLines(BridgeConfig.LogPath, lines.Skip(lines.Length / 2));
        }
    }
}
