# JeroMQ

Higher-level Java API for Zeromq (http://zeromq.org).

## Contributing

Contributions welcome! See [CONTRIBUTING.md](CONTRIBUTING.md) for details about the contribution process and useful development tasks.

## Usage

Create a file named `META-INF/services/zmq.api.AProvider` in `src/main/resources/` 
and add a line giving the fully qualified name of a class implementing AProvider.
This class shall have a default constructor with no parameters.

ZMQ will load that class as its first operation, enabling the higher-level API.

And... that's it! (It meaning to actually fully implement the provider)

### Maven

Add it to your Maven project's `pom.xml`:

```xml
    <dependency>
      <groupId>org.zeromq</groupId>
      <artifactId>zeromq-java-api</artifactId>
      <version>0.0.1</version>
    </dependency>

    <!-- for the latest SNAPSHOT -->
    <dependency>
      <groupId>org.zeromq</groupId>
      <artifactId>zeromq-java-api</artifactId>
      <version>0.0.1-SNAPSHOT</version>
    </dependency>

    <!-- If you can't find the latest snapshot -->
    <repositories>
      <repository>
        <id>sonatype-nexus-snapshots</id>
        <url>https://oss.sonatype.org/content/repositories/snapshots</url>
        <releases>
          <enabled>false</enabled>
        </releases>
        <snapshots>
          <enabled>true</enabled>
        </snapshots>
       </repository>
    </repositories>
```

### Ant

To generate an ant build file from `pom.xml`, issue the following command:

```bash
mvn ant:ant
```

## License

All source files are copyright © 2007-2017 contributors as noted in the AUTHORS file.

Free use of this software is granted under the terms of the Mozilla Public License 2.0. For details see the file `LICENSE` included with the JeroMQ distribution.
