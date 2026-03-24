package me.bechberger.femtojar.rt;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.Manifest;
import java.util.zip.InflaterInputStream;

/**
 * Bootstrap class that decompresses the bundled class blob and redirects execution
 * to the original Main-Class using a nested custom ClassLoader.
 *
 * This becomes the Main-Class in the manifest.
 */
public class BundleBootstrap extends ClassLoader {
    // Resource names
    private static final String BLOB_RESOURCE = "__classes.zlib";
    private static final String MANIFEST_RESOURCE = "META-INF/MANIFEST.MF";
    private static final String ORIGINAL_MAIN_CLASS_ATTR = "X-Original-Main-Class";

    private Map<String, int[]> classIndex;
    private Map<String, int[]> resourceIndex;
    private int totalUncompressedSize;
    private byte[] classData;

    public static void main(String[] args) throws Exception {
        new BundleBootstrap().start(args);
    }

    private void start(String[] args) throws Exception {
        // Read and parse packed blob: [indexSize][index][classBlob]
        readPackedBlob();

        Thread.currentThread().setContextClassLoader(this);

        // Read original Main-Class from manifest
        String originalMainClass = readOriginalMainClass();

        // Load and invoke original main
        Class<?> mainClass = Class.forName(originalMainClass, true, this);
        Method mainMethod = mainClass.getMethod("main", String[].class);
        mainMethod.invoke(null, (Object) args);
    }

    /**
     * Reads and parses packed blob resource.
        * Format: [int indexSize][indexBytes][classBlobBytes].
     * Index format: version, class entries, resource entries, total size.
     */
    private void readPackedBlob() throws IOException {
        classIndex = new HashMap<>();
        resourceIndex = new HashMap<>();

        byte[] packed = decompressBlobFully();
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(packed))) {
            int indexSize = dis.readInt();
            int classBlobOffset = 4 + indexSize;
            if (indexSize < 0 || classBlobOffset < 4 || classBlobOffset > packed.length) {
                throw new IOException("Invalid packed blob header");
            }

            int version = dis.readInt();
            if (version != 1) {
                throw new IOException("Unsupported index format version: " + version);
            }

            int numEntries = dis.readInt();
            for (int i = 0; i < numEntries; i++) {
                String className = dis.readUTF();
                int offset = dis.readInt();
                int length = dis.readInt();
                classIndex.put(className, new int[]{offset, length});
            }

            int numResources = dis.readInt();
            for (int i = 0; i < numResources; i++) {
                String resourceName = dis.readUTF();
                int offset = dis.readInt();
                int length = dis.readInt();
                resourceIndex.put(resourceName, new int[]{offset, length});
            }
            totalUncompressedSize = dis.readInt();

            int classBlobLength = packed.length - classBlobOffset;
            if (classBlobLength != totalUncompressedSize) {
                throw new IOException("Invalid packed blob length: expected " + totalUncompressedSize +
                        " but got " + classBlobLength);
            }
            classData = new byte[classBlobLength];
            System.arraycopy(packed, classBlobOffset, classData, 0, classBlobLength);
        }
    }

    /**
     * Decompresses the __classes.zlib blob using InflaterInputStream (ZLIB format).
     */
    private byte[] decompressBlobFully() throws IOException {
        try (InputStream is = ClassLoader.getSystemResourceAsStream(BLOB_RESOURCE);
             InflaterInputStream iis = new InflaterInputStream(is)) {
            return iis.readAllBytes();
        }
    }

    /**
     * Reads the original Main-Class attribute from the manifest.
     */
    private String readOriginalMainClass() throws IOException {
        try (InputStream is = ClassLoader.getSystemResourceAsStream(MANIFEST_RESOURCE)) {
            Manifest manifest = new Manifest(is);
            String mainClass = manifest.getMainAttributes().getValue(ORIGINAL_MAIN_CLASS_ATTR);
            if (mainClass == null) {
                throw new IOException("Manifest missing " + ORIGINAL_MAIN_CLASS_ATTR + " attribute");
            }
            return mainClass;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        int[] entry = classIndex.get(name);
        if (entry == null) {
            throw new ClassNotFoundException(name);
        }
        return defineClass(name, classData, entry[0], entry[1]);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        int[] entry = resourceIndex.get(name);
        if (entry != null) {
            return new ByteArrayInputStream(classData, entry[0], entry[1]);
        }
        return super.getResourceAsStream(name);
    }
}
