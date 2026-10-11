package android.content;

import java.util.HashMap;
import java.util.Map;

/** Wyrm Desktop: an Android intent as the shared code builds one; Context.startActivity reads the action. */
public class Intent {
    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;

    private final String action;
    private final android.net.Uri data;
    private final Map<String, String> extras = new HashMap<>();

    public Intent(String action) { this(action, null); }

    public Intent(String action, android.net.Uri data) {
        this.action = action;
        this.data = data;
    }

    public String getAction() { return action; }
    public android.net.Uri getData() { return data; }

    public Intent putExtra(String key, String value) {
        extras.put(key, value);
        return this;
    }

    public Intent addFlags(int flags) { return this; }
}
