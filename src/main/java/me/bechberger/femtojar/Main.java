package me.bechberger.femtojar;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class Main {

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        CliConfig config;
        try {
            config = parseArgs(args);
        } catch (IllegalArgumentException ex) {
            err.println(ex.getMessage());
            printUsage(err);
            return 2;
        }

        if (config.showHelp) {
            printUsage(out);
            return 0;
        }

        Path inputJar = config.inputJar;
        Path outputJar = config.outputJar == null ? inputJar : config.outputJar;

        if (!Files.exists(inputJar) || !Files.isRegularFile(inputJar)) {
            err.println("Input JAR does not exist: " + inputJar);
            return 2;
        }

        if (config.benchmark) {
            BenchmarkRunner runner = new BenchmarkRunner(out, err);
            BenchmarkRunner.Format format = config.benchmarkFormat == BenchmarkFormat.MARKDOWN
                    ? BenchmarkRunner.Format.MARKDOWN
                    : BenchmarkRunner.Format.TEXT;
            return runner.run(inputJar, config.benchmarkIterations, format);
        }

        JarReencoder reencoder = new JarReencoder();
        try {
            JarReencoder.ReencodeResult result;
            if (inputJar.equals(outputJar)) {
                result = reencoder.reencodeInPlaceBundled(
                        inputJar,
                        config.useZopfli,
                        config.zopfliIterations,
                        config.bundleResources,
                        "cli");
            } else {
                long originalSize = Files.size(inputJar);
                reencoder.rewriteJarBundled(
                        inputJar,
                        outputJar,
                        config.useZopfli,
                        config.zopfliIterations,
                        config.bundleResources,
                        "cli");
                long newSize = Files.size(outputJar);
                result = new JarReencoder.ReencodeResult(originalSize, newSize);
            }

            long saved = result.originalSize() - result.newSize();
            double ratio = result.originalSize() == 0 ? 0d : (saved * 100.0) / result.originalSize();
            String mode = config.useZopfli
                    ? "zopfli (iterations=" + config.zopfliIterations + ")"
                    : "deflate (level=9)";
            String bundledMode = config.bundleResources
                    ? "bundled classes + non-META-INF resources"
                    : "bundled classes only";
            out.println("Re-encoded " + inputJar + " -> " + outputJar + " using " + bundledMode + " + " + mode
                    + ": " + result.originalSize() + " -> " + result.newSize() + " bytes (saved " + saved
                    + " bytes, " + String.format("%.2f", ratio) + "%)");
            return 0;
        } catch (IOException ex) {
            err.println("Failed to re-encode JAR: " + ex.getMessage());
            return 1;
        }
    }



    private static CliConfig parseArgs(String[] args) {
        if (args.length == 0) {
            throw new IllegalArgumentException("Missing input JAR");
        }

        CliConfig config = new CliConfig();
        int i = 0;
        while (i < args.length) {
            String arg = args[i];
            switch (arg) {
                case "-h", "--help" -> {
                    config.showHelp = true;
                    i++;
                }
                case "--in" -> {
                    i = requireValue(args, i, "--in");
                    config.inputJar = Paths.get(args[i]);
                    i++;
                }
                case "--out" -> {
                    i = requireValue(args, i, "--out");
                    config.outputJar = Paths.get(args[i]);
                    i++;
                }
                case "--deflate" -> {
                    config.useZopfli = false;
                    i++;
                }
                case "--zopfli" -> {
                    config.useZopfli = true;
                    i++;
                }
                case "--zopfli-iterations" -> {
                    i = requireValue(args, i, "--zopfli-iterations");
                    try {
                        config.zopfliIterations = Integer.parseInt(args[i]);
                    } catch (NumberFormatException ex) {
                        throw new IllegalArgumentException("Invalid value for --zopfli-iterations: " + args[i]);
                    }
                    if (config.zopfliIterations <= 0) {
                        throw new IllegalArgumentException("--zopfli-iterations must be > 0");
                    }
                    i++;
                }
                case "--bundle-resources" -> {
                    config.bundleResources = true;
                    i++;
                }
                case "--benchmark" -> {
                    config.benchmark = true;
                    i++;
                }
                case "--benchmark-zopfli-iterations" -> {
                    i = requireValue(args, i, "--benchmark-zopfli-iterations");
                    config.benchmarkIterations = parseIterationList(args[i]);
                    i++;
                }
                case "--benchmark-format" -> {
                    i = requireValue(args, i, "--benchmark-format");
                    config.benchmarkFormat = parseBenchmarkFormat(args[i]);
                    i++;
                }
                default -> {
                    if (arg.startsWith("-")) {
                        throw new IllegalArgumentException("Unknown option: " + arg);
                    }
                    if (config.inputJar == null) {
                        config.inputJar = Paths.get(arg);
                    } else if (config.outputJar == null) {
                        config.outputJar = Paths.get(arg);
                    } else {
                        throw new IllegalArgumentException("Unexpected argument: " + arg);
                    }
                    i++;
                }
            }
        }

        if (config.showHelp) {
            return config;
        }
        if (config.inputJar == null) {
            throw new IllegalArgumentException("Missing input JAR");
        }
        return config;
    }

    private static int requireValue(String[] args, int index, String option) {
        int nextIndex = index + 1;
        if (nextIndex >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return nextIndex;
    }

    private static void printUsage(PrintStream out) {
        out.println("femtojar CLI");
        out.println("Usage:");
        out.println("  femtojar <input.jar> [output.jar] [--deflate] [--zopfli-iterations N] [--bundle-resources]");
        out.println("  femtojar --in <input.jar> [--out <output.jar>] [--deflate] [--zopfli-iterations N] [--bundle-resources]");
        out.println("  femtojar --benchmark --in <input.jar> [--benchmark-zopfli-iterations 15,50,100] [--benchmark-format text|markdown]");
        out.println("  femtojar --help");
        out.println();
        out.println("Defaults:");
        out.println("  compression: zopfli");
        out.println("  zopfli iterations: 100");
        out.println("  resource bundling: disabled");
        out.println("  benchmark format: text");
        out.println("  output: in-place if not specified");
    }

    private static List<Integer> parseIterationList(String text) {
        String[] parts = text.split(",");
        Set<Integer> values = new LinkedHashSet<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int value;
            try {
                value = Integer.parseInt(trimmed);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid benchmark iteration value: " + trimmed);
            }
            if (value <= 0) {
                throw new IllegalArgumentException("Benchmark iteration values must be > 0");
            }
            values.add(value);
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("--benchmark-zopfli-iterations requires at least one integer value");
        }
        return new ArrayList<>(values);
    }

    private static BenchmarkFormat parseBenchmarkFormat(String value) {
        String normalized = value.toLowerCase();
        return switch (normalized) {
            case "text" -> BenchmarkFormat.TEXT;
            case "markdown", "md" -> BenchmarkFormat.MARKDOWN;
            default -> throw new IllegalArgumentException(
                    "Invalid value for --benchmark-format: " + value + " (expected: text|markdown)");
        };
    }

    private static final class CliConfig {
        private Path inputJar;
        private Path outputJar;
        private boolean useZopfli = true;
        private int zopfliIterations = 100;
        private boolean bundleResources;
        private boolean benchmark;
        private List<Integer> benchmarkIterations;
        private BenchmarkFormat benchmarkFormat = BenchmarkFormat.TEXT;
        private boolean showHelp;
    }

    private enum BenchmarkFormat {
        TEXT,
        MARKDOWN
    }
}