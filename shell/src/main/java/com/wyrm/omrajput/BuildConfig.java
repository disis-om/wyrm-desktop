package com.wyrm.omrajput;

import java.io.InputStream;
import java.util.Properties;

/**
 * Wyrm Desktop: the BuildConfig fields the shared Android files read. The
 * version comes from Gradle (`-PwyrmVersion`, `-PwyrmVersionCode`) through the
 * resource wyrm-version.properties (D8).
 */
public final class BuildConfig {
    private BuildConfig() {}

    public static final String VERSION_NAME;
    public static final int VERSION_CODE;
    public static final String WYRM_API_URL = "https://wyrm-api.77-245-76-86.sslip.io";

    static {
        Properties version = new Properties();
        try (InputStream in = BuildConfig.class.getResourceAsStream("/wyrm-version.properties")) {
            if (in != null) version.load(in);
        } catch (Exception ignored) {
        }
        VERSION_NAME = version.getProperty("version", "1.0.0");
        int code = 100;
        try {
            code = Integer.parseInt(version.getProperty("code", "100").trim());
        } catch (NumberFormatException ignored) {
        }
        VERSION_CODE = code;
    }
}
