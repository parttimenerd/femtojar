// Concise error messages: fewer UTF-8 bytes in constant pool.
// Total string data here: ~128 chars (saves ~272 chars vs Before).
public class After {
    private static final String ERR_NULL    = "input is null";
    private static final String ERR_EMPTY   = "input is empty";
    private static final String ERR_LONG    = "input exceeds 64 chars";
    private static final String ERR_PATTERN = "input has invalid chars (use [A-Za-z0-9_-])";
    private static final String ERR_PREFIX  = "input must not start with - or _";

    static void validate(String input) {
        if (input == null)
            throw new IllegalArgumentException(ERR_NULL);
        if (input.isEmpty())
            throw new IllegalArgumentException(ERR_EMPTY);
        if (input.length() > 64)
            throw new IllegalArgumentException(ERR_LONG);
        if (!input.matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException(ERR_PATTERN);
        if (input.charAt(0) == '-' || input.charAt(0) == '_')
            throw new IllegalArgumentException(ERR_PREFIX);
    }
}
