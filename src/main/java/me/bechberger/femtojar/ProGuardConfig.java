package me.bechberger.femtojar;

import java.util.List;

/**
 * Immutable configuration for the optional ProGuard step.
 * Used both as a global plugin parameter and as a per-JAR override.
 * Null fields fall back to the global config via {@link #mergeWith(ProGuardConfig)}.
 */
public record ProGuardConfig(Boolean enabled,
                             Boolean prependDefaultConfig,
                             String configFile,
                             List<String> options,
                             String out,
                             List<String> libraryJars) {

    public ProGuardConfig() {
        this(null, null, null, null, null, null);
    }

    /** Whether ProGuard is effectively enabled (null treated as false). */
    public boolean isEnabled() {
        return enabled != null && enabled;
    }

    /** Whether the default config should be prepended (null treated as true). */
    public boolean isPrependDefaultConfig() {
        return prependDefaultConfig == null || prependDefaultConfig;
    }

    /**
     * Merge this (per-JAR) config with a global config.
     * Per-JAR fields take precedence; null fields fall back to the global value.
     * Returns a new instance — neither original is modified.
     */
    public ProGuardConfig mergeWith(ProGuardConfig global) {
        if (global == null) {
            return this;
        }
        return new ProGuardConfig(
                enabled != null ? enabled : global.enabled,
                prependDefaultConfig != null ? prependDefaultConfig : global.prependDefaultConfig,
                configFile != null ? configFile : global.configFile,
                options != null ? options : global.options,
                out != null ? out : global.out,
                libraryJars != null ? libraryJars : global.libraryJars);
    }
}