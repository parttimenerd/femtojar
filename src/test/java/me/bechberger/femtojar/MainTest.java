package me.bechberger.femtojar;

import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.RunResult;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class MainTest {

    @Test
    void reencodesToOutFile() throws Exception {
        Path tempDir = Files.createTempDirectory("femtojar-cli-test");
        Path inputJar = tempDir.resolve("input.jar");
        Path outputJar = tempDir.resolve("output.jar");
        createSampleJar(inputJar);

        RunResult result = FemtoCli.runCaptured(
            new Main(),
                new String[]{inputJar.toString(), outputJar.toString(), "--deflate"});

        assertEquals(0, result.exitCode(), () -> "stderr: " + result.err());
        assertTrue(Files.exists(outputJar));
        assertTrue(result.out().contains("Re-encoded"), () -> result.out());

        // Input jar should remain unchanged when output jar is specified.
        assertNotEquals(0, Files.readAllBytes(inputJar).length);

        try (JarFile jar = new JarFile(outputJar.toFile())) {
            assertNotNull(jar.getEntry("__classes.zlib"));
            assertNotNull(jar.getEntry("META-INF/MANIFEST.MF"));
        }
    }

    @Test
    void showsUsageForHelp() {
        RunResult result = FemtoCli.runCaptured(new Main(), new String[]{"--help"});
        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("Usage:"), () -> result.out());
    }

    @Test
    void failsForUnknownOption() {
        RunResult result = FemtoCli.runCaptured(new Main(), new String[]{"--does-not-exist"});
        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("Unknown option"), () -> result.err());
    }

    @Test
    void failsForInvalidCompressionMode() throws Exception {
        Path tempDir = Files.createTempDirectory("femtojar-cli-invalid-compression-test");
        Path inputJar = tempDir.resolve("input.jar");
        createSampleJar(inputJar);

        RunResult result = FemtoCli.runCaptured(new Main(), new String[]{inputJar.toString(), "--compression", "ultra"});
        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("Invalid value for --compression"), () -> result.err());
    }

    @Test
    void failsForInvalidBenchmarkFormat() throws Exception {
        Path tempDir = Files.createTempDirectory("femtojar-cli-invalid-benchmark-format-test");
        Path inputJar = tempDir.resolve("input.jar");
        createSampleJar(inputJar);

        RunResult result = FemtoCli.runCaptured(new Main(), new String[]{"--benchmark", inputJar.toString(), "--benchmark-format", "html"});
        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("Invalid value for --benchmark-format"), () -> result.err());
    }

    @Test
    void failsForMissingInputJarFile() {
        RunResult result = FemtoCli.runCaptured(new Main(), new String[]{"definitely-missing.jar"});
        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("Input JAR does not exist"), () -> result.err());
    }

    @Test
    void bundlesResourcesWhenRequested() throws Exception {
        Path tempDir = Files.createTempDirectory("femtojar-cli-bundle-test");
        Path inputJar = tempDir.resolve("input.jar");
        Path outputJar = tempDir.resolve("output.jar");
        createSampleJarWithResource(inputJar);

        RunResult result = FemtoCli.runCaptured(
            new Main(),
                new String[]{inputJar.toString(), outputJar.toString(), "--bundle-resources", "--deflate"});

        assertEquals(0, result.exitCode(), () -> "stderr: " + result.err());
        try (JarFile jar = new JarFile(outputJar.toFile())) {
            assertNotNull(jar.getEntry("__classes.zlib"));
            assertNull(jar.getEntry("app.properties"));
        }
    }

    @Test
    void benchmarkPrintsResultsAndDoesNotModifyInput() throws Exception {
        Path tempDir = Files.createTempDirectory("femtojar-cli-benchmark-test");
        Path inputJar = tempDir.resolve("input.jar");
        createSampleJarWithResource(inputJar);

        byte[] originalBytes = Files.readAllBytes(inputJar);
        RunResult result = FemtoCli.runCaptured(
            new Main(),
                new String[]{inputJar.toString(), "--benchmark"});

        String output = result.out();
        assertEquals(0, result.exitCode(), () -> "stderr: " + result.err());
        assertTrue(output.contains("Result table"), () -> output);
        assertTrue(output.contains("default, resources=off"));
        assertTrue(output.contains("zopfli, resources=on"));
        assertTrue(output.contains("max, resources=off"));
        assertTrue(output.contains("Best setting:"));

        byte[] currentBytes = Files.readAllBytes(inputJar);
        assertNotEquals(0, currentBytes.length);
        assertArrayEquals(originalBytes, currentBytes);
    }

    @Test
    void benchmarkCanEmitMarkdown() throws Exception {
        Path tempDir = Files.createTempDirectory("femtojar-cli-benchmark-md-test");
        Path inputJar = tempDir.resolve("input.jar");
        createSampleJarWithResource(inputJar);

        RunResult result = FemtoCli.runCaptured(
            new Main(),
                new String[]{inputJar.toString(), "--benchmark", "--benchmark-format", "markdown"});

        String output = result.out();
        assertEquals(0, result.exitCode(), () -> "stderr: " + result.err());
        assertTrue(output.contains("## femtojar benchmark"), () -> output);
        assertTrue(output.contains("| mode | size(bytes) | saved(bytes) | saved(%) | time(ms) | size vs default(%) | time vs default(%) |"));
        assertTrue(output.contains("| default, resources=off |"));
    }

    @Test
    void optimizedJarRemainsExecutable() throws Exception {
        Path tempDir = Files.createTempDirectory("femtojar-cli-exec-test");
        Path inputJar = tempDir.resolve("input.jar");
        Path outputJar = tempDir.resolve("output.jar");
        createSampleJar(inputJar);

        RunResult encodeResult = FemtoCli.runCaptured(
            new Main(),
                new String[]{inputJar.toString(), outputJar.toString(), "--deflate"});
        assertEquals(0, encodeResult.exitCode(), () -> "stderr: " + encodeResult.err());

        Process process = new ProcessBuilder(List.of("java", "-jar", outputJar.toString(), "one", "two"))
                .redirectErrorStream(true)
                .start();

        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().reduce("", (a, b) -> a + b + "\n");
        }

        int runExit = process.waitFor();
        assertEquals(0, runExit, () -> "Process failed. Output:\n" + output);
        assertTrue(output.contains("CLI_EXEC_OK"), () -> output);
        assertTrue(output.contains("ARG_COUNT=2"), () -> output);
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