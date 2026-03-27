// Merged into one class — one constant pool, one header shared.
public class After {
    static int f() { return 1; }
    static int g() { return 2; }

    static int run() {
        return f() + g();
    }
}
