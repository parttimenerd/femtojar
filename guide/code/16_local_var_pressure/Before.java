// Many live local variable slots — slots beyond 3 require wider opcodes (iload vs iload_N).
public class Before {
    static int compute(int[] xs) {
        int sum = 0;
        int product = 1;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        int count = xs.length;          // slot 5 — needs iload (2-byte)
        for (int i = 0; i < count; i++) {
            int v = xs[i];              // slot 6 — needs iload (2-byte)
            sum += v;
            product *= v;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        return sum + product + min + max + count;
    }
}
