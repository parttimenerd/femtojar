public class Before {
    static int classify(Object value) {
        return switch (value) {
            case null -> -1;
            case String s -> s.length();
            case Integer i -> i;
            default -> 0;
        };
    }
}
