package com.inxups.minegpt.shared;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Build version shared by the Bridge and both client Mod distributions. */
public final class MineGPTVersion {
    private static final String RESOURCE = "/minegpt-build.properties";
    private static final String FALLBACK_VERSION = "development";

    private MineGPTVersion() {
    }

    public static String current() {
        try (InputStream input = MineGPTVersion.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                return FALLBACK_VERSION;
            }
            Properties properties = new Properties();
            properties.load(input);
            String version = properties.getProperty("version");
            return version == null || version.isBlank() ? FALLBACK_VERSION : version.trim();
        } catch (IOException ignored) {
            return FALLBACK_VERSION;
        }
    }
}
