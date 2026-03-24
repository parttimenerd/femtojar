package me.bechberger.it;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class ResourceApp {
    public static void main(String[] args) throws IOException {
        String value;
        try (InputStream in = ResourceApp.class.getResourceAsStream("/app.properties")) {
            if (in == null) {
                throw new IllegalStateException("missing app.properties");
            }
            value = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        System.out.println("RESOURCE_IT_OK");
        System.out.println("RESOURCE_VALUE=" + value);
    }
}
