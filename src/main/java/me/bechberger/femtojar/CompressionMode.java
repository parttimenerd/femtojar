package me.bechberger.femtojar;

/**
 * Compression mode presets used by CLI, plugin, and benchmark commands.
 */
public enum CompressionMode {
    DEFAULT("default", false, 9, "default (deflate level=9)"),
    ZOPFLI("zopfli", true, 7, "zopfli (iterations=7)"),
    MAX("max", true, 100, "max (zopfli iterations=100)");

    private final String cliValue;
    private final boolean useZopfli;
    private final int zopfliIterations;
    private final String description;

    CompressionMode(String cliValue, boolean useZopfli, int zopfliIterations, String description) {
        this.cliValue = cliValue;
        this.useZopfli = useZopfli;
        this.zopfliIterations = zopfliIterations;
        this.description = description;
    }

    public String cliValue() {
        return cliValue;
    }

    public boolean useZopfli() {
        return useZopfli;
    }

    public int zopfliIterations() {
        return zopfliIterations;
    }

    public String description() {
        return description;
    }

    public static CompressionMode parse(String rawValue) {
        String value = rawValue.toLowerCase();
        return switch (value) {
            case "default", "deflate" -> DEFAULT;
            case "zopfli" -> ZOPFLI;
            case "max" -> MAX;
            default -> throw new IllegalArgumentException(
                    "Invalid value for --compression: " + rawValue + " (expected: default|zopfli|max)");
        };
    }
}
