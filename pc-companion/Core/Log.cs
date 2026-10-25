namespace OpenGamepad.Companion;

public static class Log
{
    private static readonly List<string> Lines = [];
    public static event Action<string>? Written;

    public static void Write(string message)
    {
        var line = $"{DateTime.Now:HH:mm:ss}  {message}";
        lock (Lines)
        {
            Lines.Add(line);
            if (Lines.Count > 200) Lines.RemoveAt(0);
        }
        Written?.Invoke(line);
    }
}
