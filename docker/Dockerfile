# syntax=docker/dockerfile:1.7

# Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. and/or its affiliates.
# Licensed under the Apache License, Version 2.0 (the "License");

FROM eclipse-temurin:17-jdk@sha256:420933bf319e6aff4bec22eff8d2a31c824df5d853432e3dc0f46d6ea8dd5919 AS build

WORKDIR /workspace
COPY . .

# Build the checked-out revision and collect the core SDK's runtime classpath.
RUN ./mvnw -B -ntp -DskipTests -pl core -am package && \
    ./mvnw -B -ntp -pl core dependency:copy-dependencies \
        -DincludeScope=runtime \
        -DoutputDirectory=target/dependency && \
    mkdir -p /image-libs && \
    find core/target -maxdepth 1 -type f \
        -name 'veadk-java-*.jar' \
        ! -name '*-sources.jar' \
        ! -name '*-javadoc.jar' \
        -exec cp '{}' /image-libs/veadk-java.jar \; && \
    test -s /image-libs/veadk-java.jar && \
    cp core/target/dependency/*.jar /image-libs/

FROM eclipse-temurin:17-jre@sha256:a9b54b51aaa867507be76277a98c6b11b99515cac6330891b534d070f607214c

ARG VERSION=dev
ARG REVISION=unknown
ARG CHANNEL=preview

LABEL org.opencontainers.image.title="VeADK Java" \
      org.opencontainers.image.description="Volcengine Agent Development Kit for Java" \
      org.opencontainers.image.source="https://github.com/volcengine/veadk-java" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      io.volcengine.veadk.channel="${CHANNEL}"

WORKDIR /opt/veadk
COPY --from=build /image-libs/ /opt/veadk/lib/

ENV CLASSPATH=/opt/veadk/lib/*

# The image is an SDK base image. Derived images provide their own main class.
CMD ["java", "-version"]
