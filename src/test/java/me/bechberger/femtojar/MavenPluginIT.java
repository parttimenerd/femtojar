package me.bechberger.femtojar;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

@EnabledIfSystemProperty(named = "it.projects.dir", matches = ".+")
class MavenPluginIT {

    private static Path itProjectsDir;

    @BeforeAll
    static void setup() {
        String itProjectsDirProperty = System.getProperty("it.projects.dir");
        assertThat(itProjectsDirProperty).isNotBlank();
        itProjectsDir = Paths.get(itProjectsDirProperty);
        assertThat(Files.exists(itProjectsDir)).isTrue();
    }

    @Test
    void testBasicMavenPlugin() throws Exception {
        Path jar = assertScenarioJarExists("basic-maven-plugin", "basic-maven-plugin-1.0-SNAPSHOT.jar");
        assertCommonBundledManifest(jar, "me.bechberger.it.BasicApp");
        assertCommonBundledLayout(jar);
        assertThat(hasEntry(jar, "me/bechberger/it/BasicApp.class"))
                .withFailMessage("Original class should not remain as a jar entry")
                .isFalse();
        assertJarIsExecutable(jar);

        ProcessResult run = runJar(jar, List.of("alpha", "beta"));
        assertEquals(0, run.exitCode, "basic scenario should exit 0. Output:\n" + run.output);
        assertThat(run.output)
                .contains("BASIC_IT_OK")
                .contains("ARGS=[alpha, beta]");
    }

    @Test
    void testBundleResourcesScenario() throws Exception {
        Path jar = assertScenarioJarExists("bundle-resources", "bundle-resources-1.0-SNAPSHOT.jar");
        assertCommonBundledManifest(jar, "me.bechberger.it.ResourceApp");
        assertCommonBundledLayout(jar);
        assertThat(hasEntry(jar, "app.properties"))
                .withFailMessage("Bundled resource should not remain as standalone jar entry")
                .isFalse();
        assertJarIsExecutable(jar);

        ProcessResult run = runJar(jar, List.of());
        assertEquals(0, run.exitCode, "bundle-resources scenario should exit 0. Output:\n" + run.output);
        assertThat(run.output)
                .contains("RESOURCE_IT_OK")
                .contains("RESOURCE_VALUE=name=bundled-resource");
    }

    @Test
    void testDeflaterFallbackScenario() throws Exception {
        Path jar = assertScenarioJarExists("deflater-fallback", "deflater-fallback-1.0-SNAPSHOT.jar");
        assertCommonBundledManifest(jar, "me.bechberger.it.DeflaterApp");
        assertCommonBundledLayout(jar);
        assertJarIsExecutable(jar);

        ProcessResult run = runJar(jar, List.of());
        assertEquals(0, run.exitCode, "deflater-fallback scenario should exit 0. Output:\n" + run.output);
        assertThat(run.output).contains("DEFLATER_IT_OK");
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
        assertThat(run.output).contains("IDEMPOTENCY_IT_OK");
    }

    @Test
    void testOutFileScenario() throws Exception {
        Path projectDir = itProjectsDir.resolve("out-file");
        Path originalJar = projectDir.resolve("target").resolve("out-file-1.0-SNAPSHOT.jar");
        Path optimizedJar = projectDir.resolve("target").resolve("out-file-1.0-SNAPSHOT-optimized.jar");

        assertThat(Files.exists(originalJar)).isTrue();
        assertThat(Files.exists(optimizedJar)).isTrue();

        assertThat(hasEntry(originalJar, "__classes.zlib")).isFalse();

        assertCommonBundledManifest(optimizedJar, "me.bechberger.it.OutFileApp");
        assertCommonBundledLayout(optimizedJar);
        assertJarIsExecutable(optimizedJar);

        ProcessResult run = runJar(optimizedJar, List.of());
        assertEquals(0, run.exitCode, "out-file scenario should exit 0. Output:\n" + run.output);
        assertThat(run.output).contains("OUT_FILE_IT_OK");
    }

    private Path assertScenarioJarExists(String projectName, String jarName) {
        Path projectDir = itProjectsDir.resolve(projectName);
        assertThat(Files.exists(projectDir)).isTrue();
        Path jar = projectDir.resolve("target").resolve(jarName);
        assertThat(Files.exists(jar)).isTrue();
        return jar;
    }

    private void assertCommonBundledManifest(Path jarPath, String expectedOriginalMainClass) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Manifest manifest = jar.getManifest();
            assertThat(manifest).isNotNull();
            String mainClass = manifest.getMainAttributes().getValue("Main-Class");
            assertThat(mainClass).isEqualTo("me.bechberger.femtojar.rt.BundleBootstrap");

            String originalMainClass = manifest.getMainAttributes().getValue("X-Original-Main-Class");
            assertThat(originalMainClass).isEqualTo(expectedOriginalMainClass);

            String femtojarVersion = manifest.getMainAttributes().getValue("X-Femtojar-Version");
            assertThat(femtojarVersion).isNotNull();
        }
    }

    private void assertCommonBundledLayout(Path jarPath) throws IOException {
        assertThat(hasEntry(jarPath, "__classes.zlib")).isTrue();
        assertThat(hasEntry(jarPath, "me/bechberger/femtojar/rt/BundleBootstrap.class")).isTrue();
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
        assertThat(finished).isTrue();
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