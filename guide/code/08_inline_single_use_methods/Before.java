import java.util.List;

public class Before {
    static String render(List<String> values) {
        StringBuilder sb = new StringBuilder();
        appendLines(sb, values);
        appendFooter(sb);
        return sb.toString();
    }

    private static void appendLines(StringBuilder sb, List<String> values) {
        for (String value : values) {
            sb.append(value).append('\n');
        }
    }

    private static void appendFooter(StringBuilder sb) {
        sb.append("-- end --");
    }
}
