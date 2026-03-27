import java.util.List;

public class Before {
    static int longest(List<String> items) {
        return items.stream().mapToInt(String::length).max().orElse(0);
    }
}
