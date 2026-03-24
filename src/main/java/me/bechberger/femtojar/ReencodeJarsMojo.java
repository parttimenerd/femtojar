package me.bechberger.femtojar;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

        public String getIn() {
            return in;
        }

        public void setIn(String in) {
            this.in = in;
        }

        public String getOut() {
            return out;
        }

        public void setOut(String out) {
            this.out = out;
        }
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
            Path sourceJarPath = resolveJarPath(entry.getIn());
            Path targetJarPath = entry.getOut() == null || entry.getOut().isBlank()
                    ? sourceJarPath
                    : resolveJarPath(entry.getOut());
            try {
                JarReencoder.ReencodeResult result;
                if (sourceJarPath.equals(targetJarPath)) {
                    result = reencoder.reencodeInPlaceBundled(
                        sourceJarPath,
                        compressionMode.useZopfli(),
                        compressionMode.zopfliIterations(),
                        bundleResources,
                        femtojarVersion);
                } else {
                    long originalSize = Files.size(sourceJarPath);
                    reencoder.rewriteJarBundled(
                        sourceJarPath,
                        targetJarPath,
                        compressionMode.useZopfli(),
                        compressionMode.zopfliIterations(),
                        bundleResources,
                        femtojarVersion);
                    long newSize = Files.size(targetJarPath);
                    result = new JarReencoder.ReencodeResult(originalSize, newSize);
                }
                long saved = result.originalSize() - result.newSize();
                double ratio = result.originalSize() == 0 ? 0d : (saved * 100.0) / result.originalSize();
                String compressionModeLabel = switch (compressionMode) {
                    case DEFAULT -> "default (deflate level=9)";
                    case ZOPFLI -> "zopfli (iterations=7)";
                    case MAX -> "max (zopfli iterations=100)";
                };
                String bundledMode = bundleResources
                    ? "bundled classes + non-META-INF resources"
                    : "bundled classes only";
                getLog().info("Re-encoded " + sourceJarPath + " -> " + targetJarPath + " with " + bundledMode + " + "
                        + compressionModeLabel + ": " + result.originalSize() + " -> " + result.newSize()
                        + " bytes (saved " + saved + " bytes, " + String.format("%.2f", ratio) + "%)");
            } catch (IOException e) {
                String message = "Failed to re-encode JAR: " + sourceJarPath + " -> " + targetJarPath;
                if (failOnError) {
                    throw new MojoExecutionException(message, e);
                }
                getLog().warn(message + " - " + e.getMessage());
            }
        }
    }

    private String getFemtojarVersion() {
        String version = ReencodeJarsMojo.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "unknown" : version;
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