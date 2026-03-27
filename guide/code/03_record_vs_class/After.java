import java.util.List;

public class After {
    static final class Entry {
        final String key;
        final String value;

        Entry(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    static String render(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            sb.append(e.key).append('=').append(e.value).append('\n');
        }
        return sb.toString();
    }
}
