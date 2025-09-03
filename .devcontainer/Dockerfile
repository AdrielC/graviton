# Devcontainer image for Scala (Scala 3.x) with JDK 21, sbt, coursier, and zsh

FROM mcr.microsoft.com/devcontainers/java:1-21-bookworm

ARG USERNAME=vscode

ENV DEBIAN_FRONTEND=noninteractive

# Base utilities
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        curl \
        wget \
        unzip \
        zip \
        ca-certificates \
        git \
        zsh \
    && rm -rf /var/lib/apt/lists/*

# Install coursier (cs) with architecture detection
RUN ARCH=$(uname -m); \
    case "$ARCH" in \
      x86_64) CS_URL="https://github.com/coursier/coursier/releases/latest/download/cs-x86_64-pc-linux.gz" ;; \
      aarch64|arm64) CS_URL="https://github.com/coursier/coursier/releases/latest/download/cs-aarch64-pc-linux.gz" ;; \
      *) echo "Unsupported architecture: $ARCH" && exit 1 ;; \
    esac; \
    curl -fL "$CS_URL" -o /usr/local/bin/cs.gz \
    && gzip -d /usr/local/bin/cs.gz \
    && chmod +x /usr/local/bin/cs \
    && cs version

# Install sbt 1.11.5 via tarball
ENV SBT_VERSION=1.11.5
RUN curl -fsSL https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz -o /tmp/sbt.tgz \
    && tar -xzf /tmp/sbt.tgz -C /usr/local \
    && ln -s /usr/local/sbt/bin/sbt /usr/local/bin/sbt \
    && rm /tmp/sbt.tgz

# Pre-create and own cache directories for the vscode user
RUN mkdir -p /home/${USERNAME}/.sbt /home/${USERNAME}/.ivy2 /home/${USERNAME}/.cache/coursier \
    && chown -R ${USERNAME}:${USERNAME} /home/${USERNAME}/.sbt /home/${USERNAME}/.ivy2 /home/${USERNAME}/.cache

# Prepare coursier environment and install Bloop/Java via cs setup as the vscode user
ENV COURSIER_CACHE=/home/${USERNAME}/.cache/coursier
USER ${USERNAME}
RUN cs setup --yes --jvm 21
USER root

# Keep bash as the Docker RUN shell; the VS Code terminal default is set via devcontainer.json
SHELL ["/bin/bash", "-lc"]


