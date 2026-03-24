# femtojar

`femtojar` shrinks executable JARs by bundling `.class` files into a single compressed blob and loading them through a tiny bootstrap classloader at runtime.

_It's still an early proof-of-concept, but initial results show promising size reductions of 20-30% on typical shaded/uber JARs._

## Features

- Single-blob class compression for better cross-class redundancy
- Zopfli compression mode (default) with configurable iterations
- Deflate fallback mode for faster builds
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
  <version>0.0.0</version>
  <executions>
    <execution>
      <goals>
        <goal>reencode-jars</goal>
      </goals>
    </execution>
  </executions>
  <configuration>
    <jars>
      <jar>${project.build.finalName}.jar</jar>
    </jars>
  </configuration>
</plugin>
```

### Full Configuration Example

```xml
<plugin>
  <groupId>me.bechberger</groupId>
  <artifactId>femtojar</artifactId>
  <version>0.0.0</version>
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
    <zopfli>true</zopfli>
    <zopfliIterations>100</zopfliIterations>
    <bundleResources>false</bundleResources>
    <jars>
      <jar>${project.build.finalName}.jar</jar>
    </jars>
    <outJars>
      <outJar>${project.build.finalName}-optimized.jar</outJar>
    </outJars>
  </configuration>
</plugin>
```

### Plugin Parameters

| Parameter | Description | Default |
| --- | --- | --- |
| `jars` | Input JAR list. Relative paths are resolved against `${project.build.directory}`. | Required |
| `outJars` | Optional output JAR list mapped 1:1 to `jars`. If omitted, rewrite in-place. | Not set |
| `zopfli` | Use Zopfli for the shared blob compression. | `true` |
| `zopfliIterations` | Zopfli iteration count. Higher can compress better, but is slower. | `100` |
| `bundleResources` | Bundle non-`META-INF/*` resources into the blob. | `false` |
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
java -jar target/femtojar-0.0.0-cli.jar --in app.jar --out app-optimized.jar
```

Positional form:

```bash
java -jar target/femtojar-0.0.0-cli.jar app.jar app-optimized.jar --deflate
```

Benchmark form:

```bash
java -jar target/femtojar-0.0.0-cli.jar --benchmark --in app.jar
java -jar target/femtojar-0.0.0-cli.jar --benchmark --in app.jar --benchmark-zopfli-iterations 15,30,80
java -jar target/femtojar-0.0.0-cli.jar --benchmark --benchmark-format markdown --in app.jar
```

CLI options:

- `--in <path>`: input JAR path
- `--out <path>`: output JAR path (optional, defaults to in-place rewrite)
- `--deflate`: use deflate level 9 instead of Zopfli
- `--zopfli`: force Zopfli mode
- `--zopfli-iterations <n>`: Zopfli iteration count
- `--bundle-resources`: bundle non-`META-INF/*` resources
- `--benchmark`: run a non-destructive benchmark matrix and print size/time comparisons
- `--benchmark-zopfli-iterations <i1,i2,...>`: comma-separated Zopfli iterations for benchmark mode
- `--benchmark-format <text|markdown>`: benchmark output format (default: `text`)
- `-h`, `--help`: show usage

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
