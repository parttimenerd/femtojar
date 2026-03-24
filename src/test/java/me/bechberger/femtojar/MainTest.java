package me.bechberger.femtojar;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    @Test
    void reencodesToOutFile() throws Exception {
        Path tempDir = Files.createTempDirectory("femtojar-cli-test");
        Path inputJar = tempDir.resolve("input.jar");
        Path outputJar = tempDir.resolve("output.jar");
        createSampleJar(inputJar);

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

        int exitCode = Main.run(
                new String[]{"--in", inputJar.toString(), "--out", outputJar.toString(), "--deflate"},
                new PrintStream(outBytes),
                new PrintStream(errBytes));

        assertEquals(0, exitCode, "CLI should succeed. stderr=" + errBytes);
        assertTrue(Files.exists(outputJar), "Output JAR should exist");
        assertTrue(new String(outBytes.toByteArray()).contains("Re-encoded"), "CLI should print summary");

        // Input jar should remain unchanged when output jar is specified.
        assertFalse(Files.readAllBytes(inputJar).length == 0, "Input jar should still exist and be readable");

        try (JarFile jar = new JarFile(outputJar.toFile())) {
            assertTrue(jar.getEntry("__classes.zlib") != null, "Optimized jar should contain bundled blob");
            assertTrue(jar.getEntry("META-INF/MANIFEST.MF") != null, "Optimized jar should keep manifest");
        }
    }

    @Test
    void showsUsageForHelp() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

        int exitCode = Main.run(new String[]{"--help"}, new PrintStream(outBytes), new PrintStream(errBytes));

        assertEquals(0, exitCode);
        assertTrue(new String(outBytes.toByteArray()).contains("Usage:"));
    }

    @Test
    void failsForUnknownOption() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

        int exitCode = Main.run(
                new String[]{"--does-not-exist"},
                new PrintStream(outBytes),
                new PrintStream(errBytes));

        assertEquals(2, exitCode);
        assertTrue(new String(errBytes.toByteArray()).contains("Unknown option"));
    }

    @Test
    void failsForInvalidCompressionMode() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

        int exitCode = Main.run(
                new String[]{"--in", "input.jar", "--compression", "ultra"},
                new PrintStream(outBytes),
                new PrintStream(errBytes));

        assertEquals(2, exitCode);
        assertTrue(new String(errBytes.toByteArray()).contains("Invalid value for --compression"));
    }

    @Test
    void failsForInvalidBenchmarkFormat() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

        int exitCode = Main.run(
                new String[]{"--benchmark", "--in", "input.jar", "--benchmark-format", "html"},
                new PrintStream(outBytes),
                new PrintStream(errBytes));

        assertEquals(2, exitCode);
        assertTrue(new String(errBytes.toByteArray()).contains("Invalid value for --benchmark-format"));
    }

    @Test
    void failsForMissingInputJarFile() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

        int exitCode = Main.run(
                new String[]{"--in", "definitely-missing.jar"},
                new PrintStream(outBytes),
                new PrintStream(errBytes));

        assertEquals(2, exitCode);
        assertTrue(new String(errBytes.toByteArray()).contains("Input JAR does not exist"));
    }

    @Test
    void bundlesResourcesWhenRequested() throws Exception {
        Path tempDir = Files.createTempDirectory("femtojar-cli-bundle-test");
        Path inputJar = tempDir.resolve("input.jar");
        Path outputJar = tempDir.resolve("output.jar");
        createSampleJarWithResource(inputJar);

        int exitCode = Main.run(
                new String[]{"--in", inputJar.toString(), "--out", outputJar.toString(), "--bundle-resources", "--deflate"},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, exitCode);
        try (JarFile jar = new JarFile(outputJar.toFile())) {
            assertTrue(jar.getEntry("__classes.zlib") != null, "Bundled output must contain class blob");
            assertTrue(jar.getEntry("app.properties") == null,
                    "Resource should be bundled into blob with --bundle-resources");
        }
    }

    @Test
    void benchmarkPrintsResultsAndDoesNotModifyInput() throws Exception {
        Path tempDir = Files.createTempDirectory("femtojar-cli-benchmark-test");
        Path inputJar = tempDir.resolve("input.jar");
        createSampleJarWithResource(inputJar);

        byte[] originalBytes = Files.readAllBytes(inputJar);
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

        int exitCode = Main.run(
            new String[]{"--benchmark", "--in", inputJar.toString()},
                new PrintStream(outBytes),
                new PrintStream(errBytes));

        String output = outBytes.toString();
        assertEquals(0, exitCode, "Benchmark should succeed. stderr=" + errBytes);
        assertTrue(output.contains("Result table"), "Benchmark output should contain result table");
        assertTrue(output.contains("default, resources=off"), "Benchmark output should include default mode");
        assertTrue(output.contains("zopfli, resources=on"), "Benchmark output should include zopfli/resource mode");
        assertTrue(output.contains("max, resources=off"), "Benchmark output should include max mode");
        assertTrue(output.contains("Best setting:"), "Benchmark output should include best setting");

        byte[] currentBytes = Files.readAllBytes(inputJar);
        assertNotEquals(0, currentBytes.length, "Input jar should remain readable after benchmark");
        assertTrue(java.util.Arrays.equals(originalBytes, currentBytes), "Benchmark mode must not modify input JAR");
    }

    @Test
    void benchmarkCanEmitMarkdown() throws Exception {
        Path tempDir = Files.createTempDirectory("femtojar-cli-benchmark-md-test");
        Path inputJar = tempDir.resolve("input.jar");
        createSampleJarWithResource(inputJar);

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

        int exitCode = Main.run(
            new String[]{"--benchmark", "--benchmark-format", "markdown", "--in", inputJar.toString()},
                new PrintStream(outBytes),
                new PrintStream(errBytes));

        String output = outBytes.toString();
        assertEquals(0, exitCode, "Markdown benchmark should succeed. stderr=" + errBytes);
        assertTrue(output.contains("## femtojar benchmark"), "Markdown header should be present");
        assertTrue(output.contains("| mode | size(bytes) | saved(bytes) | saved(%) | time(ms) | size vs default(%) | time vs default(%) |"),
                "Markdown table header should be present");
        assertTrue(output.contains("| default, resources=off |"), "Markdown table should include default mode");
    }

    @Test
    void optimizedJarRemainsExecutable() throws Exception {
        Path tempDir = Files.createTempDirectory("femtojar-cli-exec-test");
        Path inputJar = tempDir.resolve("input.jar");
        Path outputJar = tempDir.resolve("output.jar");
        createSampleJar(inputJar);

        int encodeExit = Main.run(
                new String[]{"--in", inputJar.toString(), "--out", outputJar.toString(), "--deflate"},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));
        assertEquals(0, encodeExit);

        Process process = new ProcessBuilder(List.of("java", "-jar", outputJar.toString(), "one", "two"))
                .redirectErrorStream(true)
                .start();

        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().reduce("", (a, b) -> a + b + "\n");
        }

        int runExit = process.waitFor();
        assertEquals(0, runExit, "Optimized JAR should run successfully. Output:\n" + output);
        assertTrue(output.contains("CLI_EXEC_OK"), "Expected runtime marker missing. Output:\n" + output);
        assertTrue(output.contains("ARG_COUNT=2"), "Expected argument count marker missing. Output:\n" + output);
    }

    private static void createSampleJar(Path jarPath) throws Exception {
        createJar(jarPath, false);
    }

    private static void createSampleJarWithResource(Path jarPath) throws Exception {
        createJar(jarPath, true);
    }

    private static void createJar(Path jarPath, boolean includeResource) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.put(Attributes.Name.MAIN_CLASS, "me.bechberger.femtojar.fixture.CliExecApp");

        try (var out = Files.newOutputStream(jarPath);
             var zipOut = new ZipOutputStream(out)) {
            ZipEntry manifestEntry = new ZipEntry("META-INF/MANIFEST.MF");
            zipOut.putNextEntry(manifestEntry);
            manifest.write(zipOut);
            zipOut.closeEntry();

            byte[] classBytes = readClassBytes("me.bechberger.femtojar.fixture.CliExecApp");
            ZipEntry classEntry = new ZipEntry("me/bechberger/femtojar/fixture/CliExecApp.class");
            zipOut.putNextEntry(classEntry);
            zipOut.write(classBytes);
            zipOut.closeEntry();

            if (includeResource) {
                ZipEntry resourceEntry = new ZipEntry("app.properties");
                zipOut.putNextEntry(resourceEntry);
                zipOut.write("name=cli-test".getBytes(StandardCharsets.UTF_8));
                zipOut.closeEntry();
            }
        }
    }

    private static byte[] readClassBytes(String className) throws IOException {
        String resourceName = className.replace('.', '/') + ".class";
        try (InputStream in = MainTest.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new IOException("Class bytes not found for " + className);
            }
            return in.readAllBytes();
        }
    }
}
