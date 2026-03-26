package me.bechberger.femtojar;

import java.util.List;

/**
 * Configuration POJO for the optional ProGuard step.
 * Used both as a global plugin parameter and as a per-JAR override.
 * Null fields fall back to the global config via {@link #mergeWith(ProGuardConfig)}.
 */
public class ProGuardConfig {

    private Boolean enabled;
    private Boolean prependDefaultConfig;
    private String configFile;
    private List<String> options;
    private String out;
    private List<String> libraryJars;

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Boolean getPrependDefaultConfig() { return prependDefaultConfig; }
    public void setPrependDefaultConfig(Boolean prependDefaultConfig) { this.prependDefaultConfig = prependDefaultConfig; }

    public String getConfigFile() { return configFile; }
    public void setConfigFile(String configFile) { this.configFile = configFile; }

    public List<String> getOptions() { return options; }
    public void setOptions(List<String> options) { this.options = options; }

    public String getOut() { return out; }
    public void setOut(String out) { this.out = out; }

    public List<String> getLibraryJars() { return libraryJars; }
    public void setLibraryJars(List<String> libraryJars) { this.libraryJars = libraryJars; }

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
        ProGuardConfig merged = new ProGuardConfig();
        merged.enabled = this.enabled != null ? this.enabled : global.enabled;
        merged.prependDefaultConfig = this.prependDefaultConfig != null ? this.prependDefaultConfig : global.prependDefaultConfig;
        merged.configFile = this.configFile != null ? this.configFile : global.configFile;
        merged.options = this.options != null ? this.options : global.options;
        merged.out = this.out != null ? this.out : global.out;
        merged.libraryJars = this.libraryJars != null ? this.libraryJars : global.libraryJars;
        return merged;
    }
}
