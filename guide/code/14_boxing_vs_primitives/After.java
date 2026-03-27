// Primitive int array: no boxing, minimal bytecode.
public class After {
    static int sum(int[] items) {
        int total = 0;
        for (int i = 0; i < items.length; i++) {
            total += items[i];
        }
        return total;
    }
}
