FROM eclipse-temurin:21-jdk

RUN apt-get update \
  && apt-get install -y --no-install-recommends bash ca-certificates curl \
  && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/quasar
COPY . .
RUN chmod +x ./sbt

# Warm the dependency cache so first start is faster.
RUN ./sbt -batch -no-colors "quasarLegacy/compile"

CMD ["bash", "-lc", "./sbt -batch -no-colors \"quasarLegacy/runMain quasar.legacy.jobs.JobRunnerMain\""]

