package me.bechberger.femtojar;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs non-destructive JAR reencoding benchmarks across a matrix of compression settings.
 */
public class BenchmarkRunner {

    public enum Format {
        TEXT,
        MARKDOWN
    }

    public static class Result {
        public final Map<String, int[]> classIndex;
        public final Map<String, int[]> resourceIndex;
        public final long elapsedMs;
        public final long sizeBytes;
        public final String settingLabel;

        public Result(String settingLabel, long sizeBytes, long elapsedMs) {
            this.settingLabel = settingLabel;
            this.sizeBytes = sizeBytes;
            this.elapsedMs = elapsedMs;
            this.classIndex = new HashMap<>();
            this.resourceIndex = new HashMap<>();
        }
    }

    private final PrintStream out;
    private final PrintStream err;

    public BenchmarkRunner(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    /**
     * Runs benchmark across deflate/zopfli with various iterations and resource bundling options.
     *
     * @param inputJar path to input JAR
     * @param zopfliIterations list of zopfli iteration counts to test (can be null)
     * @param format output format (text or markdown)
     * @return exit code (0 on success, 1 on failure)
     */
    public int run(Path inputJar, List<Integer> zopfliIterations, Format format) {
        long originalSize;
        try {
            originalSize = Files.size(inputJar);
        } catch (IOException ex) {
            err.println("Failed to read input JAR size: " + ex.getMessage());
            return 1;
        }

        if (zopfliIterations == null || zopfliIterations.isEmpty()) {
            zopfliIterations = List.of(15, 50, 100);
        }

        List<BenchmarkCase> cases = new ArrayList<>();
        cases.add(new BenchmarkCase(false, 0, false));
        cases.add(new BenchmarkCase(false, 0, true));
        for (int iterations : zopfliIterations) {
            cases.add(new BenchmarkCase(true, iterations, false));
            cases.add(new BenchmarkCase(true, iterations, true));
        }

        List<BenchmarkResult> results = new ArrayList<>();
        JarReencoder reencoder = new JarReencoder();

        if (format == Format.TEXT) {
            out.println("Benchmarking " + inputJar + " (original size: " + originalSize + " bytes)");
        }

        for (BenchmarkCase benchmarkCase : cases) {
            Path tempOut = null;
            try {
                tempOut = Files.createTempFile(inputJar.getParent(), "femtojar-bench-", ".jar");
                long startNs = System.nanoTime();
                reencoder.rewriteJarBundled(
                        inputJar,
                        tempOut,
                        benchmarkCase.useZopfli,
                        benchmarkCase.zopfliIterations,
                        benchmarkCase.bundleResources,
                        "cli-benchmark");
                long elapsedNs = System.nanoTime() - startNs;
                long size = Files.size(tempOut);
                results.add(new BenchmarkResult(benchmarkCase, size, elapsedNs / 1_000_000));
                if (format == Format.TEXT) {
                    out.println("  done: " + benchmarkCase.label());
                }
            } catch (IOException ex) {
                err.println("Benchmark case failed (" + benchmarkCase.label() + "): " + ex.getMessage());
                return 1;
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

        results.sort(Comparator.comparingLong(BenchmarkResult::sizeBytes));
        if (results.isEmpty()) {
            err.println("No benchmark results produced");
            return 1;
        }

        render(format, inputJar, originalSize, results);
        return 0;
    }

    private void render(Format format, Path inputJar, long originalSize, List<BenchmarkResult> results) {
        BenchmarkResult best = results.get(0);
        if (format == Format.MARKDOWN) {
            out.println("## femtojar benchmark");
            out.println();
            out.println("- input: `" + inputJar + "`");
            out.println("- original size: `" + originalSize + "` bytes");
            out.println("- best setting: `" + best.benchmarkCase().label() + "`");
            out.println();
            out.println("| mode | size(bytes) | saved(bytes) | saved(%) | time(ms) |");
            out.println("| --- | ---: | ---: | ---: | ---: |");
            for (BenchmarkResult result : results) {
                long saved = originalSize - result.sizeBytes();
                double savedPct = originalSize == 0 ? 0d : (saved * 100.0) / originalSize;
                out.printf("| %s | %d | %d | %.2f | %d |%n",
                        result.benchmarkCase().label(),
                        result.sizeBytes(),
                        saved,
                        savedPct,
                        result.elapsedMs());
            }
            return;
        }

        out.println();
        out.println("Result table (sorted by output size):");
        out.println("mode                                   size(bytes)   saved(bytes)   saved(%)   time(ms)");
        for (BenchmarkResult result : results) {
            long saved = originalSize - result.sizeBytes();
            double savedPct = originalSize == 0 ? 0d : (saved * 100.0) / originalSize;
            out.printf("%-38s %12d %13d %9.2f %10d%n",
                    result.benchmarkCase().label(),
                    result.sizeBytes(),
                    saved,
                    savedPct,
                    result.elapsedMs());
        }

        out.println();
        out.println("Best setting: " + best.benchmarkCase().label());
    }

    private record BenchmarkCase(boolean useZopfli, int zopfliIterations, boolean bundleResources) {
        private String label() {
            String mode = useZopfli ? "zopfli(" + zopfliIterations + ")" : "deflate";
            String resources = bundleResources ? "resources=on" : "resources=off";
            return mode + ", " + resources;
        }
    }

    private record BenchmarkResult(BenchmarkCase benchmarkCase, long sizeBytes, long elapsedMs) {
    }
}
