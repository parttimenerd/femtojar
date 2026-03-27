package me.bechberger.femtojar;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

@Mojo(name = "reencode-jars", threadSafe = true)
public class ReencodeJarsMojo extends AbstractMojo {

    @Parameter(property = "femtojar.skip", defaultValue = "false")
    private boolean skip;

    @Parameter(property = "femtojar.failOnError", defaultValue = "true")
    private boolean failOnError;

    @Parameter(property = "femtojar.compressionMode", defaultValue = "DEFAULT")
    private CompressionMode compressionMode;

    /**
     * Bundle non-META-INF resources into the shared compressed blob.
     * Caveat: only getResourceAsStream() is guaranteed for bundled resources;
     * frameworks requiring resource URLs via getResource() may not work.
     */
    @Parameter(property = "femtojar.bundleResources", defaultValue = "true")
    private boolean bundleResources;

    /**
     * Global ProGuard configuration. All settings inside this element apply
     * to every JAR unless overridden per-JAR.
     */
    @Parameter
    private ProGuardConfig proguard;

    /**
     * List of JAR entries to reencode. Each entry has an input path {@code <in>}
     * and optional output path {@code <out>}. If no output path is specified,
     * the input JAR is rewritten in place.
     */
    @Parameter(required = true)
    private List<JarEntry> jars;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private String buildDirectory;

    private final JarReencoder reencoder = new JarReencoder();

    /**
     * Configuration for a single JAR reencode operation.
     * All settings except {@code in}/{@code out} are optional and fall back to
     * the corresponding plugin-level value when omitted.
     */
    public static class JarEntry {
        /**
         * Input JAR path (relative to buildDirectory if not absolute).
         */
        private String in;

        /**
         * Optional output JAR path. If omitted, the input JAR is rewritten in place.
         */
        private String out;

        /**
         * Per-JAR compression mode override (DEFAULT | ZOPFLI | MAX).
         * Inherits the plugin-level {@code compressionMode} when null.
         */
        private CompressionMode compressionMode;

        /**
         * Per-JAR resource-bundling override.
         * Inherits the plugin-level {@code bundleResources} when null.
         */
        private Boolean bundleResources;

        /**
         * Per-JAR ProGuard configuration override.
         * Inherits the plugin-level {@code proguard} settings when null.
         */
        private ProGuardConfig proguard;

        public String getIn() { return in; }
        public void setIn(String in) { this.in = in; }

        public String getOut() { return out; }
        public void setOut(String out) { this.out = out; }

        public CompressionMode getCompressionMode() { return compressionMode; }
        public void setCompressionMode(CompressionMode compressionMode) { this.compressionMode = compressionMode; }

        public Boolean getBundleResources() { return bundleResources; }
        public void setBundleResources(Boolean bundleResources) { this.bundleResources = bundleResources; }

        public ProGuardConfig getProguard() { return proguard; }
        public void setProguard(ProGuardConfig proguard) { this.proguard = proguard; }
    }

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Skipping femtojar execution");
            return;
        }
        if (jars == null || jars.isEmpty()) {
            throw new MojoExecutionException("Parameter 'jars' must contain at least one JAR entry");
        }
        String femtojarVersion = getFemtojarVersion();
        for (JarEntry entry : jars) {
            if (entry.getIn() == null || entry.getIn().isBlank()) {
                throw new MojoExecutionException("Each JAR entry must have an 'in' path");
            }

            // Resolve effective per-JAR settings, falling back to plugin-level defaults.
            CompressionMode effectiveCompression = entry.getCompressionMode() != null
                    ? entry.getCompressionMode() : compressionMode;
            boolean effectiveBundleResources = entry.getBundleResources() != null
                    ? entry.getBundleResources() : bundleResources;

            // Resolve effective ProGuard config (per-JAR merged with global).
            ProGuardConfig effectiveProguard = resolveProGuardConfig(entry);

            Path sourceJarPath = resolveJarPath(entry.getIn());
            Path targetJarPath = entry.getOut() == null || entry.getOut().isBlank()
                    ? sourceJarPath
                    : resolveJarPath(entry.getOut());

            // Run ProGuard before reencoding if enabled.
            Path reencoderInput = sourceJarPath;
            Path proguardTempFile = null;
            if (effectiveProguard != null && effectiveProguard.isEnabled()) {
                try {
                    Path pgOut;
                    if (effectiveProguard.getOut() != null && !effectiveProguard.getOut().isBlank()) {
                        pgOut = resolveJarPath(effectiveProguard.getOut());
                    } else {
                        proguardTempFile = Files.createTempDirectory("proguard-work-")
                                .resolve("proguard-out.jar");
                        pgOut = proguardTempFile;
                    }
                    Path pgConfig = effectiveProguard.getConfigFile() != null
                            ? Path.of(effectiveProguard.getConfigFile()) : null;
                    List<Path> pgLibs = effectiveProguard.getLibraryJars() != null
                            ? effectiveProguard.getLibraryJars().stream().map(Path::of).toList()
                            : Collections.emptyList();
                    getLog().info("Running ProGuard on " + sourceJarPath + " -> " + pgOut);
                    ProGuardRunner.run(sourceJarPath, pgOut,
                            effectiveProguard.isPrependDefaultConfig(),
                            pgConfig, effectiveProguard.getOptions(), pgLibs);
                    reencoderInput = pgOut;
                } catch (IOException e) {
                    String message = "ProGuard failed for: " + sourceJarPath;
                    if (failOnError) {
                        throw new MojoExecutionException(message, e);
                    }
                    getLog().warn(message + " - " + e.getMessage());
                    continue;
                }
            }

            try {
                JarReencoder.ReencodeResult result;
                if (reencoderInput.equals(targetJarPath)) {
                    result = reencoder.reencodeInPlaceBundled(
                        reencoderInput,
                        effectiveCompression.useZopfli(),
                        effectiveCompression.zopfliIterations(),
                        effectiveBundleResources,
                        femtojarVersion,
                        false,
                        null);
                } else {
                    long originalSize = Files.size(reencoderInput);
                    reencoder.rewriteJarBundled(
                        reencoderInput,
                        targetJarPath,
                        effectiveCompression.useZopfli(),
                        effectiveCompression.zopfliIterations(),
                        effectiveBundleResources,
                        femtojarVersion,
                        false,
                        null);
                    long newSize = Files.size(targetJarPath);
                    result = new JarReencoder.ReencodeResult(originalSize, newSize);
                }
                long saved = result.originalSize() - result.newSize();
                double ratio = result.originalSize() == 0 ? 0d : (saved * 100.0) / result.originalSize();
                String bundledMode = effectiveBundleResources
                    ? "bundled classes + non-META-INF resources"
                    : "bundled classes only";
                getLog().info("Re-encoded " + sourceJarPath + " -> " + targetJarPath + " with " + bundledMode + " + "
                        + effectiveCompression.description() + ": " + result.originalSize() + " -> " + result.newSize()
                        + " bytes (saved " + saved + " bytes, " + String.format("%.2f", ratio) + "%)");
            } catch (IOException e) {
                String message = "Failed to re-encode JAR: " + sourceJarPath + " -> " + targetJarPath;
                if (failOnError) {
                    throw new MojoExecutionException(message, e);
                }
                getLog().warn(message + " - " + e.getMessage());
            } finally {
                if (proguardTempFile != null) {
                    try {
                        Files.deleteIfExists(proguardTempFile);
                        Files.deleteIfExists(proguardTempFile.getParent());
                    } catch (IOException ignored) {}
                }
            }
        }
    }

    private String getFemtojarVersion() {
        String version = ReencodeJarsMojo.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "unknown" : version;
    }

    private ProGuardConfig resolveProGuardConfig(JarEntry entry) {
        ProGuardConfig perJar = entry.getProguard();
        if (perJar == null) {
            return proguard;
        }
        return perJar.mergeWith(proguard);
    }

    private Path resolveJarPath(String configuredJar) throws MojoExecutionException {
        if (configuredJar == null) {
            throw new MojoExecutionException("JAR path must not be null");
        }
        Path configuredPath = Paths.get(configuredJar);
        if (configuredPath.isAbsolute()) {
            return configuredPath;
        }
        if (buildDirectory == null || buildDirectory.isBlank()) {
            throw new MojoExecutionException("Parameter 'buildDirectory' is not available");
        }
        return Paths.get(buildDirectory).resolve(configuredPath).normalize();
    }
}