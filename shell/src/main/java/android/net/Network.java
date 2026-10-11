package android.net;

import java.util.Objects;

/** Wyrm Desktop: one network interface of this computer, by its system name. */
public final class Network {
    final String name;
    final String displayName;

    Network(String name, String displayName) {
        this.name = name;
        this.displayName = displayName == null ? "" : displayName;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Network && ((Network) other).name.equals(name);
    }

    @Override
    public int hashCode() { return Objects.hash(name); }

    @Override
    public String toString() { return name; }
}
