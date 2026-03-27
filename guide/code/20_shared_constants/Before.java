// Four components each hold their own copy of the same header strings.
// The four long string literals appear four times across the four .class files.
public class Before {
    static final class Sender {
        private static final String CONTENT_TYPE =
            "Content-Type: application/json; charset=utf-8";
        private static final String ACCEPT =
            "Accept: application/vnd.api+json; version=3.1";
        private static final String USER_AGENT =
            "User-Agent: femtojar-client/1.0.0 (https://github.com/parttimenerd/femtojar)";
        private static final String CORRELATION = "X-Correlation-ID: ";

        static String header(String id) {
            return CONTENT_TYPE + " | " + ACCEPT + " | " + USER_AGENT + " | " + CORRELATION + id;
        }
    }

    static final class Receiver {
        private static final String CONTENT_TYPE =
            "Content-Type: application/json; charset=utf-8";
        private static final String ACCEPT =
            "Accept: application/vnd.api+json; version=3.1";
        private static final String USER_AGENT =
            "User-Agent: femtojar-client/1.0.0 (https://github.com/parttimenerd/femtojar)";
        private static final String CORRELATION = "X-Correlation-ID: ";

        static String header(String id) {
            return CONTENT_TYPE + " | " + ACCEPT + " | " + USER_AGENT + " | " + CORRELATION + id;
        }
    }

    static final class Middleware {
        private static final String CONTENT_TYPE =
            "Content-Type: application/json; charset=utf-8";
        private static final String ACCEPT =
            "Accept: application/vnd.api+json; version=3.1";
        private static final String USER_AGENT =
            "User-Agent: femtojar-client/1.0.0 (https://github.com/parttimenerd/femtojar)";
        private static final String CORRELATION = "X-Correlation-ID: ";

        static String header(String id) {
            return CONTENT_TYPE + " | " + ACCEPT + " | " + USER_AGENT + " | " + CORRELATION + id;
        }
    }

    static final class Tracer {
        private static final String CONTENT_TYPE =
            "Content-Type: application/json; charset=utf-8";
        private static final String ACCEPT =
            "Accept: application/vnd.api+json; version=3.1";
        private static final String USER_AGENT =
            "User-Agent: femtojar-client/1.0.0 (https://github.com/parttimenerd/femtojar)";
        private static final String CORRELATION = "X-Correlation-ID: ";

        static String header(String id) {
            return CONTENT_TYPE + " | " + ACCEPT + " | " + USER_AGENT + " | " + CORRELATION + id;
        }
    }
}
