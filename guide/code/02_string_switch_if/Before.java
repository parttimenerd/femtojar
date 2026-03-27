public class Before {
    static int code(String s) {
        return switch (s) {
            case "--help" -> 1;
            case "--version" -> 2;
            default -> 0;
        };
    }
}
