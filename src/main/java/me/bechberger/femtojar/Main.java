package me.bechberger.femtojar;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.femtocli.annotations.Parameters;

@Command(name = "femtojar", description = "Re-encode JAR files with custom class loader and compression.",
        mixinStandardHelpOptions = true)
public class Main implements Callable<Integer> {

    @Parameters(index = "0", description = "Input JAR file")
    public Path inputJar;

    @Parameters(index = "1", arity = "0..1", description = "Output JAR file")
    public Path outputJar;

    @Option(names = {"--compression"}, description = "Compression mode: default|zopfli|max")
    public String compressionMode = "default";

    @Option(names = {"--deflate"}, description = "Use deflate compression")
    public boolean deflate = false;

    @Option(names = {"--zopfli"}, description = "Use zopfli compression")
    public boolean zopfli = false;

    @Option(names = {"--max"}, description = "Use max compression")
    public boolean max = false;

    @Option(names = {"--bundle-resources"}, description = "Bundle resources")
    public boolean bundleResources = false;

    @Option(names = {"--no-bundle-resources"}, description = "Do not bundle resources")
    public boolean noBundleResources = false;

    @Option(names = {"--parallel"}, description = "Enable parallel processing")
    public boolean parallel = false;

    @Option(names = {"--proguard"}, description = "Run ProGuard before reencoding")
    public boolean proguard = false;

    @Option(names = {"--proguard-config"}, description = "Path to ProGuard configuration file")
    public Path proguardConfig;

    @Option(names = {"--proguard-options"}, description = "Inline ProGuard option (repeatable)")
    public List<String> proguardOptions;

    @Option(names = {"--proguard-out"}, description = "Separate ProGuard output JAR path")
    public Path proguardOut;

    @Option(names = {"--no-proguard-default-config"}, description = "Do not prepend the bundled default ProGuard config")
    public boolean noProguardDefaultConfig = false;

    @Option(names = {"--verbose", "--rverbose"}, description = "Enable verbose output")
    public boolean verbose = false;

    @Option(names = {"--benchmark"}, description = "Run benchmarks")
    public boolean benchmark = false;

    @Option(names = {"--benchmark-format"}, description = "Benchmark format: text|markdown|json")
    public String benchmarkFormat = "text";

    public static void main(String[] args) {
        System.exit(FemtoCli.run(Main.class, args));
    }

    @Override
    public Integer call() {
        // stray token removed: "proguard.io" — this was likely inserted accidentally

        if (inputJar == null || !Files.exists(inputJar) || !Files.isRegularFile(inputJar)) {
            System.err.println("Input JAR does not exist: " + inputJar);
            return 2;
        }
        CompressionMode compression = CompressionMode.DEFAULT;
        if (compressionMode != null) {
            try {
                compression = CompressionMode.parse(compressionMode);
            } catch (IllegalArgumentException ex) {
                System.err.println(ex.getMessage());
                return 2;
            }
        } else if (deflate) {
            compression = CompressionMode.DEFAULT;
        } else if (zopfli) {
            compression = CompressionMode.ZOPFLI;
        } else if (max) {
            compression = CompressionMode.MAX;
        }
        boolean bundle = bundleResources && !noBundleResources;
        boolean par = parallel;
        boolean verb = verbose;
        if (benchmark) {
            BenchmarkRunner.Format format;
            if ("markdown".equalsIgnoreCase(benchmarkFormat)) {
                format = BenchmarkRunner.Format.MARKDOWN;
            } else if ("json".equalsIgnoreCase(benchmarkFormat)) {
                format = BenchmarkRunner.Format.JSON;
            } else if ("text".equalsIgnoreCase(benchmarkFormat)) {
                format = BenchmarkRunner.Format.TEXT;
            } else {
                System.err.println("Invalid value for --benchmark-format: " + benchmarkFormat + " (expected: text|markdown|json)");
                return 2;
            }
            BenchmarkRunner runner = new BenchmarkRunner(System.out, System.err);
            return runner.run(
                    inputJar,
                    format,
                    proguardConfig,
                    proguardOptions != null ? proguardOptions : Collections.emptyList(),
                    noProguardDefaultConfig);
        }
        JarReencoder reencoder = new JarReencoder();
        Path proguardTempFile = null;
        try {
            // Run ProGuard if requested.
            Path reencoderInput = inputJar;
            if (proguard) {
                try {
                    Path pgOut;
                    if (proguardOut != null) {
                        pgOut = proguardOut;
                    } else {
                        proguardTempFile = Files.createTempDirectory("proguard-work-")
                                .resolve("proguard-out.jar");
                        pgOut = proguardTempFile;
                    }
                    ProGuardRunner.run(inputJar, pgOut,
                            !noProguardDefaultConfig,
                            proguardConfig,
                            proguardOptions != null ? proguardOptions : Collections.emptyList(),
                            Collections.emptyList());
                    reencoderInput = pgOut;
                } catch (IOException e) {
                    System.err.println("ProGuard failed: " + e.getMessage());
                    return 1;
                }
            }

            JarReencoder.ReencodeResult result;
            PrintStream logger = verb ? System.err : null;
            Path outJar = outputJar == null ? inputJar : outputJar;
            if (reencoderInput.equals(outJar)) {
                result = reencoder.reencodeInPlaceBundled(
                        reencoderInput,
                        compression.useZopfli(),
                        compression.zopfliIterations(),
                        bundle,
                        "cli",
                        par,
                        logger);
            } else {
                long originalSize = Files.size(reencoderInput);
                reencoder.rewriteJarBundled(
                        reencoderInput,
                        outJar,
                        compression.useZopfli(),
                        compression.zopfliIterations(),
                        bundle,
                        "cli",
                        par,
                        logger);
                long newSize = Files.size(outJar);
                result = new JarReencoder.ReencodeResult(originalSize, newSize);
            }
            long saved = result.originalSize() - result.newSize();
            double ratio = result.originalSize() == 0 ? 0d : (saved * 100.0) / result.originalSize();
            String mode = compression.description();
            String bundledMode = bundle
                    ? "bundled classes + non-META-INF resources"
                    : "bundled classes only";
            System.out.println("Re-encoded " + inputJar + " -> " + outJar + " using " + bundledMode + " + " + mode
                    + ": " + result.originalSize() + " -> " + result.newSize() + " bytes (saved " + saved
                    + " bytes, " + String.format("%.2f", ratio) + "%)");
            return 0;
        } catch (IOException ex) {
            System.err.println("Failed to re-encode JAR: " + ex.getMessage());
            return 1;
        } finally {
            if (proguardTempFile != null) {
                try {
                    Files.deleteIfExists(proguardTempFile);
                    Files.deleteIfExists(proguardTempFile.getParent());
                } catch (IOException ignored) {}
            }
        }
    }
}