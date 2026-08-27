FROM gcr.io/distroless/java21-debian12:nonroot@sha256:7e37784d94dccbf5ccb195c73b295f5ad00cd266512dfbac12eb9c3c28f8077d

WORKDIR /app
COPY --chown=65532:65532 deploy/container/data/ /data/
COPY --chown=65532:65532 modules/server/graviton-server/target/scala-3.8.4/graviton-server-*.jar /app/graviton.jar

ENV GRAVITON_BLOB_BACKEND=fs \
    GRAVITON_FS_ROOT=/data \
    GRAVITON_HTTP_PORT=8080 \
    GRAVITON_GRPC_PORT=9090

EXPOSE 8080 9090
USER 65532:65532
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/graviton.jar"]
