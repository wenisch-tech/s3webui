# syntax=docker/dockerfile:1.7

FROM cgr.dev/chainguard/jre:latest
WORKDIR /app

ARG BUILD_DATE
ARG BUILD_VERSION
ARG BUILD_REVISION

LABEL org.opencontainers.image.title="S3 Web UI" \
      org.opencontainers.image.description="Graphical web interface for S3 object storage" \
      org.opencontainers.image.url="https://github.com/wenisch-tech/s3webui" \
      org.opencontainers.image.source="https://github.com/wenisch-tech/s3webui" \
      org.opencontainers.image.authors="Jean-Fabian Wenisch" \
      org.opencontainers.image.licenses="GPL-3.0" \
      org.opencontainers.image.vendor="wenisch.tech" \
      org.opencontainers.image.version="${BUILD_VERSION}" \
      org.opencontainers.image.revision="${BUILD_REVISION}" \
      org.opencontainers.image.created="${BUILD_DATE}"

ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=20.0 -XX:+UseG1GC -Djava.security.egd=file:/dev/urandom"

COPY target/s3webui-*.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
