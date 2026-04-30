# Java Bytecode Size Reduction

**What this is optimizing:** Femtojar optimizes your existing JARs without any code changes, but
it doesn't work libraries and might benefit from optimizing the source code for small bytecode size.
Which is what this document is for. But only do this if you really need to (or of course if you're curious).

The code folder of this guide contains the before and after for all examples discussed in this guide
and helps you see the differences directly.

How I would use this document for manually optimizing: Don't. But if you really want, look for all occurrences of the
before patterns and apply the changes (or tell and LLM to do this).

TL;DR: __Syntax sugar has downsides, its like real sugar, you might want to avoid it if you're concerned about size.__

Let's start with the most basic thing:


## Every method has a cost

I would recommend inline trivial one/few-use methods, as every such method adds more than 80 bytes of [metadata](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-4.html#jvms-4.6) and call overhead.

Consider the following [example](code/08_inline_single_use_methods/Before.java):

```java
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
```

When we inline the single use methods, we loose a bit of readability:

```java
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
```
We go from 1140 bytes to 844 bytes, saving -296 bytes (-26.0%) in the process.
We can counter the loss in readability by adding comments.

You can see the bytecode diff [here](code/08_inline_single_use_methods/javap_diff_default.diff).

Please be aware that you should not inline everything as code duplication also increases the bytecode size.

---

## 2. Language Features That Commonly Inflate Bytecode

### 2.1 Small `enum switch`: prefer `if/else` in size-critical code

**Why it can cost bytes:** enum switches can compile in (at least) two common shapes, depending on `javac` version/target and how/where the enum is declared:

1. **`$SwitchMap` helper:** the compiler creates a synthetic `$SwitchMap$...` `int[]` mapping and switches over that mapping (sometimes stored in an extra helper class like `YourClass$1.class`). This can add constant-pool entries and can add an extra `.class` file.
2. **Direct ordinal switch:** the compiler switches directly on `enum.ordinal()` via `tableswitch`/`lookupswitch`, with no extra helper class.

**What this looks like in bytecode:**
- Helper form: starts with `getstatic <Helper>.$SwitchMap$...`, then `ordinal()`, then `iaload`, then a `lookupswitch`/`tableswitch`.
- Direct form: starts with `ordinal()`, then a `tableswitch`/`lookupswitch`.

**Measured examples (from this repo):**
- In the femtocli case study (`BYTECODE_SIZE_GUIDE.md`), one enum switch created a synthetic helper class of **1,017 B** plus **~155 B** in the containing class.
- In `BytecodeSizeDemo.java` (compiled with `--release 21`), the *containing class* delta for enum-switch → `if/else` was **−84 B**; with the compiler used for those numbers the enum-switch compiled without an extra helper class, so that number does **not** include “deleted helper `.class`” savings.

Example files:
- [Before.java](code/01_enum_switch_if/Before.java)
- [After.java](code/01_enum_switch_if/After.java)
- [javap_diff_default.diff](code/01_enum_switch_if/javap_diff_default.diff)

**Practical takeaway:** for small case-counts (≈ ≤ 5) in size-critical modules, `if/else` is frequently smaller and avoids compiler scaffolding.

```java
enum Mode { A, B, C }

static int sizey(Mode m) {
  // Often larger than it looks for small case counts.
  switch (m) {
    case A: return 1;
    case B: return 2;
    default: return 3;
  }
}

static int smaller(Mode m) {
  if (m == Mode.A) return 1;
  if (m == Mode.B) return 2;
  return 3;
}
```

**References:**
- [JVMS: `tableswitch` and `lookupswitch`](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-6.html)
- [SCons issue with `$SwitchMap` enum-switch details](https://github.com/SCons/scons/issues/2547)

### 2.2 Small `String switch`: prefer `if ("x".equals(s))` chains

**Why it can cost bytes:** `String switch` compiles to hashing + branching + equality checks.

**What the compiler typically emits:** `hashCode()` + `lookupswitch` on hash buckets, `equals()` checks for collisions, then a second jump table for the actual cases. For a handful of cases, this can easily be **~200–300 B** of extra bytecode in a single method.

```java
static int sizey(String s) {
  switch (s) {
    case "--help": return 1;
    case "--version": return 2;
    default: return 0;
  }
}

static int smaller(String s) {
  if ("--help".equals(s)) return 1;
  if ("--version".equals(s)) return 2;
  return 0;
}
```

**References:**
- [How `String switch` compiles (Stack Overflow discussion)](https://stackoverflow.com/questions/22110707/how-is-string-in-switch-statement-more-efficient-than-corresponding-if-else-stat)
- [BytecodeSizeDemo.java (reproducible micro-deltas)](../BytecodeSizeDemo.java)

**Measured example (from this repo):** in `BytecodeSizeDemo.java` (Java 21), replacing a `String switch` with an `if/else` chain saved **−234 B** in the example class.

Example files:
- [Before.java](code/02_string_switch_if/Before.java)
- [After.java](code/02_string_switch_if/After.java)
- [javap_diff_default.diff](code/02_string_switch_if/javap_diff_default.diff)

### 2.3 Records: great ergonomics, often extra bytecode

Records generate accessors plus `equals/hashCode/toString`.

```java
// More generated methods.
record Pair(String a, String b) {}

// Fewer generated methods (but you lose the auto methods).
final class Pair2 {
  final String a, b;
  Pair2(String a, String b) { this.a = a; this.b = b; }
}
```

**Rule of thumb:** in size-critical areas, use the simplest data shape that still keeps code correct.

**Measured example (from this repo, `BytecodeSizeDemo.java`, `--release 21`):**
- Record helper variant: **2,769 B** total
- Same data as a `final`-field helper class: **1,711 B** (−1,058 B)
- Same data as a helper class with private fields + getters: **1,902 B** (−867 B)
- No helper class (parallel arrays): **735 B** (−2,034 B)

Example files:
- [Before.java](code/03_record_vs_class/Before.java)
- [After.java](code/03_record_vs_class/After.java)
- [javap_diff_default.diff](code/03_record_vs_class/javap_diff_default.diff)

**References:**
- [JEP 395: Records](https://openjdk.org/jeps/395)

### 2.4 Streams and lambdas: convenient, but can add bootstrap/metadata

Even when lambdas don’t create extra `.class` files, they can add invokedynamic/bootstrap metadata and pull in more library usage patterns.

```java
// Compact source, potentially heavier bytecode + library surface.
static int maxSizey(int[] xs) {
  return java.util.Arrays.stream(xs).max().orElse(0);
}

// Usually very small bytecode.
static int maxSmaller(int[] xs) {
  int m = 0;
  for (int x : xs) if (x > m) m = x;
  return m;
}
```

**Positive invokedynamic note (JEP 280):** Since Java 9, *string concatenation* (`"hello " + name`) was changed to use `invokedynamic` instead of `StringBuilder.append` chains. This is a JDK-level space-saver — you get it for free when targeting Java 9+. The point here is about *application-level* lambdas and streams adding their own invokedynamic overhead on top of that.

**Measured example (from this repo):** one stream-to-loop rewrite in a help-rendering hotspot saved **−417 B** in the containing class in the femtocli case study.

Example files:
- [Before.java](code/04_stream_vs_loop/Before.java)
- [After.java](code/04_stream_vs_loop/After.java)
- [javap_diff_default.diff](code/04_stream_vs_loop/javap_diff_default.diff)

**References:**
- [JEP 280: Indify String Concatenation (invokedynamic context)](https://openjdk.org/jeps/280)
- [Baeldung: String Concatenation with InvokeDynamic](https://www.baeldung.com/java-string-concatenation-invoke-dynamic)
- [BYTECODE_SIZE_GUIDE.md (repo case study)](BYTECODE_SIZE_GUIDE.md)

### 2.5 Enhanced `for` loops and iterators can add overhead

In tight code, prefer loops that compile to minimal bytecode.

```java
// May allocate/dispatch via Iterator (esp. for non-arrays).
static int sizey(java.util.List<Integer> xs) {
  int s = 0;
  for (int x : xs) s += x;
  return s;
}

// Often smaller: index loop (still boxed here, but fewer interface calls).
static int smaller(java.util.List<Integer> xs) {
  int s = 0;
  for (int i = 0; i < xs.size(); i++) s += xs.get(i);
  return s;
}

// Smallest usually: primitive array.
static int smallest(int[] xs) {
  int s = 0;
  for (int i = 0; i < xs.length; i++) s += xs[i];
  return s;
}
```

**Measured example ([`guide/code/13_foreach_vs_indexloop`](code/13_foreach_vs_indexloop), javac 26-ea): −85 B class / −41 B deflate.**

Example files:
- [Before.java](code/13_foreach_vs_indexloop/Before.java)
- [After.java](code/13_foreach_vs_indexloop/After.java)
- [javap_diff_default.diff](code/13_foreach_vs_indexloop/javap_diff_default.diff)

The bytecode shape difference is visible in `javap`:

```
// BEFORE (enhanced for over List)
  3: invokeinterface java/util/List.iterator:()Ljava/util/Iterator;
 10: invokeinterface java/util/Iterator.hasNext:()Z
 19: invokeinterface java/util/Iterator.next:()Ljava/lang/Object;

// AFTER (index loop)
  6: invokeinterface java/util/List.size:()I
 17: invokeinterface java/util/List.get:(I)Ljava/lang/Object;
 30: iinc          2, 1
```

In this micro-case, removing iterator dispatch and using a simple index counter is smaller.

**References:**
- [JLS: Enhanced `for` statement](https://docs.oracle.com/javase/specs/jls/se21/html/jls-14.html#jls-14.14.2)

### 2.6 Boxing: prefer primitives in size-critical code

Boxing pulls in more API surface and often more bytecode around conversions.

```java
// Boxed: more calls + conversions.
static int sizey(java.util.List<Integer> xs) {
  return xs.get(0) + xs.get(1);
}

// Primitive: straightforward bytecode.
static int smaller(int[] xs) {
  return xs[0] + xs[1];
}
```

**Measured example ([`guide/code/14_boxing_vs_primitives`](code/14_boxing_vs_primitives), javac 26-ea): −220 B class / −118 B deflate.**

Example files:
- [Before.java](code/14_boxing_vs_primitives/Before.java)
- [After.java](code/14_boxing_vs_primitives/After.java)
- [javap_diff_default.diff](code/14_boxing_vs_primitives/javap_diff_default.diff)

**References:**
- [Java Language Specification: Boxing conversions](https://docs.oracle.com/javase/specs/jls/se21/html/jls-5.html#jls-5.1.7)

### 2.7 Reduce local-variable pressure where it is easy ("Rule of Four")

The JVM has compact single-byte local-variable opcodes for exactly slots 0-3 (`iload_0`..`iload_3`, `istore_0`..`istore_3`; slot 0 is `this` in instance methods). You cannot directly assign slots in Java source, but keeping the number of simultaneously live locals low often helps hot locals stay in those slots.

```java
static int sizey(int[] xs) {
  int sum = 0;
  int i = 0;
  int n = xs.length;
  int tmp = 0; // extra local
  for (; i < n; i++) {
    tmp = xs[i];
    sum += tmp;
  }
  return sum;
}

static int smaller(int[] xs) {
  int sum = 0;
  for (int i = 0; i < xs.length; i++) sum += xs[i];
  return sum;
}
```

**Measured example ([`guide/code/16_local_var_pressure`](code/16_local_var_pressure), javac 26-ea): −14 B class / −14 B deflate.**

Example files:
- [Before.java](code/16_local_var_pressure/Before.java)
- [After.java](code/16_local_var_pressure/After.java)
- [javap_diff_default.diff](code/16_local_var_pressure/javap_diff_default.diff)

**When this helps most:** tiny hot methods where a few bytes matter and readability is not reduced.

**References:**
- [JVMS: Local variable instructions (`iload_0`..`iload_3`)](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-6.html)


---

## 3. Strings and Constant-Pool Growth

### 3.1 Shorter string literals reduce constant-pool size directly

Every distinct string literal becomes a UTF-8 entry in the constant pool: 3 bytes of overhead plus one byte per character. Verbose error messages, long identifiers, and detailed log strings add up directly.

**Measured example ([`guide/code/19_string_literal_shortening`](code/19_string_literal_shortening), javac 26-ea): −279 B class / −86 B deflate.**

Example files:
- [Before.java](code/19_string_literal_shortening/Before.java)
- [After.java](code/19_string_literal_shortening/After.java)
- [javap_diff_default.diff](code/19_string_literal_shortening/javap_diff_default.diff)

Replacing 5 verbose error messages (~400 total chars) with concise equivalents (~128 chars):

```java
// BEFORE — one of five verbose messages; ~400 chars total string data.
private static final String ERR_NULL =
    "Validation failed: the input value must not be null or undefined";

// AFTER — concise equivalents; ~128 chars total.
private static final String ERR_NULL = "input is null";
```

The `ldc` operand shrinks directly in the class file:

```
// BEFORE
  8: ldc  "Validation failed: the input value must not be null or undefined"

// AFTER
  8: ldc  "input is null"
```

Each character removed saves exactly 1 byte in the constant pool UTF-8 entry. Savings are directly proportional to the total character difference across all literals.

**References:**
- [JVMS: Constant Pool](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-4.html#jvms-4.4)

### 3.2 Cross-class constant sharing (string hoisting)

Constant pools are per-class. When multiple classes carry identical long string literals (HTTP header names, format strings, option labels), hoisting those strings into a shared constants holder removes them from each component class's pool.

**Important caveat:** `static final String` fields initialized with a literal are compile-time constants in Java — `javac` folds their value directly into each call site instead of emitting `getstatic`. To prevent this folding and force genuine load-from-field semantics, initialize the constants via a `static {}` block.

**Measured example ([`guide/code/20_shared_constants`](code/20_shared_constants), javac 26-ea): −342 B class and +179 B in this synthetic cross-file deflate metric.** Four inner classes, each carrying 4 long header strings (~186 chars duplicated), consolidated into one `Headers` holder.

Example files:
- [Before.java](code/20_shared_constants/Before.java)
- [After.java](code/20_shared_constants/After.java)
- [javap_diff_default.diff](code/20_shared_constants/javap_diff_default.diff)

```java
// BEFORE — each component class carries the strings in its own constant pool.
static final class Sender {
    private static final String CONTENT_TYPE =
        "Content-Type: application/json; charset=utf-8";
    // ... Receiver, Middleware, Tracer each duplicate the same four strings.
}

// AFTER — strings live once; components load via getstatic.
static final class Headers {
    static final String CONTENT_TYPE;   // static block prevents compile-time folding
    static { CONTENT_TYPE = "Content-Type: application/json; charset=utf-8"; }
}
static final class Sender {
    static String header(String id) { return Headers.CONTENT_TYPE + ...; }
}
```

The constant pool for each component class changes from carrying full string data to carrying only Fieldref entries:

```
// BEFORE (Before$Sender constant pool, abbreviated):
  #16 = String  "Content-Type: application/json; charset=utf-8"
  #20 = String  "Accept: application/vnd.api+json; version=3.1"
  #23 = String  "User-Agent: femtojar-client/1.0.0 (...)"
  #25 = String  "X-Correlation-ID: "
  // ≈199 bytes of string data — repeated in Receiver, Middleware, Tracer

// AFTER (After$Sender constant pool, abbreviated):
  #7  = Fieldref  After$Headers.CONTENT_TYPE:Ljava/lang/String;
  #13 = Fieldref  After$Headers.ACCEPT:Ljava/lang/String;
  #16 = Fieldref  After$Headers.USER_AGENT:Ljava/lang/String;
  #19 = Fieldref  After$Headers.CORRELATION:Ljava/lang/String;
  // strings exist only once, in After$Headers.class
```

**Caveats:**
- Break-even needs roughly N × string_bytes > N × fieldref_overhead + string_bytes, which works out to ~60–70 bytes per shared string shared across 3+ classes.
- In a standard JAR (ZIP format), each `.class` is compressed independently, so raw class-byte reduction is the relevant metric for this technique.
- The benchmark in this guide computes one synthetic deflate stream across all classes, where duplicated literals compress very well. That is why this row shows +179 B deflate despite -342 B class.
- `femtojar` does true cross-file compression/re-encoding, so repeated literals across classes may compress better there than in plain JAR ZIP. Evaluate cross-class hoisting on your final artifact path (plain JAR vs femtojar output), not only on the synthetic deflate column.

**References:**
- [JVMS: Constant Pool](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-4.html#jvms-4.4)
- [JLS §15.29: Constant Expressions](https://docs.oracle.com/javase/specs/jls/se21/html/jls-15.html#jls-15.29)

---

## 4. “Helper” Patterns That Secretly Add Classes and Methods

### 4.1 Avoid tiny helper classes in size-critical modules

```java
// Helper class = extra .class file.
final class HelpEntry {
  final String k, v;
  HelpEntry(String k, String v) { this.k = k; this.v = v; }
}

// Alternative: parallel arrays (uglier, smaller).
static final String[] KEYS = {"-h", "--help"};
static final String[] VALS = {"help", "help"};
```

**Measured example ([`guide/code/17_class_merging`](code/17_class_merging), javac 26-ea): −631 B class / −142 B deflate.**

Example files:
- [Before.java](code/17_class_merging/Before.java)
- [After.java](code/17_class_merging/After.java)
- [javap_diff_default.diff](code/17_class_merging/javap_diff_default.diff)

**References:**

- [JVMS: Inner classes and attributes](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-4.html)



### 4.3 Class/interface merging and nested-class inlining (advanced)

Merging very small types can reduce per-class overhead (headers, constant pools, attributes), but this is an invasive transform and can affect reflection, stack traces, and serialization behavior.

```java
// BEFORE: two classes.
final class A { static int f() { return 1; } }
final class B { static int g() { return 2; } }

// AFTER: one class.
final class AB { static int f() { return 1; } static int g() { return 2; } }
```

**Measured example ([`guide/code/17_class_merging`](code/17_class_merging), javac 26-ea): −631 B class / −142 B deflate.**

Example files:
- [Before.java](code/17_class_merging/Before.java)
- [After.java](code/17_class_merging/After.java)
- [javap_diff_default.diff](code/17_class_merging/javap_diff_default.diff)

Treat as a build-time transform for tightly constrained artifacts, not a default code style.

**References:**
- [JVMS: ClassFile structure](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-4.html)

---

## 5. Collections and “Nice” Wrappers

### 5.1 Avoid wrapping collections if you don’t need to

Wrappers can add extra method calls and sometimes additional class references.

```java
static java.util.Map<String, String> sizey(java.util.Map<String, String> m) {
  return java.util.Collections.unmodifiableMap(m);
}

static java.util.Map<String, String> smaller(java.util.Map<String, String> m) {
  // If the map never escapes mutable, keep it as-is.
  return m;
}
```

**References:**
- [Java `Collections.unmodifiableMap` docs](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Collections.html#unmodifiableMap(java.util.Map))
- [BytecodeSizeDemo.java (reproducible micro-deltas)](../BytecodeSizeDemo.java)

**Measured example (from this repo, `BytecodeSizeDemo.java`, `--release 21`):** removing a `Collections.unmodifiableMap(...)` wrapper saved **−122 B** in the demo class.

Example files:
- [Before.java](code/07_unmodifiable_map/Before.java)
- [After.java](code/07_unmodifiable_map/After.java)
- [javap_diff_default.diff](code/07_unmodifiable_map/javap_diff_default.diff)

---


## 6. Classfile Metadata: Remove What You Don’t Need

### 6.1 Compile without debug info (when you can)

Debug attributes are useful for stack traces and debugging, but they do take space.

```xml
<!-- Maven compiler: disable debug information -->
<plugin>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <debug>false</debug>
    <parameters>false</parameters>
  </configuration>
</plugin>
```

If you compile directly with `javac`, the equivalent is:

```bash
javac -g:none --release 21 $(find src -name '*.java')
```

**Measured example (same source compiled two ways, javac 26-ea): 1,441 B with debug vs 1,180 B with `-g:none` (−261 B, −18.1%).**

Example source used:
- [Before.java](code/04_stream_vs_loop/Before.java)

Example command:

```bash
t=$(mktemp -d)
cp guide/code/04_stream_vs_loop/Before.java "$t/Before.java"
javac -d "$t/g" -g "$t/Before.java"
javac -d "$t/ng" -g:none "$t/Before.java"
wc -c "$t/g/Before.class" "$t/ng/Before.class"
```

**References:**
- [Maven Compiler Plugin parameters](https://maven.apache.org/plugins/maven-compiler-plugin/compile-mojo.html)
- [JVMS: Attributes overview](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-4.html#jvms-4.7)


## 10. Practical Checklist

For a size-sensitive module (agent, CLI bootstrap, plugin runtime):

1. Measure `.class` bytes and `.jar` bytes.
2. Reduce dependency surface (avoid shading; if shading, minimize carefully).
3. Prefer fewer classes + fewer methods.
4. Avoid “heavy” language sugar in tight code (streams, big switches, records).
5. Disable debug/parameter metadata where acceptable.
6. Recompress the final jar.
7. Re-run tests.

```bash
mvn test
```

**References:**
- [Stack Overflow: reduce external jar size (resource/class trimming ideas)](https://stackoverflow.com/questions/2606424/reduce-external-jar-file-size)

---

## A. Legacy / Pre-Java-17 Notes

These techniques are **not relevant when targeting Java 17+** but may matter if you maintain codebases that need to run on or compile against older targets.

### A.1 Nested classes accessing private outer members generate synthetic accessors (pre-Java 11)

Before Java 11's nest-based access control ([JEP 181](https://openjdk.org/jeps/181)), the compiler emitted synthetic `access$000`-style bridge methods whenever an inner class accessed a `private` member of its outer class. Each such method added ~60–80 bytes of overhead.

```java
class Outer {
  private int secret = 42;

  class Inner {
    int leak() {
      return secret; // emits access$000 on classfile targets < Java 11
    }
  }
}
```

**On Java 17+ targets this is a non-issue** — the JVM uses nest membership instead. If you still target Java 8 classfiles (`--release 8`), either:
- upgrade the target, or
- make the accessed member package-private so no accessor is needed.

**References:**
- [JEP 181: Nest-Based Access Control](https://openjdk.org/jeps/181)
- [JVMS: NestHost / NestMembers attributes](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-4.html)

### A.2 Pack200 (removed in Java 14)

Pack200 used semantics of the class-file format to deduplicate constant pool entries *across* the entire archive, achieving 4–10× reduction. It was deprecated in Java 11 and fully removed in Java 14 ([JEP 367](https://openjdk.org/jeps/367)).

```bash
# Only available on legacy JDKs (≤ Java 13):
pack200 --effort=9 --segment-limit=-1 app.pack.gz app.jar
unpack200 app.pack.gz app.jar
```

Reasons for removal (from the JEP):

1. Historically, slow downloads of the JDK over 56k modems were an impediment to Java adoption. The relentless growth in JDK functionality caused the download size to swell, further impeding adoption. Compressing the JDK with Pack200 was a way to mitigate the problem. However, time has moved on: download speeds have improved, and JDK 9 introduced new compression schemes for both the Java runtime (JEP 220) and the modules used to build the runtime (JMOD). Consequently, JDK 9 and later do not rely on Pack200; JDK 8 was the last release compressed with pack200 at build time and uncompressed with unpack200 at install time. In summary, a major consumer of Pack200 -- the JDK itself -- no longer needs it.

2. Beyond the JDK, it was attractive to compress client applications, and especially applets, with Pack200. Some deployment technologies, such as Oracle's browser plug-in, would uncompress applet JARs automatically. However, the landscape for client applications has changed, and most browsers have dropped support for plug-ins. Consequently, a major class of consumers of Pack200 -- applets running in browsers -- are no longer a driver for including Pack200 in the JDK.

3. Pack200 is a complex and elaborate technology. Its file format is tightly coupled to the class file format and the JAR file format, both of which have evolved in ways unforeseen by JSR 200. (For example, JEP 309 added a new kind of constant pool entry to the class file format, and JEP 238 added versioning metadata to the JAR file format.) The implementation in the JDK is split between Java and native code, which makes it hard to maintain. The API in java.util.jar.Pack200 was detrimental to the modularization of the Java SE Platform, leading to the removal of four of its methods in Java SE 9. Overall, the cost of maintaining Pack200 is significant, and outweighs the benefit of including it in Java SE and the JDK.

**References:**
- [JEP 367: Remove the Pack200 Tools and API](https://openjdk.org/jeps/367)