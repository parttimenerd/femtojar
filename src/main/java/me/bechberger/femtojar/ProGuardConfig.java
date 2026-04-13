package me.bechberger.femtojar;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the optional ProGuard step.
 * Used both as a global plugin parameter and as a per-JAR override.
 * Null fields fall back to the global config via {@link #mergeWith(ProGuardConfig)}.
 * <p>
 * Must be a plain class (not a record) so Maven can inject values via reflection.
 */
public class ProGuardConfig {
    Boolean enabled;
    Boolean prependDefaultConfig;
    String configFile;
    List<String> options;
    String out;
    List<String> libraryJars;

    public ProGuardConfig() {}

    public ProGuardConfig(Boolean enabled, Boolean prependDefaultConfig, String configFile,
                          List<String> options, String out, List<String> libraryJars) {
        this.enabled = enabled;
        this.prependDefaultConfig = prependDefaultConfig;
        this.configFile = configFile;
        this.options = options;
        this.out = out;
        this.libraryJars = libraryJars;
    }

    public Boolean enabled() { return enabled; }
    public Boolean prependDefaultConfig() { return prependDefaultConfig; }
    public String configFile() { return configFile; }
    public List<String> options() { return options; }
    public String out() { return out; }
    public List<String> libraryJars() { return libraryJars; }

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
                mergeLists(options, global.options),
                out != null ? out : global.out,
                mergeLists(libraryJars, global.libraryJars));
    }

    private static <T> List<T> mergeLists(List<T> perJar, List<T> global) {
        if (perJar == null) {
            return global;
        }
        if (global == null) {
            return perJar;
        }
        List<T> merged = new ArrayList<>(global);
        merged.addAll(perJar);
        return merged;
    }
}