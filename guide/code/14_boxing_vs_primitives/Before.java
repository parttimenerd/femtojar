import java.util.List;

// Boxed integers: auto-unboxing on each access.
public class Before {
    static int sum(List<Integer> items) {
        int total = 0;
        for (int i = 0; i < items.size(); i++) {
            total += items.get(i); // unbox on every iteration
        }
        return total;
    }
}
