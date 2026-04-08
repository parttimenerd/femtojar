package me.bechberger.femtojar;

import com.googlecode.pngtastic.core.processing.zopfli.Options;
import com.googlecode.pngtastic.core.processing.zopfli.Zopfli;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class JarReencoder {

    private static final int BLOB_FLAG_BUNDLED_RESOURCES = 1;

    public record ReencodeOptions(boolean useZopfli,
                                  int zopfliIterations,
                                  boolean bundleResources,
                                  String femtojarVersion,
                                  boolean parallel,
                                  PrintStream logger) {
        public ReencodeOptions {
            femtojarVersion = femtojarVersion == null || femtojarVersion.isBlank() ? "unknown" : femtojarVersion;
        }

        public ReencodeOptions(boolean useZopfli,
                               int zopfliIterations,
                               boolean bundleResources,
                               String femtojarVersion) {
            this(useZopfli, zopfliIterations, bundleResources, femtojarVersion, false, null);
        }
    }

    public ReencodeResult reencodeInPlace(Path jarPath) throws IOException {
        Objects.requireNonNull(jarPath, "jarPath");
        if (!Files.exists(jarPath) || !Files.isRegularFile(jarPath)) {
            throw new IOException("JAR file does not exist: " + jarPath);
        }

        long originalSize = Files.size(jarPath);
        Path tempFile = Files.createTempFile(jarPath.getParent(), jarPath.getFileName().toString(), ".tmp");
        try {
            rewriteJar(jarPath, tempFile);
            long newSize = Files.size(tempFile);
            moveIntoPlace(tempFile, jarPath);
            return new ReencodeResult(originalSize, newSize);
        } catch (IOException e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }
    }

    /**
     * Re-encodes a JAR file in-place with bundled class compression.
     */
    public ReencodeResult reencodeInPlaceBundled(Path jarPath, boolean useZopfli,
                                                 int zopfliIterations,
                                                 boolean bundleResources) throws IOException {
        return reencodeInPlaceBundled(jarPath, new ReencodeOptions(
                useZopfli, zopfliIterations, bundleResources, detectFemtojarVersion()));
    }

    public ReencodeResult reencodeInPlaceBundled(Path jarPath, ReencodeOptions options) throws IOException {
        Objects.requireNonNull(jarPath, "jarPath");
        Objects.requireNonNull(options, "options");
        if (!Files.exists(jarPath) || !Files.isRegularFile(jarPath)) {
            throw new IOException("JAR file does not exist: " + jarPath);
        }

        long originalSize = Files.size(jarPath);
        Path tempFile = Files.createTempFile(jarPath.getParent(), jarPath.getFileName().toString(), ".tmp");
        try {
            rewriteJarBundled(jarPath, tempFile, options);
            long newSize = Files.size(tempFile);
            moveIntoPlace(tempFile, jarPath);
            return new ReencodeResult(originalSize, newSize);
        } catch (IOException e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }
    }

    void rewriteJarBundled(Path sourceJar, Path targetJar, ReencodeOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        try (JarFile jarFile = new JarFile(sourceJar.toFile())) {
            if (isAlreadyBundledJar(jarFile)) {
                Files.copy(sourceJar, targetJar, StandardCopyOption.REPLACE_EXISTING);
                return;
            }

            // Read the original manifest
            Manifest sourceManifest = jarFile.getManifest();
            if (sourceManifest == null) {
                throw new IOException("JAR file has no manifest");
            }
            String originalMainClass = sourceManifest.getMainAttributes().getValue("Main-Class");
            if (originalMainClass == null) {
                throw new IOException("Manifest missing Main-Class attribute");
            }

            // Separate .class entries from resources
            Map<String, int[]> classIndex = new HashMap<>();
            Map<String, int[]> resourceIndex = new HashMap<>();
            Map<String, byte[]> classEntries = new HashMap<>();
            Map<String, byte[]> bundledResourceEntries = new HashMap<>();
            Map<String, ResourceEntry> resources = new LinkedHashMap<>();
            Set<String> seenEntries = new LinkedHashSet<>();

            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry inEntry = entries.nextElement();

                // Skip directories and the original manifest (we write a replacement manifest).
                if (inEntry.isDirectory() || "META-INF/MANIFEST.MF".equalsIgnoreCase(inEntry.getName())) {
                    continue;
                }

                // Skip femtojar internal entries so re-encoding already bundled jars is idempotent.
                if (isFemtojarInternalEntry(inEntry.getName())) {
                    continue;
                }

                // Skip duplicates
                if (!seenEntries.add(inEntry.getName())) {
                    continue;
                }

                byte[] content = readAllBytes(jarFile.getInputStream(inEntry));

                if (inEntry.getName().endsWith(".class")) {
                    classEntries.put(inEntry.getName(), content);
                } else if (options.bundleResources() && !isMetaInfEntry(inEntry.getName())) {
                    bundledResourceEntries.put(inEntry.getName(), content);
                } else {
                    // Store resource
                    resources.put(inEntry.getName(), new ResourceEntry(inEntry.getTime(), content));
                }
            }

            // Keep class order deterministic and stable.
            List<String> bestClassOrder = new ArrayList<>(classEntries.keySet());
            Collections.sort(bestClassOrder);

            // Build blob with best ordering
            ByteArrayOutputStream blob = new ByteArrayOutputStream();
            for (String classEntryName : bestClassOrder) {
                byte[] content = classEntries.get(classEntryName);
                String className = classEntryName.substring(0, classEntryName.length() - 6).replace('/', '.');
                int offset = blob.size();
                blob.write(content);
                classIndex.put(className, new int[]{offset, content.length});
            }

            if (options.bundleResources()) {
                List<String> resourceNames = new ArrayList<>(bundledResourceEntries.keySet());
                resourceNames.sort(String::compareTo);
                for (String resourceName : resourceNames) {
                    byte[] content = bundledResourceEntries.get(resourceName);
                    int offset = blob.size();
                    blob.write(content);
                    resourceIndex.put(resourceName, new int[]{offset, content.length});
                }
            }

            // Serialize the index
            byte[] indexData = serializeIndex(classIndex, resourceIndex, blob.size());

            // New blob format: [config][indexSize][index][classBlob]
            byte[] packedBlob = packBlob(indexData, blob.toByteArray(), options.bundleResources());

            // Compress the packed blob in one stream
            byte[] compressedBlob = compressClassBlob(
                    packedBlob, options.useZopfli(), options.zopfliIterations());

            // Write the output JAR
            try (ZipOutputStream output = new ZipOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(targetJar)))) {
                output.setLevel(Deflater.BEST_COMPRESSION);

                // Write modified manifest
                writeManifest(output, originalMainClass, options.femtojarVersion());

                // Write bootstrap classes
                writeBootstrapClasses(output);

                // Write compressed class blob (STORED)
                writeStoredEntry(output, "__classes.zlib", compressedBlob);

                // Write resources using best method per entry
                for (Map.Entry<String, ResourceEntry> resource : resources.entrySet()) {
                    writeBestEntry(output, resource.getKey(), resource.getValue().time(), resource.getValue().content());
                }

                output.finish();
            }
        }
    }

    private byte[] compressClassBlob(byte[] data, boolean useZopfli, int zopfliIterations) throws IOException {
        return useZopfli
                ? compressWithZopfli(data, zopfliIterations)
                : compressWithDeflater(data);
    }

    private static byte[] compressWithZopfli(byte[] data, int iterations) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Options options = new Options(Options.OutputFormat.ZLIB, Options.BlockSplitting.FIRST, iterations);
        Zopfli zopfli = new Zopfli(8 * 1024 * 1024);
        zopfli.compress(options, data, baos);
        return baos.toByteArray();
    }

    private static byte[] compressWithDeflater(byte[] data) throws IOException {
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

    private static byte[] packBlob(byte[] indexData, byte[] classBlobData, boolean bundleResources) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(indexData.length + classBlobData.length + 5);
        try (java.io.DataOutputStream dos = new java.io.DataOutputStream(baos)) {
            int config = bundleResources ? BLOB_FLAG_BUNDLED_RESOURCES : 0;
            dos.writeByte(config);
            dos.writeInt(indexData.length);
            dos.write(indexData);
            dos.write(classBlobData);
        }
        return baos.toByteArray();
    }

    private static byte[] serializeIndex(Map<String, int[]> classIndex,
                                         Map<String, int[]> resourceIndex,
                                         int totalUncompressedSize) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.io.DataOutputStream dos = new java.io.DataOutputStream(baos)) {
            dos.writeInt(1);
            dos.writeInt(classIndex.size());
            for (Map.Entry<String, int[]> entry : classIndex.entrySet()) {
                dos.writeUTF(entry.getKey());
                dos.writeInt(entry.getValue()[0]);
                dos.writeInt(entry.getValue()[1]);
            }
            dos.writeInt(resourceIndex.size());
            for (Map.Entry<String, int[]> entry : resourceIndex.entrySet()) {
                dos.writeUTF(entry.getKey());
                dos.writeInt(entry.getValue()[0]);
                dos.writeInt(entry.getValue()[1]);
            }
            dos.writeInt(totalUncompressedSize);
        }
        return baos.toByteArray();
    }

    private static boolean isMetaInfEntry(String entryName) {
        return entryName.startsWith("META-INF/");
    }

    private static boolean isFemtojarInternalEntry(String entryName) {
        return entryName.startsWith("__classes.")
                || entryName.startsWith("me/bechberger/femtojar/rt/");
    }

    private static boolean isAlreadyBundledJar(JarFile jarFile) throws IOException {
        boolean hasBlob = jarFile.getEntry("__classes.zlib") != null;
        if (!hasBlob) {
            return false;
        }

        Manifest manifest = jarFile.getManifest();
        if (manifest == null) {
            return false;
        }
        String mainClass = manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
        return "me.bechberger.femtojar.rt.BundleBootstrap".equals(mainClass);
    }

    private record ResourceEntry(long time, byte[] content) {
    }

    private static String detectFemtojarVersion() {
        String version = JarReencoder.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "unknown" : version;
    }

    /**
     * Writes a modified manifest with updated Main-Class and X-Original-Main-Class.
     */
    private void writeManifest(ZipOutputStream output, String originalMainClass,
                               String femtojarVersion) throws IOException {
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.put(Attributes.Name.MAIN_CLASS, "me.bechberger.femtojar.rt.BundleBootstrap");
        attrs.put(new Attributes.Name("X-Original-Main-Class"), originalMainClass);
        attrs.put(new Attributes.Name("X-Femtojar-Version"),
                femtojarVersion == null || femtojarVersion.isBlank() ? "unknown" : femtojarVersion);

        ZipEntry entry = new ZipEntry("META-INF/MANIFEST.MF");
        output.putNextEntry(entry);
        manifest.write(output);
        output.closeEntry();
    }

    /**
     * Writes bootstrap classes by reading them from the classpath and compressing them.
     */
    private void writeBootstrapClasses(ZipOutputStream output) throws IOException {
        List<String> runtimeClasses = List.of(
                "me/bechberger/femtojar/rt/BundleBootstrap.class",
                "me/bechberger/femtojar/rt/BundleBootstrap$FemtoJarURLStreamHandler.class",
                "me/bechberger/femtojar/rt/BundleBootstrap$FemtoJarURLConnection.class");
        for (String runtimeClass : runtimeClasses) {
            byte[] classBytes = readClassFromResource(runtimeClass);
            writeBestEntry(output, runtimeClass, 0, classBytes);
        }
    }

    /**
     * Reads a class file from the classpath (used for bootstrap classes).
     */
    private byte[] readClassFromResource(String resourcePath) throws IOException {
        try (InputStream is = JarReencoder.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Bootstrap class not found: " + resourcePath);
            }
            return is.readAllBytes();
        }
    }

    /**
     * Writes an entry with STORED method (no compression), with proper CRC and size.
     */
    private void writeStoredEntry(ZipOutputStream output, String name, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(content.length);
        entry.setCompressedSize(content.length);
        CRC32 crc = new CRC32();
        crc.update(content);
        entry.setCrc(crc.getValue());
        output.putNextEntry(entry);
        output.write(content);
        output.closeEntry();
    }

    void rewriteJar(Path sourceJar, Path targetJar) throws IOException {
        try (JarFile jarFile = new JarFile(sourceJar.toFile());
             ZipOutputStream output = new ZipOutputStream(
                     new BufferedOutputStream(Files.newOutputStream(targetJar)))) {

            // Maximum DEFLATE compression
            output.setLevel(Deflater.BEST_COMPRESSION);

            // Track seen entry names to skip duplicates
            Set<String> seenEntries = new LinkedHashSet<>();

            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry inEntry = entries.nextElement();

                // Skip directory entries – they are optional in JARs and waste space
                if (inEntry.isDirectory()) {
                    continue;
                }

                // Deduplicate: keep only the first occurrence of each entry name
                if (!seenEntries.add(inEntry.getName())) {
                    continue;
                }

                byte[] content = readAllBytes(jarFile.getInputStream(inEntry));
                writeBestEntry(output, inEntry.getName(), inEntry.getTime(), content);
            }

            output.finish();
        }
    }

    /**
     * Writes an entry using whichever method (DEFLATED or STORED) produces fewer bytes.
     * For entries where DEFLATE at level 9 doesn't shrink the data (e.g. already-compressed
     * images, native libs), STORED avoids the ~5-byte deflate overhead per entry.
     */
    private static void writeBestEntry(ZipOutputStream output, String name,
                                       long time, byte[] content) throws IOException {
        // Try compressing with DEFLATE level 9
        byte[] deflated = deflateBytes(content);

        // Build a minimal entry – no extra fields, no comments
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(time);

        if (deflated.length < content.length) {
            // Deflated is smaller – let the stream compress it
            entry.setMethod(ZipEntry.DEFLATED);
            entry.setSize(content.length);
        } else {
            // Stored is smaller or equal – write raw bytes
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(content.length);
            entry.setCompressedSize(content.length);
            CRC32 crc = new CRC32();
            crc.update(content);
            entry.setCrc(crc.getValue());
        }
        output.putNextEntry(entry);
        output.write(content);
        output.closeEntry();
    }

    /**
     * Deflate the content at maximum compression level to measure the compressed size.
     */
    private static byte[] deflateBytes(byte[] data) throws IOException {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
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

    private static byte[] readAllBytes(InputStream inputStream) throws IOException {
        try (InputStream in = inputStream) {
            return in.readAllBytes();
        }
    }

    private static void moveIntoPlace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record ReencodeResult(long originalSize, long newSize) {
    }
}