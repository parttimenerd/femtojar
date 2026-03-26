# femtojar

[![CI](https://github.com/parttimenerd/femtojar/actions/workflows/ci.yml/badge.svg)](https://github.com/parttimenerd/femtojar/actions/workflows/ci.yml)

femtojar shrinks executable JARs by bundling `.class` files into a single compressed blob and loading them through a tiny bootstrap classloader at runtime.

_It's still an early proof-of-concept, but initial results show promising size reductions of 20-30% on typical shaded/uber JARs._

## Features

- Single-blob class compression for better cross-class redundancy
- Zopfli compression mode (default) with configurable iterations
- Deflate fallback mode for faster builds
- Optional advanced class ordering (package-aware, hill-climb) for better compression
- Optional bundling of non-`META-INF/*` resources
- Maven plugin goal and standalone CLI
- Integration-test profile (`run-its`) with Maven Invoker fixtures

Zopfli is great, because it essentially generates more effizient deflate/gz streams:

> The output of Zopfli is typically 3–8% smaller than zlib's maximum compression, but takes around 80 times longer

[Wikipedia](https://en.wikipedia.org/wiki/Zopfli)

More info: https://blog.codinghorror.com/zopfli-optimization-literally-free-bandwidth/

## Maven Plugin

Goal:

- `femtojar:reencode-jars`

Important:

- Use this for executable (typically shaded/uber) JARs.
- It is not designed for library JAR publishing.
- With `bundleResources=true`, `getResourceAsStream()` works for bundled resources, but URL-based resource loading (`getResource()`) may break for frameworks expecting real ZIP entries.

### Minimal Plugin Configuration

```xml
<plugin>
  <groupId>me.bechberger</groupId>
  <artifactId>femtojar</artifactId>
  <version>0.1.0</version>
  <executions>
    <execution>
      <id>recompress-jar</id>
      <phase>package</phase>
      <goals>
          <goal>reencode-jars</goal>
      </goals>
    </execution>
  </executions>
  <configuration>
    <jars>
      <jar>
        <in>${project.build.finalName}.jar</in>
      </jar>
    </jars>
  </configuration>
</plugin>
```

### Full Configuration Example

```xml
<plugin>
  <groupId>me.bechberger</groupId>
  <artifactId>femtojar</artifactId>
  <version>0.1.0</version>
  <executions>
    <execution>
      <id>recompress-jar</id>
      <phase>package</phase>
      <goals>
        <goal>reencode-jars</goal>
      </goals>
    </execution>
  </executions>
  <configuration>
    <failOnError>true</failOnError>
    <skip>false</skip>
    <compressionMode>MAX</compressionMode>
    <bundleResources>false</bundleResources>
    <jars>
      <jar>
        <in>${project.build.finalName}.jar</in>
        <out>${project.build.finalName}-optimized.jar</out>
      </jar>
      <jar>
        <in>other.jar</in>
      </jar>
    </jars>
  </configuration>
</plugin>
```

### Plugin Parameters

| Parameter | Description | Default |
| --- | --- | --- |
| `jars` | List of JAR entries to reencode. Each entry has an `<in>` path and optional `<out>` path. | Required |
| `jars[i].in` | Input JAR path (relative to `${project.build.directory}` if not absolute) | Required per entry |
| `jars[i].out` | Optional output JAR path. If omitted, input JAR is rewritten in place. | Not set |
| `compressionMode` | Compression preset: `DEFAULT` (deflate), `ZOPFLI` (7 iterations), `MAX` (100 iterations). | `DEFAULT` |
| `bundleResources` | Bundle non-`META-INF/*` resources into the blob. | `false` |
| `advancedMode` | Class ordering strategy: empty (lexical), `package`, or `hill-climb`. | Not set (lexical) |
| `advancedIterations` | Iterations for hill-climb modes. Ignored for package mode. | `-1` |
| `parallel` | Evaluate random swap candidates in parallel in hill-climb modes. | `false` |
| `failOnError` | Fail build immediately on rewrite errors. | `true` |
| `skip` | Skip plugin execution. | `false` |

## CLI

A standalone CLI jar is built as an attached shaded artifact.

Build it:

```bash
mvn package
```

Run it:

```bash
java -jar target/femtojar-0.1.0-cli.jar --in app.jar --out app-optimized.jar
```

Positional form:

```bash
java -jar target/femtojar-0.1.0-cli.jar app.jar app-optimized.jar --compression default
```

Benchmark form:

```bash
java -jar target/femtojar-0.1.0-cli.jar --benchmark --in app.jar
```

CLI options:

- `--in <path>`: input JAR path
- `--out <path>`: output JAR path (optional, defaults to in-place rewrite)
- `--compression <default|zopfli|max>`: compression preset (`default`=deflate, `zopfli`=7 iterations, `max`=100 iterations)
- `--bundle-resources`: enable resource bundling
- `--no-bundle-resources`: disable resource bundling
- `--advanced-mode <package|hill-climb>`: class ordering strategy (`package` = group by package+size, `hill-climb` = proxy-guided swap perturbations)
- `--advanced-iterations <N>`: iterations for hill-climb modes
- `--parallel`: evaluate swap candidates in parallel in hill-climb modes
- `--rverbose` (or `--verbose`): print ordering iteration logs (size per trial)
- `--benchmark`: run a non-destructive benchmark matrix in parallel and print size/time comparisons, including best relative improvement vs default
- `--benchmark-format <text|markdown>`: optional benchmark output format (default: `text`)
- `-h`, `--help`: show usage

## Advanced Class Ordering

femtojar can optionally reorder class files before creating the compressed blob.

Why this exists:

- Deflate/Zopfli use a fixed 32KB sliding window.
- In one large bundled stream, class order affects which repeated byte patterns are still inside that window.
- A smarter order can produce slightly smaller output, especially for large shaded JARs.

Two modes are available:

### Package mode (`--advanced-mode package`)

Groups classes by package, then sorts by file size within each package.
Classes in the same package share constant-pool strings (package name, common imports),
so placing them adjacent keeps repeated patterns inside the deflate window.
Deterministic and fast — no compression measurement needed.

### Hill-climb mode (`--advanced-mode hill-climb --advanced-iterations N`)

Starts from package-aware ordering and makes N random swap perturbations.
Uses fast deflate (level 1) as a proxy to measure each candidate — so each
iteration is very cheap (~100x faster than real Zopfli).
Keeps a swap only if the proxy size improves.
Best balance of quality and speed.

Optional: add `--parallel` to evaluate multiple random swap candidates per
iteration and keep the best one.

## Build and Test

```bash
mvn test
mvn package
mvn -Prun-its verify
```

Integration fixtures are documented in [src/it/README.md](src/it/README.md).

## CI Workflow

GitHub Actions workflow is in [.github/workflows/ci.yml](.github/workflows/ci.yml) and includes:

- OS/JDK test matrix
- `run-its` integration verification
- example project packaging check
- artifact upload for plugin and CLI jars

## Release Script

Release automation is in [release.py](release.py).

Dry run:

```bash
./release.py --dry-run
```

Patch release without deploy:

```bash
./release.py --patch --no-deploy
```

Default release flow:

- require release notes in `CHANGELOG.md` under `## [Unreleased]`
- roll `## [Unreleased]` notes into `## [<version>] - <YYYY-MM-DD>`
- bump version in root `pom.xml`
- update version references in `README.md`
- update femtojar plugin version in `example-project/pom.xml`
- run tests and build artifacts
- optional `mvn clean deploy -P release`
- commit + tag (and optional push)

## Example Project

See [example-project/pom.xml](example-project/pom.xml) for a complete usage example that shades dependencies, then re-encodes the output JAR.

## Support, Feedback, Contributing

This project is open to feature requests/suggestions, bug reports etc.
via [GitHub](https://github.com/parttimenerd/jstall/issues) issues.
Contribution and feedback are encouraged and always welcome.

## License

MIT, Copyright 2026 SAP SE or an SAP affiliate company, Johannes Bechberger and contributors

464 -rw-r--r--   1 i560383  staff   231K Mar 25 10:02 demo-app-1.0-SNAPSHOT-small.jar
16 -rw-r--r--   1 i560383  staff   4.2K Mar 25 10:06 demo-app-1.0-SNAPSHOT.jar
600 -rw-r--r--   1 i560383  staff   299K Mar 25 10:06 demo-app-optimized.jar
464 -rw-r--r--   1 i560383  staff   231K Mar 25 10:06 demo-app-small.jar
816 -rw-r--r--   1 i560383  staff   408K Mar 25 10:06 demo-app.jar


464 -rw-r--r--   1 i560383  staff   231K Mar 25 10:02 demo-app-1.0-SNAPSHOT-small.jar
16 -rw-r--r--   1 i560383  staff   4.2K Mar 25 10:07 demo-app-1.0-SNAPSHOT.jar
600 -rw-r--r--   1 i560383  staff   299K Mar 25 10:07 demo-app-optimized.jar
600 -rw-r--r--   1 i560383  staff   297K Mar 25 10:07 demo-app-small.jar
816 -rw-r--r--   1 i560383  staff   408K Mar 25 10:07 demo-app.jar


Idea:

current: demo-app.jar
408K Mar 25 10:16 demo-app.jar

just recompression:
299K Mar 25 10:07 demo-app-optimized.jar

just proguard in application mode:
231K Mar 25 10:16 demo-app-small.jar

combined:
171K Mar 25 10:16 demo-app-optimized.jar

So possibly add another mode: proguard-application
Caveats: Modifies the bytecode and is possibly Java version dependent
But proguard is a commonly used tool and no aggressive optimizations are applied.
The biggest caveat is that it requires customisation of the proguard rules
to work, especially for reflection have (i.e. CLI library) code.
Implementing doesn't hurt, but the resulting JAR should be properly tested.