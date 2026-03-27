import java.util.List;

// Index loop avoids Iterator allocation; fewer interface dispatches.
public class After {
    static int sum(List<Integer> items) {
        int total = 0;
        for (int i = 0; i < items.size(); i++) {
            total += items.get(i);
        }
        return total;
    }
}
