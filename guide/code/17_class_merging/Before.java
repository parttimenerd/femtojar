// Two tiny helper classes — each carries a full .class header, constant pool, constructor.
public class Before {
    static final class A {
        static int f() { return 1; }
    }

    static final class B {
        static int g() { return 2; }
    }

    static int run() {
        return A.f() + B.g();
    }
}
