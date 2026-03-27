public class Before {
    record Pair(int left, int right) {}

    static int sum(Object value) {
        return switch (value) {
            case Pair(int left, int right) -> left + right;
            default -> 0;
        };
    }
}
