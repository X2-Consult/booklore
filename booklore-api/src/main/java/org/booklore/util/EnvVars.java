package org.booklore.util;

/**
 * Reads Trove's own environment variables. Each {@code TROVE_*} variable falls back to the
 * {@code BOOKLORE_*} name it had before the rename, so existing installs keep their settings.
 */
public final class EnvVars {

    private EnvVars() {
    }

    /** {@code get("RAR_BIN")} returns TROVE_RAR_BIN, else BOOKLORE_RAR_BIN, else null. Blank counts as unset. */
    public static String get(String name) {
        String value = System.getenv("TROVE_" + name);
        if (value == null || value.isBlank()) {
            value = System.getenv("BOOKLORE_" + name);
        }
        return value == null || value.isBlank() ? null : value;
    }

    public static String getOrDefault(String name, String defaultValue) {
        String value = get(name);
        return value != null ? value : defaultValue;
    }
}
