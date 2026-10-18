package com.nusadb.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The driver's own version, read from {@code driver.properties}, which the build fills in from the
 * project version so the driver, its metadata and the published artifact always agree.
 */
final class Version {

    /** Full version string, e.g. {@code 0.2.0} or {@code 0.2.0-SNAPSHOT}. */
    static final String FULL;
    static final int MAJOR;
    static final int MINOR;

    static {
        String v = "0.0.0";
        try (InputStream in = Version.class.getResourceAsStream("driver.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                v = p.getProperty("version", v);
            }
        } catch (IOException ignored) {
            // fall back to 0.0.0; nothing else depends on the exact value
        }
        FULL = v;
        int[] parts = parse(v);
        MAJOR = parts[0];
        MINOR = parts[1];
    }

    private Version() {}

    /** Major and minor of a version such as {@code 1.4.2-SNAPSHOT}; missing parts are 0. */
    static int[] parse(String version) {
        int[] out = new int[2];
        String[] parts = version.split("[.\\-+]");
        for (int i = 0; i < 2 && i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                break;
            }
        }
        return out;
    }
}
