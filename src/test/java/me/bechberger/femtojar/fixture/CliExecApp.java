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
    }
}