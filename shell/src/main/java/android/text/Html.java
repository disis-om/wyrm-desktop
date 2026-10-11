package android.text;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Desktop stand-in for android.text.Html, for the shared Android files
 * (TeamService decodes NTL names and messages with it). One pass, as
 * Android's: tags out (a line break stays a line break), entities decoded
 * once, so "&amp;nbsp;" becomes "&nbsp;" and a second call finishes it.
 */
public final class Html {
    public static final int FROM_HTML_MODE_LEGACY = 0;
    public static final int FROM_HTML_MODE_COMPACT = 63;

    private static final Pattern BREAK = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern PARAGRAPH = Pattern.compile("(?i)</p\\s*>");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern ENTITY = Pattern.compile("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);");
    private static final Map<String, String> NAMED = Map.ofEntries(
            Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"),
            Map.entry("quot", "\""), Map.entry("apos", "'"), Map.entry("nbsp", " "),
            Map.entry("copy", "©"), Map.entry("reg", "®"), Map.entry("trade", "™"),
            Map.entry("hellip", "…"), Map.entry("mdash", "—"), Map.entry("ndash", "–"),
            Map.entry("lsquo", "‘"), Map.entry("rsquo", "’"), Map.entry("ldquo", "“"),
            Map.entry("rdquo", "”"), Map.entry("bull", "•"), Map.entry("middot", "·"),
            Map.entry("euro", "€"), Map.entry("pound", "£"), Map.entry("yen", "¥"),
            Map.entry("cent", "¢"), Map.entry("deg", "°"), Map.entry("times", "×"));

    private Html() {}

    public static CharSequence fromHtml(String source, int flags) {
        if (source == null || source.isEmpty()) return "";
        String text = BREAK.matcher(source).replaceAll("\n");
        text = PARAGRAPH.matcher(text).replaceAll("\n\n");
        text = TAG.matcher(text).replaceAll("");
        Matcher matcher = ENTITY.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            String name = matcher.group(1);
            String value;
            if (name.charAt(0) == '#') {
                try {
                    int code = (name.charAt(1) == 'x' || name.charAt(1) == 'X')
                            ? Integer.parseInt(name.substring(2), 16)
                            : Integer.parseInt(name.substring(1));
                    value = Character.isValidCodePoint(code) ? new String(Character.toChars(code)) : matcher.group();
                } catch (NumberFormatException e) {
                    value = matcher.group();
                }
            } else {
                value = NAMED.getOrDefault(name.toLowerCase(java.util.Locale.ROOT), matcher.group());
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString().replaceAll("\\n+$", "");
    }
}
