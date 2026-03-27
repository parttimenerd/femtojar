import java.util.List;

public class After {
    static int longest(List<String> items) {
        int max = 0;
        for (String item : items) {
            if (item.length() > max) {
                max = item.length();
            }
        }
        return max;
    }
}
