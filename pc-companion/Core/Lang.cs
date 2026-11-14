using System.Globalization;

namespace OpenGamepad.Companion;

/// <summary>
/// Messages in the system language: Italian, English, Chinese (simplified and traditional) or
/// Japanese; English for every other language.
/// </summary>
public static class L
{
    private static readonly int Index = Pick(CultureInfo.CurrentUICulture);

    public static string T(string italian, string english, string chineseSimplified, string chineseTraditional, string japanese) =>
        Index switch
        {
            0 => italian,
            2 => chineseSimplified,
            3 => chineseTraditional,
            4 => japanese,
            _ => english,
        };

    private static int Pick(CultureInfo culture)
    {
        switch (culture.TwoLetterISOLanguageName)
        {
            case "it": return 0;
            case "ja": return 4;
            case "zh":
                // zh-TW, zh-HK, zh-MO and zh-Hant-* use traditional characters.
                var name = culture.Name;
                return name.Contains("Hant") || name.EndsWith("TW") || name.EndsWith("HK") || name.EndsWith("MO") ? 3 : 2;
            default: return 1;
        }
    }
}
