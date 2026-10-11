package android.util;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Wyrm Desktop: Android's Log, to standard error with the tag in front;
 * DesktopLog keeps those lines in app.log for support reports.
 */
public final class Log {
    private Log() {}

    private static int write(String level, String tag, String message, Throwable error) {
        String line = level + "/" + tag + ": " + message;
        System.err.println(line);
        if (error != null) System.err.println(getStackTraceString(error));
        return line.length();
    }

    public static int v(String tag, String message) { return write("V", tag, message, null); }
    public static int d(String tag, String message) { return write("D", tag, message, null); }
    public static int i(String tag, String message) { return write("I", tag, message, null); }
    public static int w(String tag, String message) { return write("W", tag, message, null); }
    public static int w(String tag, String message, Throwable error) { return write("W", tag, message, error); }
    public static int e(String tag, String message) { return write("E", tag, message, null); }
    public static int e(String tag, String message, Throwable error) { return write("E", tag, message, error); }

    public static String getStackTraceString(Throwable error) {
        if (error == null) return "";
        StringWriter out = new StringWriter();
        error.printStackTrace(new PrintWriter(out));
        return out.toString();
    }
}
