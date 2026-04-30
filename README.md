# femtojar

[![CI](https://github.com/parttimenerd/femtojar/actions/workflows/ci.yml/badge.svg)](https://github.com/parttimenerd/femtojar/actions/workflows/ci.yml) [![Maven Central Version](https://img.shields.io/maven-central/v/me.bechberger/femtojar)](https://search.maven.org/artifact/me.bechberger/femtojar)

femtojar shrinks executable JARs by bundling `.class` files and resources into a single compressed blob and loading them through a tiny bootstrap classloader at runtime.
If you want, it runs [ProGuard](https://www.guardsquare.com/proguard) before hand to further optimize/shrink the JAR.

**It's for executable JARs, like [jstall](https://github.com/parttimenerd/jstall), not libraries.**

_Femtojar is still an early proof-of-concept, but initial results show promising size reductions of 15-30% for tested shaded/uber JARs, going up to 70% in some cases with ProGuard._

## Features

- Novel single-blob class compression for better cross-class redundancy
- Zopfli compression mode to squeeze out extra bytes at the cost of longer build times
- Bundling of non-`META-INF/*` resources into the blob for better compression (with some caveats, see below)
- Optional [ProGuard](https://www.guardsquare.com/proguard) shrinking/optimization before reencoding
- Maven plugin and standalone CLI

Important: Always black box test the resulting JARs, especially if using ProGuard, as bytecode transformations can break edge cases.

## Maven Plugin

Goal:

- `femtojar:reencode-jars`

Important:

- Use this for executable (typically shaded/uber) JARs.
- It is not designed for library JAR publishing.

### Minimal Plugin Configuration

If your project produces a single JAR named `${project.build.finalName}.jar` (the default for shaded/uber JARs), no `<configuration>` block is needed. The JAR is rewritten in place in `${project.build.directory}/${project.build.finalName}.jar` (typically `target/<artifactId>-<version>.jar`):

```xml
<plugin>
  <groupId>me.bechberger</groupId>
  <artifactId>femtojar</artifactId>
  <version>0.1.5</version>
  <executions>
    <execution>
      <goals>
        <goal>reencode-jars</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

If you need to target a different JAR or customise behaviour, add a `<configuration>` block:

```xml
<plugin>
  <groupId>me.bechberger</groupId>
  <artifactId>femtojar</artifactId>
  <version>0.1.5</version>
  <executions>
    <execution>
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
  <version>0.1.5</version>
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
    <bundleResources>true</bundleResources>
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

### Example Project

See [example-project/pom.xml](example-project/pom.xml) for a complete usage example that shades dependencies, then re-encodes the output JAR.

### Plugin Parameters

| Parameter                     | Description | Default |
|-------------------------------| --- | --- |
| `jars`                        | List of JAR entries to reencode. If omitted, defaults to `${project.build.finalName}.jar` rewritten in place. | Auto-detected |
| `jars[i].in`                  | Input JAR path (relative to `${project.build.directory}` if not absolute) | Required per entry |
| `jars[i].out`                 | Optional output JAR path. If omitted, input JAR is rewritten in place. | Not set |
| `compressionMode`             | Compression preset: `DEFAULT` (deflate), `ZOPFLI` (7 iterations), `MAX` (100 iterations). | `DEFAULT` |
| `bundleResources`             | Bundle non-`META-INF/*` resources into the blob. | `true` |
| `failOnError`                 | Fail build immediately on rewrite errors. | `true` |
| `skip`                        | Skip plugin execution. | `false` |
| `proguard.enabled`            | Run ProGuard before reencoding. | `false` |
| `proguard.prependDefaultConfig` | Prepend the bundled default ProGuard config. | `true` |
| `proguard.configFile`         | Path to a user ProGuard `.pro` config file. | Not set |
| `proguard.options`            | Inline ProGuard options (e.g. `-dontobfuscate`). | Not set |
| `proguard.out`                | Separate ProGuard output path. If omitted, a temp file is used. | Not set |
| `proguard.libraryJars`        | Additional `-libraryjars` paths for ProGuard. | Not set |

All `proguard.*` parameters can also be specified per-JAR inside `<jar><proguard>...</proguard></jar>`. Per-JAR values override the global setting; null fields fall back to the global config.

### ProGuard Configuration

femtojar can optionally run ProGuard before reencoding to shrink/optimize the JAR. This typically yields an additional ~30% size reduction on top of femtojar's normal compression. ProGuard runs in-process via the `proguard.ProGuard` API. All ProGuard settings are grouped under a `<proguard>` element, available both globally and per-JAR.

A bundled default config (`proguard-default.pro`) ships with femtojar and is prepended automatically unless disabled. It includes `java.base` as a library JAR and standard keep rules for main classes, native methods, enums, serialization, and annotations.

**Caveats:**

- ProGuard modifies bytecode — the resulting JAR should be thoroughly tested before shipping.
- Reflection-heavy code (e.g. CLI frameworks like picocli, serialization libraries) typically requires additional `-keep` rules to work correctly.
- ProGuard may be sensitive to the target Java version; verify with the JDK you deploy on.
- No aggressive optimizations are applied by the default config, but ProGuard is still an invasive transform compared to plain reencoding.

```xml
<configuration>
  <proguard>
    <enabled>true</enabled>
    <prependDefaultConfig>true</prependDefaultConfig>
    <configFile>${basedir}/proguard.conf</configFile>
    <options>
      <option>-dontobfuscate</option>
    </options>
  </proguard>
  <jars>
    <jar>
      <in>${project.build.finalName}.jar</in>
      <!-- per-JAR ProGuard overrides -->
      <proguard>
        <options>
          <option>-keep class com.example.api.cli.** { *; }</option>
        </options>
      </proguard>
    </jar>
  </jars>
</configuration>
```

### Per-JAR Overrides

In addition to ProGuard, the following settings can be overridden per-JAR:

| Per-JAR Parameter | Description |
| --- | --- |
| `jars[i].compressionMode` | Override global `compressionMode` for this JAR. |
| `jars[i].bundleResources` | Override global `bundleResources`. |
| `jars[i].proguard` | Override global ProGuard config (same sub-elements). |

## Resource Bundling Caveats

In the default case when not `bundleResources=false`, non-`META-INF/*` resources are packed into the compressed blob for better compression.

The bootstrap handler registers a handler for the custom "femtojar:" URL protocol via
[URL.setURLStreamHandlerFactory](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/URL.html#setURLStreamHandlerFactory(java.net.URLStreamHandlerFactory)),
but this only works with when no other code uses URL.setURLStreamHandlerFactory. This is usually the case, but if not,
then set `bundleResources=false`.

But there might be other issues related to frameworks that expect specific URLs or else,
so test resources properly in your particular use case and disable this feature when you see any problems.

```xml
<configuration>
  <bundleResources>false</bundleResources>
  <!-- ... rest of config -->
</configuration>
```

Or per-JAR:

```xml
<jar>
  <in>${project.build.finalName}.jar</in>
  <bundleResources>false</bundleResources>
</jar>
```

## CLI

A standalone CLI jar is built as an attached shaded artifact.

Build it:

```bash
mvn package
```

Run it:

```bash
java -jar target/femtojar-0.1.2-cli.jar app.jar app-optimized.jar
```

With ProGuard:

```bash
java -jar target/femtojar-0.1.2-cli.jar app.jar app-optimized.jar --proguard --proguard-config proguard.conf
```

Benchmark form:

```bash
java -jar target/femtojar-0.1.2-cli.jar app.jar --benchmark --benchmark-format markdown
```

Benchmark with custom ProGuard rules used for all `proguard*` benchmark rows:

```bash
java -jar target/femtojar-0.1.2-cli.jar app.jar --benchmark --benchmark-format json \
  --proguard-config proguard.conf \
  --proguard-options "-dontwarn"
```

CLI options:

- First positional arg: input JAR path
- Second positional arg (optional): output JAR path (defaults to in-place rewrite)
- `--compression <default|zopfli|max>`: compression preset (`default`=deflate, `zopfli`=7 iterations, `max`=100 iterations)
- `--no-bundle-resources`: disable resource bundling, keep the resource files separate in the JAR
- `--proguard`: run ProGuard before reencoding
- `--proguard-config <path>`: path to a ProGuard `.pro` config file
- `--proguard-options <option>`: inline ProGuard option (repeatable)
- `--proguard-out <path>`: write ProGuard output to a separate path instead of a temp file
- `--no-proguard-default-config`: do not prepend the bundled default ProGuard config
- `--verbose`: print verbose processing output
- `--benchmark`: run a non-destructive benchmark matrix and print size/time comparisons
- `--benchmark-format <text|markdown|json>`: optional benchmark output format (default: `text`)
- `-h`, `--help`: show usage

## Bytecode Size Guide

For source-level bytecode size reduction techniques (with measured before/after numbers and `javap` snippets), see:

- [guide/BYTECODE_SIZE_GUIDE.md](guide/BYTECODE_SIZE_GUIDE.md)
- [guide/code/README.md](guide/code/README.md)

## Comparison with Other Tools

| Tool | Approach | Strengths | Limitations |
| --- | --- | --- | --- |
| [JarTighten](https://github.com/NeRdTheNed/JarTighten) | ZIP entry optimization | Minimal changes to JAR structure, focused recompression | Limited to ZIP compression, no bytecode optimization |
| [Pack200](https://docs.oracle.com/javase/8/docs/technotes/guides/deployment/deployment-guide/pack200.html) | Native compression with cross-class deduplication | Achieved 4-10× reduction, follows Java standards | Deprecated in Java 11, removed in Java 14, maintenance burden |
| [jlink](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jlink.html) | Custom runtime images (JDK 9+) | Reduces runtime size by excluding unused modules | Requires modular JDK, creates platform-specific artifacts |

Feel free to suggest additions or corrections to this comparison.

## Build and Test

```bash
mvn test
mvn package
mvn -Prun-its verify
```

Integration fixtures are documented in [src/it/README.md](src/it/README.md).

## Release Script

Release automation is in [release.py](release.py).

Dry run:

```bash
./release.py --dry-run
```

Patch release without deploy:

```bash
./release.py --patch
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

## Support, Feedback, Contributing

This project is open to feature requests/suggestions, bug reports etc.
via [GitHub](https://github.com/parttimenerd/femtojar/issues) issues.
Contribution and feedback are encouraged and always welcome.

## License

MIT, Copyright 2026 SAP SE or an SAP affiliate company, Johannes Bechberger and contributors