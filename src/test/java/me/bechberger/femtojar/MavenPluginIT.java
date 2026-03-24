package me.bechberger.femtojar;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipException;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class MavenPluginIT {

    private static Path itProjectsDir;

    @BeforeAll
    static void setup() {
        String itProjectsDirProperty = System.getProperty("it.projects.dir");
        assertNotNull(itProjectsDirProperty, "it.projects.dir system property must be set");
        if (itProjectsDirProperty.isBlank()) {
            fail("it.projects.dir system property must not be blank");
        }
        itProjectsDir = Paths.get(itProjectsDirProperty);
        assertTrue(Files.exists(itProjectsDir), "Integration test projects directory must exist: " + itProjectsDir);
    }

    @Test
    void testBasicMavenPlugin() throws Exception {
        Path jar = assertScenarioJarExists("basic-maven-plugin", "basic-maven-plugin-1.0-SNAPSHOT.jar");
        assertCommonBundledManifest(jar, "me.bechberger.it.BasicApp");
        assertCommonBundledLayout(jar);
        assertFalse(hasEntry(jar, "me/bechberger/it/BasicApp.class"), "Original class should not remain as a jar entry");
        assertJarIsExecutable(jar);

        ProcessResult run = runJar(jar, List.of("alpha", "beta"));
        assertEquals(0, run.exitCode, "basic scenario should exit 0. Output:\n" + run.output);
        assertTrue(run.output.contains("BASIC_IT_OK"), "Expected BASIC_IT_OK output. Output:\n" + run.output);
        assertTrue(run.output.contains("ARGS=[alpha, beta]"), "Expected args output. Output:\n" + run.output);
    }

    @Test
    void testBundleResourcesScenario() throws Exception {
        Path jar = assertScenarioJarExists("bundle-resources", "bundle-resources-1.0-SNAPSHOT.jar");
        assertCommonBundledManifest(jar, "me.bechberger.it.ResourceApp");
        assertCommonBundledLayout(jar);
        assertFalse(hasEntry(jar, "app.properties"), "Bundled resource should not remain as standalone jar entry");
        assertJarIsExecutable(jar);

        ProcessResult run = runJar(jar, List.of());
        assertEquals(0, run.exitCode, "bundle-resources scenario should exit 0. Output:\n" + run.output);
        assertTrue(run.output.contains("RESOURCE_IT_OK"), "Expected RESOURCE_IT_OK output. Output:\n" + run.output);
        assertTrue(run.output.contains("RESOURCE_VALUE=name=bundled-resource"), "Expected bundled resource value output. Output:\n" + run.output);
    }

    @Test
    void testDeflaterFallbackScenario() throws Exception {
        Path jar = assertScenarioJarExists("deflater-fallback", "deflater-fallback-1.0-SNAPSHOT.jar");
        assertCommonBundledManifest(jar, "me.bechberger.it.DeflaterApp");
        assertCommonBundledLayout(jar);
        assertJarIsExecutable(jar);

        ProcessResult run = runJar(jar, List.of());
        assertEquals(0, run.exitCode, "deflater-fallback scenario should exit 0. Output:\n" + run.output);
        assertTrue(run.output.contains("DEFLATER_IT_OK"), "Expected DEFLATER_IT_OK output. Output:\n" + run.output);
    }

    @Test
    void testIdempotencyScenario() throws Exception {
        Path jar = assertScenarioJarExists("idempotency", "idempotency-1.0-SNAPSHOT.jar");
        assertCommonBundledManifest(jar, "me.bechberger.it.IdempotencyApp");
        assertCommonBundledLayout(jar);
        assertJarIsExecutable(jar);

        int classesBlobCount = countEntries(jar, "__classes.zlib");
        assertEquals(1, classesBlobCount, "Idempotent packaging should keep a single __classes.zlib entry");

        ProcessResult run = runJar(jar, List.of());
        assertEquals(0, run.exitCode, "idempotency scenario should exit 0. Output:\n" + run.output);
        assertTrue(run.output.contains("IDEMPOTENCY_IT_OK"), "Expected IDEMPOTENCY_IT_OK output. Output:\n" + run.output);
    }

    @Test
    void testOutFileScenario() throws Exception {
        Path projectDir = itProjectsDir.resolve("out-file");
        Path originalJar = projectDir.resolve("target").resolve("out-file-1.0-SNAPSHOT.jar");
        Path optimizedJar = projectDir.resolve("target").resolve("out-file-1.0-SNAPSHOT-optimized.jar");

        assertTrue(Files.exists(originalJar), "Original out-file scenario jar should exist: " + originalJar);
        assertTrue(Files.exists(optimizedJar), "Optimized output jar should exist: " + optimizedJar);

        assertFalse(hasEntry(originalJar, "__classes.zlib"), "Original jar should remain untouched when outJars is used");

        assertCommonBundledManifest(optimizedJar, "me.bechberger.it.OutFileApp");
        assertCommonBundledLayout(optimizedJar);
        assertJarIsExecutable(optimizedJar);

        ProcessResult run = runJar(optimizedJar, List.of());
        assertEquals(0, run.exitCode, "out-file scenario should exit 0. Output:\n" + run.output);
        assertTrue(run.output.contains("OUT_FILE_IT_OK"), "Expected OUT_FILE_IT_OK output. Output:\n" + run.output);
    }

    private Path assertScenarioJarExists(String projectName, String jarName) {
        Path projectDir = itProjectsDir.resolve(projectName);
        assertTrue(Files.exists(projectDir), "Expected scenario directory: " + projectDir);
        Path jar = projectDir.resolve("target").resolve(jarName);
        assertTrue(Files.exists(jar), "Expected scenario jar: " + jar);
        return jar;
    }

    private void assertCommonBundledManifest(Path jarPath, String expectedOriginalMainClass) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Manifest manifest = jar.getManifest();
            assertNotNull(manifest, "Manifest should exist");
            String mainClass = manifest.getMainAttributes().getValue("Main-Class");
            assertEquals("me.bechberger.femtojar.rt.BundleBootstrap", mainClass,
                    "Main-Class should be femtojar bootstrap");

            String originalMainClass = manifest.getMainAttributes().getValue("X-Original-Main-Class");
            assertEquals(expectedOriginalMainClass, originalMainClass,
                    "X-Original-Main-Class should match the app main class");

            String femtojarVersion = manifest.getMainAttributes().getValue("X-Femtojar-Version");
            assertNotNull(femtojarVersion, "X-Femtojar-Version should be present");
        }
    }

    private void assertCommonBundledLayout(Path jarPath) throws IOException {
        assertTrue(hasEntry(jarPath, "__classes.zlib"), "Expected __classes.zlib entry");
        assertTrue(hasEntry(jarPath, "me/bechberger/femtojar/rt/BundleBootstrap.class"),
                "Expected BundleBootstrap runtime class entry");
    }

    private boolean hasEntry(Path jarPath, String name) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            return jar.getEntry(name) != null;
        }
    }

    private int countEntries(Path jarPath, String name) throws IOException {
        int count = 0;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (name.equals(entry.getName())) {
                    count++;
                }
            }
        }
        return count;
    }

    private void assertJarIsExecutable(Path jarPath) throws IOException, InterruptedException {
        try (JarFile ignored = new JarFile(jarPath.toFile())) {
            // Ensure the artifact is a readable ZIP/JAR before runtime checks.
        } catch (ZipException zipException) {
            fail("Invalid JAR (not executable): " + jarPath + " - " + zipException.getMessage());
        }

        ProcessResult run = runJar(jarPath, List.of());
        assertEquals(0, run.exitCode, "java -jar should succeed for " + jarPath + ". Output:\n" + run.output);
    }

    private ProcessResult runJar(Path jarPath, List<String> args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("java");
        command.add("-jar");
        command.add(jarPath.toString());
        command.addAll(args);

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
        }

        boolean finished = process.waitFor(Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS);
        assertTrue(finished, "java -jar process timed out for " + jarPath);
        return new ProcessResult(process.exitValue(), output.toString());
    }

    private static final class ProcessResult {
        private final int exitCode;
        private final String output;

        private ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
