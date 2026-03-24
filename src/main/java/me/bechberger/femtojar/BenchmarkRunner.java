package me.bechberger.femtojar;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs non-destructive JAR reencoding benchmarks across a matrix of compression settings.
 */
public class BenchmarkRunner {

    public enum Format {
        TEXT,
        MARKDOWN
    }

    private final PrintStream out;
    private final PrintStream err;

    public BenchmarkRunner(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    /**
     * Runs benchmark across compression modes and resource bundling options.
     *
     * @param inputJar path to input JAR
     * @param format output format (text or markdown)
     * @return exit code (0 on success, 1 on failure)
     */
    public int run(Path inputJar, Format format) {
        long originalSize;
        try {
            originalSize = Files.size(inputJar);
        } catch (IOException ex) {
            err.println("Failed to read input JAR size: " + ex.getMessage());
            return 1;
        }

        List<BenchmarkCase> cases = new ArrayList<>();
        for (CompressionMode mode : List.of(CompressionMode.DEFAULT, CompressionMode.ZOPFLI, CompressionMode.MAX)) {
            cases.add(new BenchmarkCase(mode, false, null, -1));
            cases.add(new BenchmarkCase(mode, true, null, -1));
        }
        // Extra default-mode runs with advanced ordering.
        cases.add(new BenchmarkCase(CompressionMode.DEFAULT, false, AdvancedOrderingMode.PACKAGE, -1));
        cases.add(new BenchmarkCase(CompressionMode.DEFAULT, false, AdvancedOrderingMode.HILL_CLIMB, 10000));
        List<BenchmarkResult> results = new ArrayList<>();

        if (format == Format.TEXT) {
            out.println("Benchmarking " + inputJar + " (original size: " + originalSize + " bytes)");
        }

        int workerCount = Math.max(1, Math.min(cases.size(), Runtime.getRuntime().availableProcessors()));
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        List<Future<BenchmarkResult>> futures = new ArrayList<>();
        for (BenchmarkCase benchmarkCase : cases) {
            futures.add(executor.submit((Callable<BenchmarkResult>) () -> runSingleCase(inputJar, benchmarkCase)));
        }
        executor.shutdown();

        for (Future<BenchmarkResult> future : futures) {
            try {
                BenchmarkResult result = future.get();
                results.add(result);
                if (format == Format.TEXT) {
                    out.println("  done: " + result.benchmarkCase().label());
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                err.println("Benchmark interrupted");
                return 1;
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof IOException ioEx) {
                    err.println("Benchmark case failed: " + ioEx.getMessage());
                } else {
                    err.println("Benchmark case failed: " + cause.getMessage());
                }
                futures.forEach(f -> f.cancel(true));
                return 1;
            }
        }

        results.sort(Comparator.comparingLong(BenchmarkResult::sizeBytes));
        if (results.isEmpty()) {
            err.println("No benchmark results produced");
            return 1;
        }

        render(format, inputJar, originalSize, results);
        return 0;
    }

    private BenchmarkResult runSingleCase(Path inputJar, BenchmarkCase benchmarkCase) throws IOException {
        Path tempOut = null;
        try {
            if (inputJar.getParent() != null) {
                tempOut = Files.createTempFile(inputJar.getParent(), "femtojar-bench-", ".jar");
            } else {
                tempOut = Files.createTempFile("femtojar-bench-", ".jar");
            }

            JarReencoder reencoder = new JarReencoder();
            long startNs = System.nanoTime();
            reencoder.rewriteJarBundled(
                    inputJar,
                    tempOut,
                    benchmarkCase.mode.useZopfli(),
                    benchmarkCase.mode.zopfliIterations(),
                    benchmarkCase.bundleResources,
                    "cli-benchmark",
                    benchmarkCase.advancedMode,
                    benchmarkCase.advancedIterations);
            long elapsedNs = System.nanoTime() - startNs;
            long size = Files.size(tempOut);
            long elapsedMs = elapsedNs / 1_000_000;
            BenchmarkResult result = new BenchmarkResult(benchmarkCase, size, elapsedMs);
            return result;
        } finally {
            if (tempOut != null) {
                try {
                    Files.deleteIfExists(tempOut);
                } catch (IOException ignored) {
                    // Ignore temp cleanup issues.
                }
            }
        }
    }

    private void render(Format format, Path inputJar, long originalSize, List<BenchmarkResult> results) {
        BenchmarkResult best = results.get(0);
        BenchmarkResult defaultConfig = results.stream()
                .filter(result -> result.benchmarkCase().mode() == CompressionMode.DEFAULT
                && result.benchmarkCase().bundleResources()
                && result.benchmarkCase().advancedMode() == null)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Benchmark results missing default baseline"));
        double bestSizeDeltaPct = defaultConfig.sizeBytes() == 0
            ? 0d
            : ((defaultConfig.sizeBytes() - best.sizeBytes()) * 100.0) / defaultConfig.sizeBytes();
        double bestTimeDeltaPct = defaultConfig.elapsedMs() == 0
            ? 0d
            : ((best.elapsedMs() - defaultConfig.elapsedMs()) * 100.0) / defaultConfig.elapsedMs();

        if (format == Format.MARKDOWN) {
            out.println("## femtojar benchmark");
            out.println();
            out.println("- input: `" + inputJar + "`");
            out.println("- original size: `" + originalSize + "` bytes");
            out.println("- default baseline: `" + defaultConfig.benchmarkCase().label() + "`");
            out.println("- best setting: `" + best.benchmarkCase().label() + "`");
            out.printf("- best relative size vs default: `%.2f%%`%n", bestSizeDeltaPct);
            out.printf("- best relative time vs default: `%.2f%%`%n", bestTimeDeltaPct);
            out.println();
            out.println("| mode | size(bytes) | saved(bytes) | saved(%) | time(ms) | size vs default(%) | time vs default(%) |");
            out.println("| --- | ---: | ---: | ---: | ---: | ---: | ---: |");
            for (BenchmarkResult result : results) {
                long saved = originalSize - result.sizeBytes();
                double savedPct = originalSize == 0 ? 0d : (saved * 100.0) / originalSize;
                double sizeDeltaPct = defaultConfig.sizeBytes() == 0 ? 0d : ((defaultConfig.sizeBytes() - result.sizeBytes()) * 100.0) / defaultConfig.sizeBytes();
                double timeDeltaPct = defaultConfig.elapsedMs() == 0 ? 0d : ((result.elapsedMs() - defaultConfig.elapsedMs()) * 100.0) / defaultConfig.elapsedMs();
                out.printf("| %s | %d | %d | %.2f | %d | %.2f | %.2f |%n",
                        result.benchmarkCase().label(),
                        result.sizeBytes(),
                        saved,
                        savedPct,
                        result.elapsedMs(),
                        sizeDeltaPct,
                        timeDeltaPct);
            }
            return;
        }

        out.println();
        out.println("Result table (sorted by output size):");
        out.println("mode                                   size(bytes)   saved(bytes)   saved(%)   time(ms)   size-vs-default(%)   time-vs-default(%)");
        for (BenchmarkResult result : results) {
            long saved = originalSize - result.sizeBytes();
            double savedPct = originalSize == 0 ? 0d : (saved * 100.0) / originalSize;
            double sizeDeltaPct = defaultConfig.sizeBytes() == 0 ? 0d : ((defaultConfig.sizeBytes() - result.sizeBytes()) * 100.0) / defaultConfig.sizeBytes();
            double timeDeltaPct = defaultConfig.elapsedMs() == 0 ? 0d : ((result.elapsedMs() - defaultConfig.elapsedMs()) * 100.0) / defaultConfig.elapsedMs();
            out.printf("%-38s %12d %13d %9.2f %10d %20.2f %19.2f%n",
                    result.benchmarkCase().label(),
                    result.sizeBytes(),
                    saved,
                    savedPct,
                    result.elapsedMs(),
                    sizeDeltaPct,
                    timeDeltaPct);
        }

        out.println();
        out.println("Best setting: " + best.benchmarkCase().label());
        out.printf("Best relative size vs default: %.2f%%%n", bestSizeDeltaPct);
        out.printf("Best relative time vs default: %.2f%%%n", bestTimeDeltaPct);
    }

    private record BenchmarkCase(CompressionMode mode, boolean bundleResources,
                                   AdvancedOrderingMode advancedMode, int advancedIterations) {
        private String label() {
            String modeLabel = mode.cliValue();
            String resources = bundleResources ? "resources=on" : "resources=off";
            String advancedLabel = "";
            if (advancedMode == AdvancedOrderingMode.PACKAGE) {
                advancedLabel = ", order=package";
            } else if (advancedMode == AdvancedOrderingMode.HILL_CLIMB) {
                advancedLabel = ", hill-climb=" + advancedIterations;
            }
            return modeLabel + ", " + resources + advancedLabel;
        }
    }

    private record BenchmarkResult(BenchmarkCase benchmarkCase, long sizeBytes, long elapsedMs) {
    }
}
