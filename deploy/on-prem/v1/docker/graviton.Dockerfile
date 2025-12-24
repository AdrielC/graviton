FROM eclipse-temurin:21-jdk

RUN apt-get update \
  && apt-get install -y --no-install-recommends bash ca-certificates curl \
  && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/graviton
COPY . .
RUN chmod +x ./sbt

# Warm the dependency cache so first start is faster.
RUN ./sbt -batch -no-colors "server/compile"

ENV GRAVITON_HTTP_PORT=8081
EXPOSE 8081

CMD ["bash", "-lc", "./sbt -batch -no-colors \"server/runMain graviton.server.Main\""]

