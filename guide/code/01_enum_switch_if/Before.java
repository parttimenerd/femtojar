public class Before {
    enum Mode { A, B, C }

    static int value(Mode mode) {
        return switch (mode) {
            case A -> 1;
            case B -> 2;
            case C -> 3;
        };
    }
}
