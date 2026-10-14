# Container image

VeADK Java publishes an SDK base image to Volcengine Container Registry:

```text
veadk-cn-beijing.cr.volces.com/veadk/veadk-java:preview
veadk-cn-beijing.cr.volces.com/veadk/veadk-java:latest
veadk-cn-beijing.cr.volces.com/veadk/veadk-java:<version>
```

The image contains the `veadk-java` JAR and all runtime dependencies under
`/opt/veadk/lib`. `CLASSPATH` is preset to `/opt/veadk/lib/*`, so an application
can use it as a base image:

```dockerfile
FROM veadk-cn-beijing.cr.volces.com/veadk/veadk-java:latest

WORKDIR /app
COPY target/my-agent.jar /app/my-agent.jar
ENTRYPOINT ["java", "-cp", "/app/my-agent.jar:/opt/veadk/lib/*", "com.example.MyAgent"]
```

## Release channels

- A push to `main` builds the checked-out revision and publishes `preview`.
- A tag matching `vX.Y.Z` or `X.Y.Z` publishes both that exact tag and `latest`.
- The stable workflow can be started manually only with an existing release tag;
  it verifies that the checked-out commit is exactly the tagged commit and that
  the tag matches the Maven project version.

Both workflows run the Maven test suite, build `linux/amd64`, smoke-test the
local image, and only then log in and push. They run only in the official
`volcengine/veadk-java` repository and use the `VE_CR_USERNAME` and
`VE_CR_PASSWORD` GitHub Actions secrets.
