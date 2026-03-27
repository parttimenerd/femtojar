// Constants live once in a shared holder; components reference via getstatic.
// Static block prevents javac from folding the literals into each call site
// (compile-time constant folding only applies to ConstantValue-attributed fields).
public class After {
    static final class Headers {
        static final String CONTENT_TYPE;
        static final String ACCEPT;
        static final String USER_AGENT;
        static final String CORRELATION;
        static {
            CONTENT_TYPE = "Content-Type: application/json; charset=utf-8";
            ACCEPT       = "Accept: application/vnd.api+json; version=3.1";
            USER_AGENT   = "User-Agent: femtojar-client/1.0.0 (https://github.com/parttimenerd/femtojar)";
            CORRELATION  = "X-Correlation-ID: ";
        }
    }

    static final class Sender {
        static String header(String id) {
            return Headers.CONTENT_TYPE + " | " + Headers.ACCEPT + " | "
                   + Headers.USER_AGENT + " | " + Headers.CORRELATION + id;
        }
    }

    static final class Receiver {
        static String header(String id) {
            return Headers.CONTENT_TYPE + " | " + Headers.ACCEPT + " | "
                   + Headers.USER_AGENT + " | " + Headers.CORRELATION + id;
        }
    }

    static final class Middleware {
        static String header(String id) {
            return Headers.CONTENT_TYPE + " | " + Headers.ACCEPT + " | "
                   + Headers.USER_AGENT + " | " + Headers.CORRELATION + id;
        }
    }

    static final class Tracer {
        static String header(String id) {
            return Headers.CONTENT_TYPE + " | " + Headers.ACCEPT + " | "
                   + Headers.USER_AGENT + " | " + Headers.CORRELATION + id;
        }
    }
}
