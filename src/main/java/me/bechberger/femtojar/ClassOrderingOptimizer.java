package me.bechberger.femtojar;

import com.googlecode.pngtastic.core.processing.zopfli.Options;
import com.googlecode.pngtastic.core.processing.zopfli.Zopfli;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Finds the best ordering of class entries within the bundled blob so that
 * the final compressed blob is as small as possible.
 *
 * <p>All methods are static; the class has no instances.
 */
class ClassOrderingOptimizer {

    private ClassOrderingOptimizer() {}

    /**
     * Returns the class ordering that yields the smallest compressed blob.
     *
     * @param classNames             all class entry names (e.g. {@code "com/Foo.class"})
     * @param classEntries           raw bytes per entry name
     * @param bundledResourceEntries resource bytes included in the blob (may be empty)
     * @param useZopfli              whether to use Zopfli for full-quality measurements
     * @param zopfliIterations       Zopfli iteration count
     * @param advancedMode           ordering strategy; {@code null} = lexical
    * @param advancedIterations     iteration budget for hill-climb variants
    * @param parallel               evaluate random swap candidates in parallel
     * @param logger                 optional verbose sink
     */
    static List<String> findBestOrdering(List<String> classNames,
                                         Map<String, byte[]> classEntries,
                                         Map<String, byte[]> bundledResourceEntries,
                                         boolean useZopfli,
                                         int zopfliIterations,
                                         AdvancedOrderingMode advancedMode,
                                         int advancedIterations,
                                 boolean parallel,
                                         PrintStream logger) throws IOException {
        if (advancedMode == null) {
            List<String> order = new ArrayList<>(classNames);
            order.sort(String::compareTo);
            return order;
        }

        return switch (advancedMode) {
            case PACKAGE -> {
                List<String> order = packageAwareOrdering(classNames, classEntries);
                if (logger != null) {
                    long size = measureBlobSize(order, classEntries, bundledResourceEntries, useZopfli, zopfliIterations);
                    logger.println("  [package-aware] size: " + size + " bytes");
                }
                yield order;
            }
            case HILL_CLIMB -> {
                // Start from package-aware ordering
                List<String> bestOrder = packageAwareOrdering(classNames, classEntries);
                // Use fast deflate (level 1) as proxy — relative ranking correlates with real compression
                long baselineSize = measureBlobSizeFast(bestOrder, classEntries, bundledResourceEntries);
                long bestSize = baselineSize;
                if (logger != null) {
                    logger.println("  [hill-climb] package-aware baseline (fast proxy): " + baselineSize + " bytes");
                }

                int n = bestOrder.size();
                int iterations = Math.max(0, advancedIterations);
                int improvements = 0;
                for (int i = 0; i < iterations && n > 1; i++) {
                    Candidate candidate = pickBestSwapCandidate(bestOrder, n, parallel,
                            swapped -> measureBlobSizeFast(swapped, classEntries, bundledResourceEntries));
                    long size = candidate.size();
                    if (size < bestSize) {
                        long delta = bestSize - size;
                        double deltaPct = bestSize == 0 ? 0d : (delta * 100.0) / bestSize;
                        improvements++;
                        bestSize = size;
                        bestOrder = candidate.order();
                        if (logger != null) {
                            long totalDelta = baselineSize - bestSize;
                            double totalPct = baselineSize == 0 ? 0d : (totalDelta * 100.0) / baselineSize;
                            logger.printf("  [hill-climb #%d] step: -%d bytes (%.2f%%), overall: -%d bytes (%.2f%%) -> %d bytes%n",
                                    i + 1, delta, deltaPct, totalDelta, totalPct, bestSize);
                        }
                    }
                }
                if (logger != null) {
                    long realSize = measureBlobSize(bestOrder, classEntries, bundledResourceEntries, useZopfli, zopfliIterations);
                    long totalDelta = baselineSize - bestSize;
                    double totalPct = baselineSize == 0 ? 0d : (totalDelta * 100.0) / baselineSize;
                    logger.printf("  [hill-climb done] %d improvements in %d iterations: -%d bytes (%.2f%%) proxy, real size: %d bytes%n",
                            improvements, iterations, totalDelta, totalPct, realSize);
                }
                yield bestOrder;
            }
        };
    }

    @FunctionalInterface
    private interface SizeFunction {
        long measure(List<String> classOrder) throws IOException;
    }

    private record Candidate(List<String> order, long size) {
    }

    private static Candidate pickBestSwapCandidate(List<String> baseOrder,
                                                   int n,
                                                   boolean parallel,
                                                   SizeFunction sizeFunction) throws IOException {
        if (!parallel) {
            List<String> candidate = new ArrayList<>(baseOrder);
            int a = ThreadLocalRandom.current().nextInt(n);
            int b = ThreadLocalRandom.current().nextInt(n - 1);
            if (b >= a) {
                b++;
            }
            Collections.swap(candidate, a, b);
            return new Candidate(candidate, sizeFunction.measure(candidate));
        }

        int candidates = Math.max(2, Runtime.getRuntime().availableProcessors());
        try {
            return IntStream.range(0, candidates)
                    .parallel()
                    .mapToObj(ignored -> {
                        List<String> candidate = new ArrayList<>(baseOrder);
                        int a = ThreadLocalRandom.current().nextInt(n);
                        int b = ThreadLocalRandom.current().nextInt(n - 1);
                        if (b >= a) {
                            b++;
                        }
                        Collections.swap(candidate, a, b);
                        try {
                            return new Candidate(candidate, sizeFunction.measure(candidate));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .min(Comparator.comparingLong(Candidate::size))
                    .orElseThrow(() -> new IllegalStateException("No swap candidates generated"));
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof IOException io) {
                throw io;
            }
            throw ex;
        }
    }

    /**
     * Orders classes by package (lexical), then by file size (ascending) within each package.
     * Classes in the same package share constant-pool strings, so placing them adjacent
     * keeps repeated patterns inside deflate's 32 KB sliding window.
     */
    static List<String> packageAwareOrdering(List<String> classNames,
                                              Map<String, byte[]> classEntries) {
        List<String> order = new ArrayList<>(classNames);
        order.sort(Comparator
                .<String, String>comparing(name -> {
                    int lastSlash = name.lastIndexOf('/');
                    return lastSlash >= 0 ? name.substring(0, lastSlash) : "";
                })
                .thenComparingInt(name -> classEntries.get(name).length));
        return order;
    }

    /**
     * Measures the compressed size of the blob produced by the given class ordering.
     * Uses the same serialisation path as the real encoder so the result is accurate.
     */
    static long measureBlobSize(List<String> classOrder,
                                Map<String, byte[]> classEntries,
                                Map<String, byte[]> bundledResourceEntries,
                                boolean useZopfli,
                                int zopfliIterations) throws IOException {
        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        Map<String, int[]> classIndex = new HashMap<>();
        Map<String, int[]> resourceIndex = new HashMap<>();

        for (String classEntryName : classOrder) {
            byte[] content = classEntries.get(classEntryName);
            int offset = blob.size();
            blob.write(content);
            String className = classEntryName.substring(0, classEntryName.length() - 6).replace('/', '.');
            classIndex.put(className, new int[]{offset, content.length});
        }

        if (!bundledResourceEntries.isEmpty()) {
            List<String> resourceNames = new ArrayList<>(bundledResourceEntries.keySet());
            resourceNames.sort(String::compareTo);
            for (String resourceName : resourceNames) {
                byte[] content = bundledResourceEntries.get(resourceName);
                int offset = blob.size();
                blob.write(content);
                resourceIndex.put(resourceName, new int[]{offset, content.length});
            }
        }

        byte[] indexData = serializeIndex(classIndex, resourceIndex, blob.size());
        byte[] packedBlob = packBlob(indexData, blob.toByteArray());

        return useZopfli
                ? compressWithZopfli(packedBlob, zopfliIterations).length
                : compressWithDeflater(packedBlob).length;
    }

    /**
     * Fast proxy measurement using Deflater level 1. Much faster than full compression;
     * relative ordering of candidates correlates well with real compression.
     */
    private static long measureBlobSizeFast(List<String> classOrder,
                                            Map<String, byte[]> classEntries,
                                            Map<String, byte[]> bundledResourceEntries) throws IOException {
        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        for (String classEntryName : classOrder) {
            blob.write(classEntries.get(classEntryName));
        }
        if (!bundledResourceEntries.isEmpty()) {
            List<String> resourceNames = new ArrayList<>(bundledResourceEntries.keySet());
            resourceNames.sort(String::compareTo);
            for (String resourceName : resourceNames) {
                blob.write(bundledResourceEntries.get(resourceName));
            }
        }
        // Skip index serialisation — it doesn't change the relative ranking
        byte[] data = blob.toByteArray();
        Deflater deflater = new Deflater(Deflater.BEST_SPEED, true);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length);
            try (DeflaterOutputStream dos = new DeflaterOutputStream(baos, deflater, 8192)) {
                dos.write(data);
            }
            return baos.size();
        } finally {
            deflater.end();
        }
    }

    // -------------------------------------------------------------------------
    // Shared compression / serialisation utilities (package-private)
    // -------------------------------------------------------------------------

    /** Compresses {@code data} using Zopfli in ZLIB output format. */
    static byte[] compressWithZopfli(byte[] data, int iterations) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Options options = new Options(Options.OutputFormat.ZLIB, Options.BlockSplitting.FIRST, iterations);
        Zopfli zopfli = new Zopfli(8 * 1024 * 1024);
        zopfli.compress(options, data, baos);
        return baos.toByteArray();
    }

    /** Compresses {@code data} using standard Deflater at best compression with ZLIB wrapping. */
    static byte[] compressWithDeflater(byte[] data) throws IOException {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, false);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length);
            try (DeflaterOutputStream dos = new DeflaterOutputStream(baos, deflater, 8192)) {
                dos.write(data);
            }
            return baos.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /** Packs index and class-blob data into the {@code [indexSize][index][blob]} wire format. */
    static byte[] packBlob(byte[] indexData, byte[] classBlobData) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(indexData.length + classBlobData.length + 4);
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeInt(indexData.length);
            dos.write(indexData);
            dos.write(classBlobData);
        }
        return baos.toByteArray();
    }

    /**
     * Serializes the index used by the runtime loader:
     * {@code [version(int)][numClasses(int)][className(UTF) offset(int) length(int)]*
     * [numResources(int)][name(UTF) offset(int) length(int)]* [totalSize(int)]}
     */
    static byte[] serializeIndex(Map<String, int[]> classIndex,
                                  Map<String, int[]> resourceIndex,
                                  int totalUncompressedSize) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeInt(1); // format version
            dos.writeInt(classIndex.size());
            for (Map.Entry<String, int[]> entry : classIndex.entrySet()) {
                dos.writeUTF(entry.getKey());
                dos.writeInt(entry.getValue()[0]); // offset
                dos.writeInt(entry.getValue()[1]); // length
            }
            dos.writeInt(resourceIndex.size());
            for (Map.Entry<String, int[]> entry : resourceIndex.entrySet()) {
                dos.writeUTF(entry.getKey());
                dos.writeInt(entry.getValue()[0]); // offset
                dos.writeInt(entry.getValue()[1]); // length
            }
            dos.writeInt(totalUncompressedSize);
        }
        return baos.toByteArray();
    }
}
