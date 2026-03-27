import java.util.List;

public class Before {
    record Entry(String key, String value) {}

    static String render(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            sb.append(e.key()).append('=').append(e.value()).append('\n');
        }
        return sb.toString();
    }
}
