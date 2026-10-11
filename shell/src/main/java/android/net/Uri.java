package android.net;

import java.net.URI;

/** Wyrm Desktop: the part of android.net.Uri the shared code reads (host, port). */
public final class Uri {
    private final URI uri;

    private Uri(URI uri) { this.uri = uri; }

    public static Uri parse(String text) {
        try {
            return new Uri(URI.create(text));
        } catch (IllegalArgumentException e) {
            return new Uri(URI.create("about:blank"));
        }
    }

    public String getHost() { return uri.getHost(); }
    public int getPort() { return uri.getPort(); }
    public String getScheme() { return uri.getScheme(); }
    public String getPath() { return uri.getPath(); }

    @Override
    public String toString() { return uri.toString(); }
}
