package me.bechberger.femtojar;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs non-destructive JAR benchmarks across compression and ProGuard modes.
 */
public class BenchmarkRunner {

    public enum Format {
        TEXT,
        MARKDOWN,
        JSON
    }

    private static final List<BenchmarkCase> CASES = List.of(
            new BenchmarkCase(CompressionMode.DEFAULT, false),
            new BenchmarkCase(CompressionMode.ZOPFLI, false),
            new BenchmarkCase(CompressionMode.DEFAULT, true),
            new BenchmarkCase(CompressionMode.ZOPFLI, true),
            new BenchmarkCase(null, true));

    private final PrintStream out;
    private final PrintStream err;

    public BenchmarkRunner(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    /**
     * Runs benchmark across fixed cases.
     *
     * @param inputJar path to input JAR
     * @param format output format (text, markdown, json)
     * @param proguardConfig optional ProGuard config file
     * @param proguardOptions optional inline ProGuard options
     * @param noProguardDefaultConfig whether to disable bundled default ProGuard config
     * @return exit code (0 on success, 1 on failure)
     */
    public int run(Path inputJar,
                   Format format,
                   Path proguardConfig,
                   List<String> proguardOptions,
                   boolean noProguardDefaultConfig) {
        long originalSize;
        try {
            originalSize = Files.size(inputJar);
        } catch (IOException ex) {
            err.println("Failed to read input JAR size: " + ex.getMessage());
            return 1;
        }

        List<BenchmarkResult> results = new ArrayList<>();
        List<String> pgOptions = proguardOptions == null ? Collections.emptyList() : proguardOptions;

        if (format == Format.TEXT) {
            out.println("Benchmarking " + inputJar + " (original size: " + originalSize + " bytes)");
        }

        int workerCount = Math.max(1, Math.min(CASES.size(), Runtime.getRuntime().availableProcessors()));
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        List<Future<BenchmarkResult>> futures = new ArrayList<>();
        for (BenchmarkCase benchmarkCase : CASES) {
            futures.add(executor.submit((Callable<BenchmarkResult>) () ->
                    runSingleCase(inputJar, benchmarkCase, proguardConfig, pgOptions, noProguardDefaultConfig)));
        }
        executor.shutdown();

        for (int fi = 0; fi < futures.size(); fi++) {
            try {
                BenchmarkResult result = futures.get(fi).get();
                results.add(result);
                if (format == Format.TEXT) {
                    out.println("  done: " + result.benchmarkCase().label());
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                err.println("Benchmark interrupted");
                return 1;
            } catch (ExecutionException ex) {
                BenchmarkCase failedCase = CASES.get(fi);
                Throwable cause = ex.getCause();
                String detail = cause.getMessage() != null ? cause.getMessage() : cause.toString();
                err.println("Benchmark case '" + failedCase.label() + "' failed: " + detail);
                cause.printStackTrace(err);
                results.add(BenchmarkResult.failure(failedCase));
            }
        }

        if (results.isEmpty()) {
            err.println("No benchmark results produced");
            return 1;
        }

        render(format, inputJar, originalSize, results);
        return 0;
    }

    private BenchmarkResult runSingleCase(Path inputJar,
                                          BenchmarkCase benchmarkCase,
                                          Path proguardConfig,
                                          List<String> proguardOptions,
                                          boolean noProguardDefaultConfig) throws IOException {
        Path tempProguardOut = null;
        Path tempOut = null;
        try {
            Path currentInput = inputJar;
            long startNs = System.nanoTime();

            if (benchmarkCase.proguard()) {
                tempProguardOut = createTempJar(inputJar);
                Files.deleteIfExists(tempProguardOut);
                List<String> effectiveOptions = proguardOptions;
                if ((effectiveOptions == null || effectiveOptions.isEmpty()) && proguardConfig == null) {
                    // Keep benchmark mode resilient for minimal test jars.
                    effectiveOptions = List.of("-keep class ** { *; }", "-dontwarn");
                }
                ProGuardRunner.run(
                        inputJar,
                        tempProguardOut,
                        !noProguardDefaultConfig,
                        proguardConfig,
                        effectiveOptions,
                        Collections.emptyList());
                currentInput = tempProguardOut;
            }

            long size;
            if (benchmarkCase.mode() == null) {
                size = Files.size(currentInput);
            } else {
                tempOut = createTempJar(inputJar);
                JarReencoder reencoder = new JarReencoder();
                JarReencoder.ReencodeOptions options = new JarReencoder.ReencodeOptions(
                        benchmarkCase.mode().useZopfli(),
                        benchmarkCase.mode().zopfliIterations(),
                        true,
                        "cli-benchmark",
                        false,
                        null);
                reencoder.rewriteJarBundled(currentInput, tempOut, options);
                size = Files.size(tempOut);
            }

            long elapsedNs = System.nanoTime() - startNs;
            long elapsedMs = elapsedNs / 1_000_000;
            return BenchmarkResult.success(benchmarkCase, size, elapsedMs);
        } finally {
            if (tempOut != null) {
                try {
                    Files.deleteIfExists(tempOut);
                } catch (IOException ignored) {
                    // Ignore temp cleanup issues.
                }
            }
            if (tempProguardOut != null) {
                try {
                    Files.deleteIfExists(tempProguardOut);
                } catch (IOException ignored) {
                    // Ignore temp cleanup issues.
                }
            }
        }
    }

    private static Path createTempJar(Path inputJar) throws IOException {
        if (inputJar.getParent() != null) {
            return Files.createTempFile(inputJar.getParent(), "femtojar-bench-", ".jar");
        }
        return Files.createTempFile("femtojar-bench-", ".jar");
    }

    private void render(Format format, Path inputJar, long originalSize, List<BenchmarkResult> results) {
        Map<String, BenchmarkResult> byLabel = results.stream().collect(
                java.util.stream.Collectors.toMap(r -> r.benchmarkCase().label(), r -> r));

        BenchmarkResult best = results.stream()
                .filter(r -> !r.failed())
                .min(Comparator.comparingLong(BenchmarkResult::sizeBytes))
                .orElse(null);
        long bestSavedBytes = best != null ? originalSize - best.sizeBytes() : 0;
        double bestSavedPct = best != null && originalSize != 0 ? (bestSavedBytes * 100.0) / originalSize : 0d;
        long bestSeconds = best != null ? Math.max(0L, Math.round(best.elapsedMs() / 1000.0)) : 0;

        if (format == Format.JSON) {
            renderJson(inputJar, originalSize, best, bestSavedPct, bestSeconds, byLabel);
            return;
        }

        if (format == Format.MARKDOWN) {
            out.println("## femtojar benchmark");
            out.println();
            out.println("- input: `" + inputJar + "`");
            out.println("- original size: `" + originalSize + "` bytes");
            if (best != null) {
                out.printf(Locale.ROOT, "- best-reduction: `%s` (%.2f%%) in %ds%n", best.benchmarkCase().label(), bestSavedPct, bestSeconds);
            } else {
                out.println("- best-reduction: none (all cases failed)");
            }
            out.println();
            out.println("| mode | size(bytes) | saved(%) | time(s) |");
            out.println("| --- | ---: | ---: | ---: |");
            for (BenchmarkCase benchmarkCase : CASES) {
                BenchmarkResult result = byLabel.get(benchmarkCase.label());
                if (result == null) {
                    continue;
                }
                if (result.failed()) {
                    out.printf("| %s | failed | failed | failed |%n", result.benchmarkCase().label());
                    continue;
                }
                long saved = originalSize - result.sizeBytes();
                double savedPct = originalSize == 0 ? 0d : (saved * 100.0) / originalSize;
                double seconds = result.elapsedMs() / 1000.0;
                out.printf(Locale.ROOT, "| %s | %d | %.2f | %.2f |%n",
                        result.benchmarkCase().label(),
                        result.sizeBytes(),
                        savedPct,
                        seconds);
            }
            return;
        }

        out.println();
        out.println("Result table:");
        out.println("mode                size(bytes)   saved(%)   time(s)");
        for (BenchmarkCase benchmarkCase : CASES) {
            BenchmarkResult result = byLabel.get(benchmarkCase.label());
            if (result == null) {
                continue;
            }
            if (result.failed()) {
                out.printf("%-18s %12s %10s %9s%n", result.benchmarkCase().label(), "failed", "failed", "failed");
                continue;
            }
            long saved = originalSize - result.sizeBytes();
            double savedPct = originalSize == 0 ? 0d : (saved * 100.0) / originalSize;
            double seconds = result.elapsedMs() / 1000.0;
            out.printf(Locale.ROOT, "%-18s %12d %10.2f %9.2f%n",
                    result.benchmarkCase().label(),
                    result.sizeBytes(),
                    savedPct,
                    seconds);
        }
        out.println();
        if (best != null) {
            out.printf(Locale.ROOT, "Best reduction: %s (%.2f%%) in %ds%n", best.benchmarkCase().label(), bestSavedPct, bestSeconds);
        } else {
            out.println("Best reduction: none (all cases failed)");
        }
        BenchmarkResult defaultConfig = byLabel.get("default");
        if (defaultConfig != null && !defaultConfig.failed()) {
            out.printf("Default baseline: %d bytes%n", defaultConfig.sizeBytes());
        }
    }

    private void renderJson(Path inputJar,
                            long originalSize,
                            BenchmarkResult best,
                            double bestSavedPct,
                            long bestSeconds,
                            Map<String, BenchmarkResult> byLabel) {
        out.println("{");
        out.println("  \"input\": \"" + escapeJson(inputJar.toString()) + "\",");
        out.println("  \"originalSize\": " + originalSize + ",");
        out.println("  \"bestMode\": \"" + (best != null ? escapeJson(best.benchmarkCase().label()) : "none") + "\",");
        out.printf(Locale.ROOT, "  \"bestReductionPercent\": %.4f,%n", bestSavedPct);
        out.println("  \"bestTimeSeconds\": " + bestSeconds + ",");
        out.println("  \"results\": [");
        for (int i = 0; i < CASES.size(); i++) {
            BenchmarkCase benchmarkCase = CASES.get(i);
            BenchmarkResult result = byLabel.get(benchmarkCase.label());
            if (result == null) {
                continue;
            }
            out.println("    {");
            out.println("      \"mode\": \"" + escapeJson(result.benchmarkCase().label()) + "\",");
            if (result.failed()) {
                out.println("      \"failed\": true");
            } else {
                long saved = originalSize - result.sizeBytes();
                double savedPct = originalSize == 0 ? 0d : (saved * 100.0) / originalSize;
                double seconds = result.elapsedMs() / 1000.0;
                out.println("      \"sizeBytes\": " + result.sizeBytes() + ",");
                out.printf(Locale.ROOT, "      \"savedPercent\": %.4f,%n", savedPct);
                out.printf(Locale.ROOT, "      \"elapsedSeconds\": %.4f,%n", seconds);
                out.println("      \"elapsedMs\": " + result.elapsedMs());
            }
            out.print("    }");
            out.println(i == CASES.size() - 1 ? "" : ",");
        }
        out.println("  ]");
        out.println("}");
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record BenchmarkCase(CompressionMode mode, boolean proguard) {
        private String label() {
            if (proguard && mode == null) {
                return "proguard";
            }
            if (proguard) {
                return "proguard " + mode.cliValue();
            }
            return mode.cliValue();
        }
    }

    private record BenchmarkResult(BenchmarkCase benchmarkCase, long sizeBytes, long elapsedMs, boolean failed) {
        static BenchmarkResult success(BenchmarkCase benchmarkCase, long sizeBytes, long elapsedMs) {
            return new BenchmarkResult(benchmarkCase, sizeBytes, elapsedMs, false);
        }
        static BenchmarkResult failure(BenchmarkCase benchmarkCase) {
            return new BenchmarkResult(benchmarkCase, -1, 0, true);
        }
    }
}