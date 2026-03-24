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

    @Parameter(property = "femtojar.zopfli", defaultValue = "true")
    private boolean zopfli;

    @Parameter(property = "femtojar.zopfliIterations", defaultValue = "100")
    private int zopfliIterations;

    /**
     * Bundle non-META-INF resources into the shared compressed blob.
     * Caveat: only getResourceAsStream() is guaranteed for bundled resources;
     * frameworks requiring resource URLs via getResource() may not work.
     */
    @Parameter(property = "femtojar.bundleResources", defaultValue = "false")
    private boolean bundleResources;

    @Parameter(required = true)
    private List<String> jars;

    /**
     * Optional list of output JAR paths, one per entry in {@code jars}.
     * If omitted, each input JAR is rewritten in place.
     */
    @Parameter
    private List<String> outJars;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private String buildDirectory;

    private final JarReencoder reencoder = new JarReencoder();

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Skipping femtojar execution");
            return;
        }
        if (jars == null || jars.isEmpty()) {
            throw new MojoExecutionException("Parameter 'jars' must contain at least one JAR path");
        }
        if (outJars != null && outJars.size() != jars.size()) {
            throw new MojoExecutionException("Parameter 'outJars' must have the same number of entries as 'jars'");
        }
        String femtojarVersion = getFemtojarVersion();
        for (int i = 0; i < jars.size(); i++) {
            Path sourceJarPath = resolveJarPath(jars.get(i));
            Path targetJarPath = outJars == null ? sourceJarPath : resolveJarPath(outJars.get(i));
            try {
                JarReencoder.ReencodeResult result;
                if (sourceJarPath.equals(targetJarPath)) {
                    result = reencoder.reencodeInPlaceBundled(
                        sourceJarPath,
                        zopfli,
                        zopfliIterations,
                        bundleResources,
                        femtojarVersion);
                } else {
                    long originalSize = Files.size(sourceJarPath);
                    reencoder.rewriteJarBundled(
                        sourceJarPath,
                        targetJarPath,
                        zopfli,
                        zopfliIterations,
                        bundleResources,
                        femtojarVersion);
                    long newSize = Files.size(targetJarPath);
                    result = new JarReencoder.ReencodeResult(originalSize, newSize);
                }
                long saved = result.originalSize() - result.newSize();
                double ratio = result.originalSize() == 0 ? 0d : (saved * 100.0) / result.originalSize();
                String compressionMode = zopfli ? "zopfli (iterations=" + zopfliIterations + ")" : "deflate (level=9)";
                String bundledMode = bundleResources
                    ? "bundled classes + non-META-INF resources"
                    : "bundled classes only";
                getLog().info("Re-encoded " + sourceJarPath + " -> " + targetJarPath + " with " + bundledMode + " + "
                        + compressionMode + ": " + result.originalSize() + " -> " + result.newSize()
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
            throw new MojoExecutionException("Parameter 'jars' must not contain null values");
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