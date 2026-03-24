package me.bechberger.femtojar;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
            return runner.run(inputJar, format);
        }

        JarReencoder reencoder = new JarReencoder();
        try {
            JarReencoder.ReencodeResult result;
            PrintStream logger = config.verbose ? System.err : null;
            if (inputJar.equals(outputJar)) {
                result = reencoder.reencodeInPlaceBundled(
                        inputJar,
                    config.compressionMode.useZopfli(),
                    config.compressionMode.zopfliIterations(),
                        config.bundleResources,
                        "cli",
                        config.advancedMode,
                        config.advancedIterations,
                        config.parallel,
                        logger);
            } else {
                long originalSize = Files.size(inputJar);
                reencoder.rewriteJarBundled(
                        inputJar,
                        outputJar,
                    config.compressionMode.useZopfli(),
                    config.compressionMode.zopfliIterations(),
                        config.bundleResources,
                        "cli",
                        config.advancedMode,
                        config.advancedIterations,
                        config.parallel,
                        logger);
                long newSize = Files.size(outputJar);
                result = new JarReencoder.ReencodeResult(originalSize, newSize);
            }

            long saved = result.originalSize() - result.newSize();
            double ratio = result.originalSize() == 0 ? 0d : (saved * 100.0) / result.originalSize();
            String mode = config.compressionMode.description();
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
                case "--compression" -> {
                    i = requireValue(args, i, "--compression");
                    config.compressionMode = CompressionMode.parse(args[i]);
                    i++;
                }
                case "--deflate" -> {
                    config.compressionMode = CompressionMode.DEFAULT;
                    i++;
                }
                case "--zopfli" -> {
                    config.compressionMode = CompressionMode.ZOPFLI;
                    i++;
                }
                case "--max" -> {
                    config.compressionMode = CompressionMode.MAX;
                    i++;
                }
                case "--bundle-resources" -> {
                    config.bundleResources = true;
                    i++;
                }
                case "--no-bundle-resources" -> {
                    config.bundleResources = false;
                    i++;
                }
                case "--advanced-mode" -> {
                    i = requireValue(args, i, "--advanced-mode");
                    config.advancedMode = AdvancedOrderingMode.parse(args[i]);
                    i++;
                }
                case "--advanced-iterations" -> {
                    i = requireValue(args, i, "--advanced-iterations");
                    try {
                        config.advancedIterations = Integer.parseInt(args[i]);
                    } catch (NumberFormatException ex) {
                        throw new IllegalArgumentException("Invalid value for --advanced-iterations: " + args[i]);
                    }
                    i++;
                }
                case "--parallel" -> {
                    config.parallel = true;
                    i++;
                }
                case "--verbose", "--rverbose" -> {
                    config.verbose = true;
                    i++;
                }
                case "--benchmark" -> {
                    config.benchmark = true;
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
        out.println("  femtojar <input.jar> [output.jar] [--compression default|zopfli|max] [--no-bundle-resources] [--advanced-mode package|hill-climb] [--advanced-iterations N] [--parallel] [--rverbose]");
        out.println("  femtojar --in <input.jar> [--out <output.jar>] [--compression default|zopfli|max] [--no-bundle-resources] [--advanced-mode package|hill-climb] [--advanced-iterations N] [--parallel] [--rverbose]");
        out.println("  femtojar --benchmark --in <input.jar> [--benchmark-format text|markdown]");
        out.println("  femtojar --help");
        out.println();
        out.println("Defaults:");
        out.println("  compression: default (deflate level=9)");
        out.println("  resource bundling: disabled");
        out.println("  advanced mode: disabled (lexical class order)");
        out.println("  advanced iterations: -1 (off; only used with hill-climb modes)");
        out.println("  parallel: disabled");
        out.println("  benchmark modes: default, zopfli, max (each with resources on/off, run in parallel)");
        out.println("  benchmark format: text");
        out.println("  output: in-place if not specified");
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
        private CompressionMode compressionMode = CompressionMode.DEFAULT;
        private boolean bundleResources = false;
        private AdvancedOrderingMode advancedMode = null;
        private int advancedIterations = -1;
        private boolean parallel = false;
        private boolean verbose = false;
        private boolean benchmark;
        private BenchmarkFormat benchmarkFormat = BenchmarkFormat.TEXT;
        private boolean showHelp;
    }

    private enum BenchmarkFormat {
        TEXT,
        MARKDOWN
    }
}