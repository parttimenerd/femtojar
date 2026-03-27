// Verbose error messages: each distinct string is a separate UTF-8 constant pool entry.
// Total string data here: ~400 chars.
public class Before {
    private static final String ERR_NULL    =
        "Validation failed: the input value must not be null or undefined";
    private static final String ERR_EMPTY   =
        "Validation failed: the input value must not be an empty string";
    private static final String ERR_LONG    =
        "Validation failed: the input value must not exceed sixty-four characters in total length";
    private static final String ERR_PATTERN =
        "Validation failed: the input value may only contain alphanumeric characters, underscores, or hyphens";
    private static final String ERR_PREFIX  =
        "Validation failed: the input value must not begin with a hyphen or underscore character";

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
