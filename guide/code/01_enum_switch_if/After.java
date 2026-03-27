public class After {
    enum Mode { A, B, C }

    static int value(Mode mode) {
        if (mode == Mode.A) {
            return 1;
        }
        if (mode == Mode.B) {
            return 2;
        }
        return 3;
    }
}
