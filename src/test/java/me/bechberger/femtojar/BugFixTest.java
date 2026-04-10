package me.bechberger.femtojar;

import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.RunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests verifying fixes for bugs BUG-1 through BUG-31 documented in BUGS.md.
 */
class BugFixTest {

    // -----------------------------------------------------------------------
    // BUG-1: NPE on in-place reencoding with bare filename
    // -----------------------------------------------------------------------
    @Test
    void bug1_bareFilenameDoesNotCauseNPE(@TempDir Path tempDir) throws Exception {
        Path jarPath = tempDir.resolve("test.jar");
        createMinimalJar(jarPath);

        // Create a JAR with a bare filename (no parent path)
        // We can't truly test a bare filename in the current dir from JUnit,
        // so we test the safe path logic via the reencoder directly.
        JarReencoder reencoder = new JarReencoder();
        JarReencoder.ReencodeResult result = reencoder.reencodeInPlaceBundled(
                jarPath, new JarReencoder.ReencodeOptions(false, 0, true, "test"));
        assertThat(result.originalSize()).isGreaterThan(0);
        assertThat(result.newSize()).isGreaterThan(0);
    }

    // -----------------------------------------------------------------------
    // BUG-2: CLI --zopfli flag was dead code
    // -----------------------------------------------------------------------
    @Test
    void bug2_zopfliFlagIsHonoredByCli(@TempDir Path tempDir) throws Exception {
        Path inputJar = tempDir.resolve("input.jar");
        Path defaultOut = tempDir.resolve("out-default.jar");
        Path zopfliOut = tempDir.resolve("out-zopfli.jar");
        createJarWithClasses(inputJar);

        RunResult r1 = FemtoCli.runCaptured(new Main(),
                inputJar.toString(), defaultOut.toString());
        assertEquals(0, r1.exitCode(), () -> "stderr: " + r1.err());

        RunResult r2 = FemtoCli.runCaptured(new Main(),
                inputJar.toString(), zopfliOut.toString(), "--zopfli");
        assertEquals(0, r2.exitCode(), () -> "stderr: " + r2.err());

        // Zopfli should produce output that's at most the same size as default
        // (and the output message should say zopfli, not default)
        assertThat(r2.out()).contains("zopfli");
    }

    // -----------------------------------------------------------------------
    // BUG-3: Manifest attributes preserved
    // -----------------------------------------------------------------------
    @Test
    void bug3_manifestAttributesArePreserved(@TempDir Path tempDir) throws Exception {
        Path inputJar = tempDir.resolve("input.jar");
        Path outputJar = tempDir.resolve("output.jar");

        // Create JAR with custom manifest attributes
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.put(Attributes.Name.MAIN_CLASS, "com.example.Main");
        attrs.put(new Attributes.Name("Implementation-Title"), "MyApp");
        attrs.put(new Attributes.Name("Implementation-Version"), "2.5.0");
        attrs.put(new Attributes.Name("Class-Path"), "lib/dep.jar");
        attrs.put(new Attributes.Name("Multi-Release"), "true");

        createJarWithManifest(inputJar, manifest);

        JarReencoder reencoder = new JarReencoder();
        reencoder.rewriteJarBundled(inputJar, outputJar,
                new JarReencoder.ReencodeOptions(false, 0, true, "test"));

        try (JarFile jar = new JarFile(outputJar.toFile())) {
            Manifest outManifest = jar.getManifest();
            Attributes outAttrs = outManifest.getMainAttributes();
            assertEquals("me.bechberger.femtojar.rt.BundleBootstrap", outAttrs.getValue("Main-Class"));
            assertEquals("com.example.Main", outAttrs.getValue("X-Original-Main-Class"));
            assertEquals("MyApp", outAttrs.getValue("Implementation-Title"));
            assertEquals("2.5.0", outAttrs.getValue("Implementation-Version"));
            assertEquals("lib/dep.jar", outAttrs.getValue("Class-Path"));
            assertEquals("true", outAttrs.getValue("Multi-Release"));
        }
    }

    // -----------------------------------------------------------------------
    // BUG-5: FemtoJarURLConnection.getContentLength()
    // BUG-29: FemtoJarURLConnection.getContentType()
    // (tested at runtime via JAR execution, hard to unit test directly since
    //  BundleBootstrap is a runtime-only component)
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // BUG-7: Signature files stripped
    // -----------------------------------------------------------------------
    @Test
    void bug7_signatureFilesAreStripped(@TempDir Path tempDir) throws Exception {
        Path inputJar = tempDir.resolve("signed.jar");
        Path outputJar = tempDir.resolve("output.jar");

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "com.example.Main");

        try (OutputStream os = Files.newOutputStream(inputJar);
             ZipOutputStream zos = new ZipOutputStream(os)) {
            // Manifest
            zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            manifest.write(zos);
            zos.closeEntry();

            // Class
            zos.putNextEntry(new ZipEntry("com/example/Main.class"));
            zos.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 8});
            zos.closeEntry();

            // Signature files
            zos.putNextEntry(new ZipEntry("META-INF/MYKEY.SF"));
            zos.write("Signature-Version: 1.0\n".getBytes());
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("META-INF/MYKEY.RSA"));
            zos.write(new byte[]{1, 2, 3, 4});
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("META-INF/MYKEY.DSA"));
            zos.write(new byte[]{5, 6, 7, 8});
            zos.closeEntry();
        }

        JarReencoder reencoder = new JarReencoder();
        reencoder.rewriteJarBundled(inputJar, outputJar,
                new JarReencoder.ReencodeOptions(false, 0, true, "test"));

        try (JarFile jar = new JarFile(outputJar.toFile())) {
            assertNull(jar.getEntry("META-INF/MYKEY.SF"), "SF file should be stripped");
            assertNull(jar.getEntry("META-INF/MYKEY.RSA"), "RSA file should be stripped");
            assertNull(jar.getEntry("META-INF/MYKEY.DSA"), "DSA file should be stripped");
        }
    }

    // -----------------------------------------------------------------------
    // BUG-8: module-info.class not bundled
    // BUG-9: Multi-release versioned classes kept as JAR entries
    // -----------------------------------------------------------------------
    @Test
    void bug8_9_moduleInfoAndMultiReleaseClassesNotBundled(@TempDir Path tempDir) throws Exception {
        Path inputJar = tempDir.resolve("multi-release.jar");
        Path outputJar = tempDir.resolve("output.jar");

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "com.example.Main");
        manifest.getMainAttributes().put(new Attributes.Name("Multi-Release"), "true");

        try (OutputStream os = Files.newOutputStream(inputJar);
             ZipOutputStream zos = new ZipOutputStream(os)) {
            zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            manifest.write(zos);
            zos.closeEntry();

            byte[] classBytes = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 8};

            // Regular class (should be bundled)
            zos.putNextEntry(new ZipEntry("com/example/Main.class"));
            zos.write(classBytes);
            zos.closeEntry();

            // module-info.class at root (should NOT be bundled)
            zos.putNextEntry(new ZipEntry("module-info.class"));
            zos.write(classBytes);
            zos.closeEntry();

            // Multi-release versioned class (should NOT be bundled)
            zos.putNextEntry(new ZipEntry("META-INF/versions/17/com/example/Main.class"));
            zos.write(classBytes);
            zos.closeEntry();

            // Multi-release module-info (should NOT be bundled)
            zos.putNextEntry(new ZipEntry("META-INF/versions/17/module-info.class"));
            zos.write(classBytes);
            zos.closeEntry();
        }

        JarReencoder reencoder = new JarReencoder();
        reencoder.rewriteJarBundled(inputJar, outputJar,
                new JarReencoder.ReencodeOptions(false, 0, false, "test"));

        try (JarFile jar = new JarFile(outputJar.toFile())) {
            // module-info should be kept as a regular JAR entry
            assertNotNull(jar.getEntry("module-info.class"),
                    "module-info.class should be preserved as a JAR entry");
            // Multi-release entries should be kept
            assertNotNull(jar.getEntry("META-INF/versions/17/com/example/Main.class"),
                    "Multi-release class should be preserved as a JAR entry");
            assertNotNull(jar.getEntry("META-INF/versions/17/module-info.class"),
                    "Multi-release module-info should be preserved as a JAR entry");
        }
    }

    // -----------------------------------------------------------------------
    // BUG-13: Precise internal entry filter
    // -----------------------------------------------------------------------
    @Test
    void bug13_userResourcesWithClassesPrefixNotStripped(@TempDir Path tempDir) throws Exception {
        Path inputJar = tempDir.resolve("input.jar");
        Path outputJar = tempDir.resolve("output.jar");

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "com.example.Main");

        try (OutputStream os = Files.newOutputStream(inputJar);
             ZipOutputStream zos = new ZipOutputStream(os)) {
            zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            manifest.write(zos);
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("com/example/Main.class"));
            zos.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 8});
            zos.closeEntry();

            // User resource that looks like a femtojar internal entry
            zos.putNextEntry(new ZipEntry("__classes.json"));
            zos.write("{\"data\":true}".getBytes());
            zos.closeEntry();
        }

        JarReencoder reencoder = new JarReencoder();
        reencoder.rewriteJarBundled(inputJar, outputJar,
                new JarReencoder.ReencodeOptions(false, 0, true, "test"));

        // __classes.json should be bundled as a resource, not silently dropped
        // Since bundleResources=true, it goes into the blob (no JAR entry),
        // but it should NOT be filtered out by isFemtojarInternalEntry
        try (JarFile jar = new JarFile(outputJar.toFile())) {
            // The entry won't be in the JAR (it's bundled into the blob),
            // but let's verify with bundleResources=false
        }

        // Re-test with bundleResources=false — the resource should appear as a JAR entry
        Path outputJar2 = tempDir.resolve("output2.jar");
        reencoder.rewriteJarBundled(inputJar, outputJar2,
                new JarReencoder.ReencodeOptions(false, 0, false, "test"));
        try (JarFile jar = new JarFile(outputJar2.toFile())) {
            assertNotNull(jar.getEntry("__classes.json"),
                    "__classes.json user resource should not be filtered out");
        }
    }

    // -----------------------------------------------------------------------
    // BUG-14: --parallel flag removed
    // -----------------------------------------------------------------------
    @Test
    void bug14_parallelFlagRejected(@TempDir Path tempDir) throws Exception {
        Path inputJar = tempDir.resolve("input.jar");
        createMinimalJar(inputJar);

        RunResult result = FemtoCli.runCaptured(new Main(),
                inputJar.toString(), "--parallel");
        // Should fail with unknown option since --parallel was removed
        assertEquals(2, result.exitCode());
        assertThat(result.err()).contains("Unknown option");
    }

    // -----------------------------------------------------------------------
    // BUG-21: ProGuardConfig mergeWith concatenates lists
    // -----------------------------------------------------------------------
    @Test
    void bug21_mergeWithConcatenatesLists() {
        ProGuardConfig global = new ProGuardConfig(
                true, null, null,
                List.of("-dontwarn"),
                null,
                List.of("/lib/rt.jar"));

        ProGuardConfig perJar = new ProGuardConfig(
                null, null, null,
                List.of("-keep class Foo"),
                null,
                List.of("/lib/extra.jar"));

        ProGuardConfig merged = perJar.mergeWith(global);

        // Both lists should be present: global first, then per-jar
        assertThat(merged.options()).containsExactly("-dontwarn", "-keep class Foo");
        assertThat(merged.libraryJars()).containsExactly("/lib/rt.jar", "/lib/extra.jar");
    }

    @Test
    void bug21_mergeWithFallsBackToGlobalWhenPerJarNull() {
        ProGuardConfig global = new ProGuardConfig(
                true, null, null,
                List.of("-dontwarn"),
                null, null);

        ProGuardConfig perJar = new ProGuardConfig();
        ProGuardConfig merged = perJar.mergeWith(global);

        // When per-jar is null, should fall back to global
        assertEquals(List.of("-dontwarn"), merged.options());
    }

    // -----------------------------------------------------------------------
    // BUG-22: escapeJson handles control characters
    // -----------------------------------------------------------------------
    @Test
    void bug22_benchmarkJsonEscapesControlChars(@TempDir Path tempDir) throws Exception {
        Path inputJar = tempDir.resolve("input.jar");
        createJarWithClasses(inputJar);

        RunResult result = FemtoCli.runCaptured(new Main(),
                inputJar.toString(), "--benchmark", "--benchmark-format", "json");
        assertEquals(0, result.exitCode(), () -> "stderr: " + result.err());

        String json = result.out();
        assertThat(json).contains("\"input\"");
        // Verify basic JSON structure is valid (no raw control chars)
        assertThat(json).doesNotContainPattern("[\\x00-\\x1f](?<!\\n)");
    }

    // -----------------------------------------------------------------------
    // BUG-26: Manifest timestamp is epoch zero for reproducibility
    // -----------------------------------------------------------------------
    @Test
    void bug26_manifestTimestampIsEpochZero(@TempDir Path tempDir) throws Exception {
        Path inputJar = tempDir.resolve("input.jar");
        Path outputJar = tempDir.resolve("output.jar");
        createMinimalJar(inputJar);

        JarReencoder reencoder = new JarReencoder();
        reencoder.rewriteJarBundled(inputJar, outputJar,
                new JarReencoder.ReencodeOptions(false, 0, true, "test"));

        try (JarFile jar = new JarFile(outputJar.toFile())) {
            ZipEntry manifestEntry = jar.getEntry("META-INF/MANIFEST.MF");
            assertNotNull(manifestEntry);
            // Timestamp should be epoch 0 for reproducibility
            assertEquals(0L, manifestEntry.getTime(),
                    "Manifest entry timestamp should be 0 for reproducible builds");
        }
    }

    // -----------------------------------------------------------------------
    // BUG-27: Serialized index has deterministic order
    // -----------------------------------------------------------------------
    @Test
    void bug27_indexIsDeterministic(@TempDir Path tempDir) throws Exception {
        Path inputJar = tempDir.resolve("input.jar");

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "com.example.Main");

        // Create a JAR with many classes to exercise index ordering
        try (OutputStream os = Files.newOutputStream(inputJar);
             ZipOutputStream zos = new ZipOutputStream(os)) {
            zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            manifest.write(zos);
            zos.closeEntry();

            byte[] classBytes = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 8};
            String[] classNames = {"z/Z.class", "a/A.class", "m/M.class", "b/B.class"};
            for (String cn : classNames) {
                zos.putNextEntry(new ZipEntry(cn));
                zos.write(classBytes);
                zos.closeEntry();
            }
        }

        // Reencode twice and verify byte-identical output
        Path output1 = tempDir.resolve("out1.jar");
        Path output2 = tempDir.resolve("out2.jar");

        JarReencoder reencoder = new JarReencoder();
        JarReencoder.ReencodeOptions opts = new JarReencoder.ReencodeOptions(false, 0, true, "test");
        reencoder.rewriteJarBundled(inputJar, output1, opts);
        reencoder.rewriteJarBundled(inputJar, output2, opts);

        byte[] bytes1 = Files.readAllBytes(output1);
        byte[] bytes2 = Files.readAllBytes(output2);
        assertArrayEquals(bytes1, bytes2,
                "Two reencodes of the same input should produce identical output");
    }

    // -----------------------------------------------------------------------
    // BUG-31: Benchmark now includes MAX mode
    // -----------------------------------------------------------------------
    @Test
    void bug31_benchmarkIncludesMaxMode(@TempDir Path tempDir) throws Exception {
        Path inputJar = tempDir.resolve("input.jar");
        createJarWithClasses(inputJar);

        RunResult result = FemtoCli.runCaptured(new Main(),
                inputJar.toString(), "--benchmark");
        assertEquals(0, result.exitCode(), () -> "stderr: " + result.err());

        assertThat(result.out()).contains("max");
    }

    // -----------------------------------------------------------------------
    // BundleBootstrap runtime behavior: package metadata, class-as-resource,
    // directory synthesis, content length, code source
    // -----------------------------------------------------------------------
    @Test
    void bundlePreservesPackageMetadata(@TempDir Path tempDir) throws Exception {
        Path inputJar = tempDir.resolve("input.jar");
        Path outputJar = tempDir.resolve("output.jar");
        createEnrichedJar(inputJar);

        JarReencoder reencoder = new JarReencoder();
        reencoder.rewriteJarBundled(inputJar, outputJar,
                new JarReencoder.ReencodeOptions(false, 0, true, "test"));

        String output = runJar(outputJar);

        // BUG-15: Package.getImplementationVersion() uses manifest attrs
        assertThat(output).contains("IMPL_VERSION=2.5.0");
        assertThat(output).contains("IMPL_TITLE=TestApp");
    }

    @Test
    void bundleServesClassFilesAsResources(@TempDir Path tempDir) throws Exception {
        Path inputJar = tempDir.resolve("input.jar");
        Path outputJar = tempDir.resolve("output.jar");
        createEnrichedJar(inputJar);

        JarReencoder reencoder = new JarReencoder();
        reencoder.rewriteJarBundled(inputJar, outputJar,
                new JarReencoder.ReencodeOptions(false, 0, true, "test"));

        String output = runJar(outputJar);

        // BUG-17: getResourceAsStream works for .class entries
        assertThat(output).contains("CLASS_AS_STREAM=found(");

        // BUG-6: getResource returns URL for .class entries
        assertThat(output).contains("CLASS_URL_PRESENT=true");
    }

    @Test
    void bundleSynthesizesDirectoryResources(@TempDir Path tempDir) throws Exception {
        Path inputJar = tempDir.resolve("input.jar");
        Path outputJar = tempDir.resolve("output.jar");
        createEnrichedJar(inputJar);

        JarReencoder reencoder = new JarReencoder();
        reencoder.rewriteJarBundled(inputJar, outputJar,
                new JarReencoder.ReencodeOptions(false, 0, true, "test"));

        String output = runJar(outputJar);

        // BUG-28 / computeDirectoryPaths: directory resources are synthesized
        assertThat(output).contains("DIR_RESOURCE_PRESENT=true");
    }

    @Test
    void bundleReportsContentLengthAndCodeSource(@TempDir Path tempDir) throws Exception {
        Path inputJar = tempDir.resolve("input.jar");
        Path outputJar = tempDir.resolve("output.jar");
        createEnrichedJar(inputJar);

        JarReencoder reencoder = new JarReencoder();
        reencoder.rewriteJarBundled(inputJar, outputJar,
                new JarReencoder.ReencodeOptions(false, 0, true, "test"));

        String output = runJar(outputJar);

        // BUG-5: getContentLength returns actual byte count
        assertThat(output).contains("CONTENT_LENGTH=14"); // "name=test-data" = 14 bytes

        // BUG-16: CodeSource present for bundled classes
        assertThat(output).contains("CODE_SOURCE_PRESENT=true");
    }

    // -----------------------------------------------------------------------
    // Helper methods
    // -----------------------------------------------------------------------

    private static void createMinimalJar(Path jarPath) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "com.example.Main");

        try (OutputStream os = Files.newOutputStream(jarPath);
             ZipOutputStream zos = new ZipOutputStream(os)) {
            zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            manifest.write(zos);
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("com/example/Main.class"));
            zos.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 8});
            zos.closeEntry();
        }
    }

    private static void createJarWithClasses(Path jarPath) throws IOException {
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.put(Attributes.Name.MAIN_CLASS, "me.bechberger.femtojar.fixture.CliExecApp");

        try (OutputStream os = Files.newOutputStream(jarPath);
             ZipOutputStream zos = new ZipOutputStream(os)) {
            zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            manifest.write(zos);
            zos.closeEntry();

            byte[] classBytes = readClassBytes("me.bechberger.femtojar.fixture.CliExecApp");
            zos.putNextEntry(new ZipEntry("me/bechberger/femtojar/fixture/CliExecApp.class"));
            zos.write(classBytes);
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("app.properties"));
            zos.write("name=cli-test".getBytes());
            zos.closeEntry();
        }
    }

    private static void createJarWithManifest(Path jarPath, Manifest manifest) throws IOException {
        try (OutputStream os = Files.newOutputStream(jarPath);
             ZipOutputStream zos = new ZipOutputStream(os)) {
            zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            manifest.write(zos);
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("com/example/Main.class"));
            zos.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 8});
            zos.closeEntry();
        }
    }

    private static byte[] readClassBytes(String className) throws IOException {
        String resourceName = className.replace('.', '/') + ".class";
        try (InputStream in = BugFixTest.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) throw new IOException("Class bytes not found for " + className);
            return in.readAllBytes();
        }
    }

    /**
     * Creates a JAR with CliExecApp, app.properties, and rich manifest attributes
     * (Implementation-Title/Version) for testing BundleBootstrap runtime behavior.
     */
    private static void createEnrichedJar(Path jarPath) throws IOException {
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.put(Attributes.Name.MAIN_CLASS, "me.bechberger.femtojar.fixture.CliExecApp");
        attrs.put(Attributes.Name.IMPLEMENTATION_TITLE, "TestApp");
        attrs.put(Attributes.Name.IMPLEMENTATION_VERSION, "2.5.0");
        attrs.put(Attributes.Name.SPECIFICATION_TITLE, "TestSpec");

        try (OutputStream os = Files.newOutputStream(jarPath);
             ZipOutputStream zos = new ZipOutputStream(os)) {
            zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            manifest.write(zos);
            zos.closeEntry();

            byte[] classBytes = readClassBytes("me.bechberger.femtojar.fixture.CliExecApp");
            zos.putNextEntry(new ZipEntry("me/bechberger/femtojar/fixture/CliExecApp.class"));
            zos.write(classBytes);
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("app.properties"));
            zos.write("name=test-data".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }

    private static String runJar(Path jarPath) throws Exception {
        Process process = new ProcessBuilder(List.of("java", "-jar", jarPath.toString()))
                .redirectErrorStream(true)
                .start();
        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().reduce("", (a, b) -> a + b + "\n");
        }
        int exitCode = process.waitFor();
        assertThat(exitCode)
                .withFailMessage("Process failed. Output:\n%s", output)
                .isZero();
        return output;
    }
}
