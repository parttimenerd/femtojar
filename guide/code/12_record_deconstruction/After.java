public class After {
    record Pair(int left, int right) {}

    static int sum(Object value) {
        if (value instanceof Pair) {
            Pair p = (Pair) value;
            return p.left() + p.right();
        }
        return 0;
    }
}
