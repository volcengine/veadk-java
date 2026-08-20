ARG BASE_IMAGE=console.e97-dev07.inspirecloud.io/codex/codex:0.1

FROM ${BASE_IMAGE} AS build

USER root
WORKDIR /opt/veadk-java
RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-17-jdk-headless maven \
    && rm -rf /var/lib/apt/lists/*
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests -Dmaven.javadoc.skip=true install \
    && mvn -q -pl example dependency:copy-dependencies -DoutputDirectory=target/dependency

FROM ${BASE_IMAGE}

USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-17-jre-headless \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/veadk-java
COPY --from=build /opt/veadk-java/core/target/classes core/target/classes
COPY --from=build /opt/veadk-java/example/target/classes example/target/classes
COPY --from=build /opt/veadk-java/example/target/dependency example/target/dependency
COPY entrypoint.sh /app/entrypoint.sh
RUN mkdir -p /opt/application \
    && ln -sf /app/entrypoint.sh /opt/application/run.sh \
    && chmod 755 /app/entrypoint.sh \
    && chown -R codex:codex /opt/veadk-java /app/entrypoint.sh /opt/application

EXPOSE 8000

USER codex
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/list-apps', timeout=4)" || exit 1
ENTRYPOINT ["/opt/application/run.sh"]
