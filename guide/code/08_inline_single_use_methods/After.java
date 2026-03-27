import java.util.List;

public class After {
    static String render(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            sb.append(value).append('\n');
        }
        sb.append("-- end --");
        return sb.toString();
    }
}
