# Example Project Using `femtojar`

This project demonstrates using the `femtojar` Maven plugin to recompress a combined (shaded) runnable JAR in place.

## Build Steps

1. Install the plugin from the parent directory:

```bash
cd ..
mvn install
```

2. Build this project and run recompression in the `package` phase:

```bash
cd example-project
mvn package
```

The plugin is configured in `pom.xml` to run `femtojar:reencode-jars` against:

- `${project.artifactId}.jar`

The combined JAR includes `picocli` and is directly runnable:

```bash
java -jar target/demo-app-optimized.jar --repeats 3000
```