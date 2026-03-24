package me.bechberger.femtojar;

/**
 * Class ordering strategy for advanced blob compression.
 */
public enum AdvancedOrderingMode {
    /**
     * Package-aware ordering: group classes by package, then sort by file size within each package.
     * Deterministic — ignores advancedIterations.
     */
    PACKAGE("package"),

    /**
     * Hill climbing starting from package-aware ordering.
     * Makes local swap perturbations and keeps improvements.
     * Uses fast deflate (level 1) as a proxy during search, so each iteration is cheap.
     * Number of iterations controlled by advancedIterations.
     */
    HILL_CLIMB("hill-climb");

    private final String cliValue;

    AdvancedOrderingMode(String cliValue) {
        this.cliValue = cliValue;
    }

    public String cliValue() {
        return cliValue;
    }

    public static AdvancedOrderingMode parse(String rawValue) {
        String value = rawValue.toLowerCase();
        return switch (value) {
            case "package" -> PACKAGE;
            case "hill-climb", "hillclimb", "hill_climb" -> HILL_CLIMB;
            default -> throw new IllegalArgumentException(
                    "Invalid value for --advanced-mode: " + rawValue + " (expected: package|hill-climb)");
        };
    }
}
