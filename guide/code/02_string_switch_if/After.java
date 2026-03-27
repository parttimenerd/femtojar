public class After {
    static int code(String s) {
        if ("--help".equals(s)) {
            return 1;
        }
        if ("--version".equals(s)) {
            return 2;
        }
        return 0;
    }
}
