FROM eclipse-temurin:21-jdk

RUN apt-get update \
  && apt-get install -y --no-install-recommends bash ca-certificates curl \
  && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/quasar
COPY . .
RUN chmod +x ./sbt

# Warm the dependency cache so first start is faster.
RUN ./sbt -batch -no-colors "quasarHttp/compile"

ENV QUASAR_HTTP_PORT=8080
EXPOSE 8080

CMD ["bash", "-lc", "./sbt -batch -no-colors \"quasarHttp/runMain quasar.http.QuasarMain\""]

