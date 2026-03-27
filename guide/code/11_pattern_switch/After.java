public class After {
    static int classify(Object value) {
        if (value == null) {
            return -1;
        }
        if (value instanceof String) {
            return ((String) value).length();
        }
        if (value instanceof Integer) {
            return ((Integer) value).intValue();
        }
        return 0;
    }
}
