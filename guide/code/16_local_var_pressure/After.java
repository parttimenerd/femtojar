// Fewer simultaneously live variables — more fit in slots 0-3 (single-byte opcodes).
public class After {
    static int compute(int[] xs) {
        int sum = 0;
        int product = 1;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < xs.length; i++) {
            sum += xs[i];
            product *= xs[i];
            if (xs[i] < min) min = xs[i];
            if (xs[i] > max) max = xs[i];
        }
        return sum + product + min + max + xs.length;
    }
}
