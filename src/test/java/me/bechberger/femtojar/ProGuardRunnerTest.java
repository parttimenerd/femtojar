package me.bechberger.femtojar;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProGuardRunnerTest {

    @Test
    void runPassThroughProducesValidJar() throws Exception {
        Path tempDir = Files.createTempDirectory("proguard-runner-test");
        Path inputJar = tempDir.resolve("input.jar");
        Path outputJar = tempDir.resolve("output.jar");
        createSampleJar(inputJar);

        ProGuardRunner.run(inputJar, outputJar, true, null,
                List.of("-dontobfuscate", "-dontshrink", "-dontoptimize"),
                Collections.emptyList());

        assertThat(Files.exists(outputJar)).isTrue();
        assertThat(Files.size(outputJar)).isPositive();
        try (JarFile jar = new JarFile(outputJar.toFile())) {
            assertThat(jar.getEntry("me/bechberger/femtojar/fixture/CliExecApp.class")).isNotNull();
        }
    }

    @Test
    void runWithoutDefaultConfigAndExplicitLibrary() throws Exception {
        Path tempDir = Files.createTempDirectory("proguard-runner-nodefault-test");
        Path inputJar = tempDir.resolve("input.jar");
        Path outputJar = tempDir.resolve("output.jar");
        createSampleJar(inputJar);

        // Write a minimal user config that includes the java.base jmod
        Path userConfig = tempDir.resolve("user.pro");
        Files.writeString(userConfig, String.join("\n",
                "-libraryjars <java.home>/jmods/java.base.jmod(!**.jar;!module-info.class)",
                "-keep class me.bechberger.femtojar.fixture.CliExecApp { *; }",
                "-dontobfuscate",
                "-dontoptimize"
        ));

        ProGuardRunner.run(inputJar, outputJar, false, userConfig,
                Collections.emptyList(), Collections.emptyList());

        assertThat(Files.exists(outputJar)).isTrue();
        try (JarFile jar = new JarFile(outputJar.toFile())) {
            assertThat(jar.getEntry("me/bechberger/femtojar/fixture/CliExecApp.class")).isNotNull();
        }
    }

    @Test
    void runWithInvalidJarThrowsIOException() {
        assertThrows(IOException.class, () -> {
            Path tempDir = Files.createTempDirectory("proguard-runner-fail-test");
            Path fakeJar = tempDir.resolve("not-a-jar.jar");
            Files.writeString(fakeJar, "this is not a JAR file");
            Path outputJar = tempDir.resolve("output.jar");

            ProGuardRunner.run(fakeJar, outputJar, false, null,
                    List.of("-dontobfuscate", "-dontshrink", "-dontoptimize"),
                    Collections.emptyList());
        });
    }

    private static void createSampleJar(Path jarPath) throws Exception {
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
        }
    }

    private static byte[] readClassBytes(String className) throws IOException {
        String resourceName = className.replace('.', '/') + ".class";
        try (InputStream in = ProGuardRunnerTest.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new IOException("Class bytes not found for " + className);
            }
            return in.readAllBytes();
        }
    }
}