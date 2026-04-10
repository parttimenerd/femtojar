package me.bechberger.femtojar;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;

/**
 * Runs ProGuard in-process via the {@code Configuration} / {@code ProGuard.execute()} API.
 */
public class ProGuardRunner {

    private static final String DEFAULT_CONFIG_RESOURCE = "/proguard-default.pro";

    private static final Object PROGUARD_LOCK = new Object();

    /**
     * Run ProGuard on the given input JAR.
     *
     * @param inJar                 input JAR path
     * @param outJar                output JAR path
     * @param prependDefaultConfig  prepend the bundled default config
     * @param configFile            user-supplied .pro config file (may be null)
     * @param options               inline ProGuard options (may be null or empty)
     * @param libraryJars           additional library JAR paths (may be null or empty)
     */
    public static void run(Path inJar, Path outJar, boolean prependDefaultConfig,
                           Path configFile, List<String> options, List<Path> libraryJars) throws IOException {
        List<String> args = new ArrayList<>();
        args.add("-injars");
        args.add(inJar.toAbsolutePath().toString());
        args.add("-outjars");
        args.add(outJar.toAbsolutePath().toString());

        if (libraryJars != null) {
            for (Path lib : libraryJars) {
                args.add("-libraryjars");
                args.add(lib.toAbsolutePath().toString());
            }
        }

        Path tempConfig = null;
        if (prependDefaultConfig) {
            tempConfig = extractDefaultConfig();
            args.add("-include");
            args.add(tempConfig.toAbsolutePath().toString());
        }

        if (configFile != null) {
            args.add("-include");
            args.add(configFile.toAbsolutePath().toString());
        }

        if (options != null) {
            for (String opt : options) {
                // Pass each option string as-is; ProGuard's own tokenizer handles
                // quoted paths and spaces correctly (BUG-30 fix).
                if (!opt.isEmpty()) {
                    args.add(opt);
                }
            }
        }

        // Synchronize on a lock to prevent concurrent System.out/err redirects (BUG-11).
        try {
        synchronized (PROGUARD_LOCK) {
            // Redirect System.out to stderr during ProGuard execution to prevent
            // ProGuard notes/version banners from polluting structured output (JSON).
            PrintStream origOut = System.out;
            PrintStream origErr = System.err;
            ByteArrayOutputStream proguardErr = new ByteArrayOutputStream();
            PrintStream capturedErr = new PrintStream(proguardErr, true, StandardCharsets.UTF_8);
            System.setOut(System.err);
            System.setErr(capturedErr);
            try {
                proguard.Configuration configuration = new proguard.Configuration();
                try (proguard.ConfigurationParser parser =
                             new proguard.ConfigurationParser(args.toArray(new String[0]),
                                     System.getProperties())) {
                    parser.parse(configuration);
                }
                try {
                    new proguard.ProGuard(configuration).execute();
                } catch (ConcurrentModificationException e) {
                    // Retry once on ConcurrentModificationException (BUG-10)
                    new proguard.ProGuard(configuration).execute();
                }
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                e.printStackTrace(System.err);
                throw new IOException("ProGuard failed (" + e.getClass().getSimpleName() + "): " + detail, e);
            } finally {
                capturedErr.flush();
                String errText = proguardErr.toString(StandardCharsets.UTF_8);
                for (String line : errText.split("\\R")) {
                    if ("ProGuard, version undefined".equals(line.trim())) {
                        continue;
                    }
                    if (!line.isEmpty()) {
                        origErr.println(line);
                    }
                }
                System.setErr(origErr);
                System.setOut(origOut);
            }
        }
        } finally {
            // Clean up temp config file (BUG-23: no more deleteOnExit leak)
            if (tempConfig != null) {
                try { Files.deleteIfExists(tempConfig); } catch (IOException ignored) {}
            }
        }
    }

    private static Path extractDefaultConfig() throws IOException {
        Path tempFile = Files.createTempFile("proguard-default-", ".pro");
        try (InputStream is = ProGuardRunner.class.getResourceAsStream(DEFAULT_CONFIG_RESOURCE)) {
            if (is == null) {
                throw new IOException("Bundled ProGuard config not found: " + DEFAULT_CONFIG_RESOURCE);
            }
            Files.copy(is, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return tempFile;
    }
}