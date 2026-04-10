package me.bechberger.femtojar.fixture;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class CliExecApp {
    public static void main(String[] args) throws Exception {
        System.out.println("CLI_EXEC_OK");
        System.out.println("ARG_COUNT=" + args.length);

        URL resourceUrl = CliExecApp.class.getClassLoader().getResource("app.properties");
        System.out.println("RESOURCE_URL_PRESENT=" + (resourceUrl != null));
        System.out.println("RESOURCE_URL_PROTOCOL=" + (resourceUrl == null ? "null" : resourceUrl.getProtocol()));

        try (InputStream in = resourceUrl == null ? null : resourceUrl.openStream()) {
            String resourceValue = in == null ? "null" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            System.out.println("RESOURCE_URL_CONTENT=" + resourceValue);
        }

        try (InputStream in = CliExecApp.class.getClassLoader().getResourceAsStream("app.properties")) {
            String resourceValue = in == null ? "null" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            System.out.println("RESOURCE_STREAM_CONTENT=" + resourceValue);
        }

        URL missingUrl = CliExecApp.class.getClassLoader().getResource("missing.properties");
        System.out.println("MISSING_URL_PRESENT=" + (missingUrl != null));
        try (InputStream in = CliExecApp.class.getClassLoader().getResourceAsStream("missing.properties")) {
            System.out.println("MISSING_STREAM_PRESENT=" + (in != null));
        }

        // --- BundleBootstrap diagnostics ---

        // Package metadata (BUG-15: definePackage with manifest attrs)
        Package pkg = CliExecApp.class.getPackage();
        System.out.println("IMPL_VERSION=" + (pkg != null ? pkg.getImplementationVersion() : "null"));
        System.out.println("IMPL_TITLE=" + (pkg != null ? pkg.getImplementationTitle() : "null"));

        // Class file as input stream (BUG-17: getResourceAsStream for .class)
        try (InputStream classIn = CliExecApp.class.getClassLoader()
                .getResourceAsStream("me/bechberger/femtojar/fixture/CliExecApp.class")) {
            System.out.println("CLASS_AS_STREAM=" + (classIn != null ? "found(" + classIn.readAllBytes().length + ")" : "null"));
        }

        // Class file as URL (BUG-6: findResource for classes)
        URL classUrl = CliExecApp.class.getClassLoader()
                .getResource("me/bechberger/femtojar/fixture/CliExecApp.class");
        System.out.println("CLASS_URL_PRESENT=" + (classUrl != null));

        // Directory resource (BUG-28 / computeDirectoryPaths)
        URL dirUrl = CliExecApp.class.getClassLoader()
                .getResource("me/bechberger/femtojar/fixture");
        System.out.println("DIR_RESOURCE_PRESENT=" + (dirUrl != null));

        // Content length from URL connection (BUG-5)
        if (resourceUrl != null) {
            java.net.URLConnection conn = resourceUrl.openConnection();
            System.out.println("CONTENT_LENGTH=" + conn.getContentLength());
        }

        // Code source (BUG-16: ProtectionDomain with CodeSource)
        try {
            java.security.CodeSource cs = CliExecApp.class.getProtectionDomain().getCodeSource();
            System.out.println("CODE_SOURCE_PRESENT=" + (cs != null && cs.getLocation() != null));
        } catch (Exception e) {
            System.out.println("CODE_SOURCE_PRESENT=error");
        }
    }
}