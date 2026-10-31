using Microsoft.Win32;

namespace ItemManagement.PrintBridge;

internal static class Autostart
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "ItemManagementPrintBridge";

    public static bool IsEnabled()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey);
            return key?.GetValue(ValueName) is string value && value.Length > 0;
        }
        catch
        {
            return false;
        }
    }

    public static void Apply(bool enabled)
    {
        try
        {
            using var key = Registry.CurrentUser.CreateSubKey(RunKey);
            if (key is null) return;
            if (enabled)
            {
                var path = Environment.ProcessPath;
                if (!string.IsNullOrWhiteSpace(path))
                {
                    key.SetValue(ValueName, $"\"{path}\" --autostart", RegistryValueKind.String);
                }
            }
            else
            {
                key.DeleteValue(ValueName, throwOnMissingValue: false);
            }
        }
        catch (Exception ex)
        {
            Log.Write($"Could not update Windows startup: {ex.Message}");
        }
    }
}
