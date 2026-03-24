package me.bechberger.femtojar;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarReencoderTest {

    @Test
    void reencodesJarAndPreservesContent() throws Exception {
        Path tempDir = Files.createTempDirectory("femtojar-test");
        Path jarPath = tempDir.resolve("sample.jar");
        createSampleJar(jarPath);

        Map<String, byte[]> before = readJarContents(jarPath);

        JarReencoder reencoder = new JarReencoder();
        JarReencoder.ReencodeResult result = reencoder.reencodeInPlace(jarPath);

        assertTrue(Files.exists(jarPath));
        assertTrue(result.originalSize() > 0);
        assertTrue(result.newSize() > 0);

        Map<String, byte[]> after = readJarContents(jarPath);
        assertEquals(before.keySet(), after.keySet());
        for (String name : before.keySet()) {
            assertTrue(Arrays.equals(before.get(name), after.get(name)), "Content changed for entry: " + name);
        }
    }

    @Test
    void reencodesBundledJarAndPreservesStructure() throws Exception {
        Path tempDir = Files.createTempDirectory("femtojar-bundled-test");
        Path jarPath = tempDir.resolve("bundled.jar");
        createSampleBundledJar(jarPath);

        JarReencoder reencoder = new JarReencoder();
        JarReencoder.ReencodeResult result = reencoder.reencodeInPlaceBundled(jarPath, false, 100, true);

        assertTrue(Files.exists(jarPath));
        assertTrue(result.originalSize() > 0);
        assertTrue(result.newSize() > 0);

        // Verify the output structure
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            // Check manifest has correct main class
            Manifest manifest = jar.getManifest();
            assertTrue(manifest != null);
            String mainClass = manifest.getMainAttributes().getValue("Main-Class");
            assertEquals("me.bechberger.femtojar.rt.BundleBootstrap", mainClass);
            String originalMainClass = manifest.getMainAttributes().getValue("X-Original-Main-Class");
            assertEquals("com.example.Main", originalMainClass);
            String femtojarVersion = manifest.getMainAttributes().getValue("X-Femtojar-Version");
            assertNotNull(femtojarVersion, "Manifest should include X-Femtojar-Version");

            // Check bundled files exist
            assertTrue(jar.getEntry("__classes.zlib") != null, "Missing __classes.zlib");
            assertTrue(jar.getEntry("me/bechberger/femtojar/rt/BundleBootstrap.class") != null, "Missing BundleBootstrap");

            // Check resources are preserved
            ZipEntry resourceEntry = jar.getEntry("application.properties");
            assertTrue(resourceEntry == null, "Resource should be bundled into blob when bundleResources=true");

            // META-INF resources remain as regular entries
            assertTrue(jar.getEntry("META-INF/services/com.example.Service") != null,
                    "META-INF resources should remain normal entries");
        }
    }

    @Test
    void reencodesAlreadyBundledJarWithoutGrowing() throws Exception {
        Path tempDir = Files.createTempDirectory("femtojar-bundled-idempotent-test");
        Path jarPath = tempDir.resolve("bundled-twice.jar");
        createSampleBundledJar(jarPath);

        JarReencoder reencoder = new JarReencoder();
        reencoder.reencodeInPlaceBundled(jarPath, true, 100, true);
        long firstPassSize = Files.size(jarPath);

        reencoder.reencodeInPlaceBundled(jarPath, true, 100, true);
        long secondPassSize = Files.size(jarPath);

        assertEquals(firstPassSize, secondPassSize, "Bundled re-encoding should be idempotent");
    }

    @Test
    void reencodesAlreadyBundledJarWithoutDuplicateInternalEntries() throws Exception {
        Path tempDir = Files.createTempDirectory("femtojar-bundled-idempotent-test");
        Path jarPath = tempDir.resolve("bundled-twice.jar");
        createSampleBundledJar(jarPath);

        JarReencoder reencoder = new JarReencoder();
        reencoder.reencodeInPlaceBundled(jarPath, true, 100, true);
        // Must not fail with duplicate entry errors on second pass.
        reencoder.reencodeInPlaceBundled(jarPath, true, 100, true);

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            assertTrue(jar.getEntry("__classes.zlib") != null, "Missing __classes.zlib after second pass");
        }
    }

    private static void createSampleJar(Path jarPath) throws IOException {
        byte[] compressible = "abc123".repeat(16_384).getBytes();
        byte[] stored = "this entry stays stored".getBytes();

        try (OutputStream out = Files.newOutputStream(jarPath);
             ZipOutputStream zipOut = new ZipOutputStream(out)) {
            ZipEntry deflated = new ZipEntry("data.txt");
            zipOut.putNextEntry(deflated);
            zipOut.write(compressible);
            zipOut.closeEntry();

            ZipEntry storedEntry = new ZipEntry("stored.bin");
            storedEntry.setMethod(ZipEntry.STORED);
            storedEntry.setSize(stored.length);
            storedEntry.setCompressedSize(stored.length);
            CRC32 crc = new CRC32();
            crc.update(stored);
            storedEntry.setCrc(crc.getValue());
            zipOut.putNextEntry(storedEntry);
            zipOut.write(stored);
            zipOut.closeEntry();
        }
    }

    private static void createSampleBundledJar(Path jarPath) throws IOException {
        // Create manifest with Main-Class
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.put(Attributes.Name.MAIN_CLASS, "com.example.Main");

        try (OutputStream out = Files.newOutputStream(jarPath);
             ZipOutputStream zipOut = new ZipOutputStream(out)) {

            // Write manifest
            ZipEntry manifestEntry = new ZipEntry("META-INF/MANIFEST.MF");
            zipOut.putNextEntry(manifestEntry);
            manifest.write(zipOut);
            zipOut.closeEntry();

            // Create fake .class files (just some bytes)
            byte[] classBytes1 = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 1, 2, 3, 4};
            byte[] classBytes2 = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 5, 6, 7, 8};
            byte[] classBytes3 = "class bytes for a small class file".getBytes();

            // Write class entries
            writeClassEntry(zipOut, "com/example/Main.class", classBytes1);
            writeClassEntry(zipOut, "com/example/Helper.class", classBytes2);
            writeClassEntry(zipOut, "com/example/util/Utils.class", classBytes3);

            // Write a resource file
            byte[] resourceBytes = "app.name=TestApp\napp.version=1.0".getBytes();
            ZipEntry resource = new ZipEntry("application.properties");
            zipOut.putNextEntry(resource);
            zipOut.write(resourceBytes);
            zipOut.closeEntry();

            // Write a META-INF service resource that must stay outside the blob
            byte[] serviceBytes = "com.example.impl.ServiceImpl".getBytes();
            ZipEntry serviceResource = new ZipEntry("META-INF/services/com.example.Service");
            zipOut.putNextEntry(serviceResource);
            zipOut.write(serviceBytes);
            zipOut.closeEntry();
        }
    }

    private static void writeClassEntry(ZipOutputStream zipOut, String name, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zipOut.putNextEntry(entry);
        zipOut.write(content);
        zipOut.closeEntry();
    }

    private static Map<String, byte[]> readJarContents(Path jarPath) throws IOException {
        Map<String, byte[]> result = new HashMap<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.isDirectory()) {
                    result.put(entry.getName(), jar.getInputStream(entry).readAllBytes());
                }
            }
        }
        return result;
    }
}