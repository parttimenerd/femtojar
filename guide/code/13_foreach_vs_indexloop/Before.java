import java.util.List;

// Enhanced for over a List dispatches through Iterator (allocates + interface calls).
public class Before {
    static int sum(List<Integer> items) {
        int total = 0;
        for (int x : items) {
            total += x;
        }
        return total;
    }
}
