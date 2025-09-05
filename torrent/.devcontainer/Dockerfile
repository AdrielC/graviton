FROM eclipse-temurin:21-jdk-jammy

# Configure DNS servers
RUN echo "nameserver 8.8.8.8" > /etc/resolv.conf && \
    echo "nameserver 8.8.4.4" >> /etc/resolv.conf

# Switch to a different mirror for apt-get
RUN sed -i 's/http:\/\/archive.ubuntu.com\/ubuntu\//http:\/\/mirrors.edge.kernel.org\/ubuntu\//g' /etc/apt/sources.list

# Install only essential packages in minimal way
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    git \
    unzip \
    zsh \
    fonts-powerline \
    openjdk-21-source \
    gnupg \
    gpg-agent \
    ca-certificates \
    lsb-release \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/* \
    && rm -rf /var/cache/apt/archives/* \
    && rm -rf /tmp/*

# Install Docker
RUN curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null \
    && apt-get update \
    && apt-get install -y docker-ce-cli \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/* \
    && rm -rf /var/cache/apt/archives/* \
    && rm -rf /tmp/*

# Set JAVA_HOME and ensure Java 21 is used
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH=$JAVA_HOME/bin:$PATH

# Configure GPG for container environment to suppress errors
RUN mkdir -p /root/.gnupg \
    && chmod 700 /root/.gnupg \
    && echo "no-autostart" > /root/.gnupg/gpg-agent.conf \
    && echo "disable-scdaemon" >> /root/.gnupg/gpg-agent.conf \
    && echo "pinentry-mode loopback" >> /root/.gnupg/gpg-agent.conf

# Link the source files to where Metals expects them
RUN ln -sf /usr/lib/jvm/openjdk-21/lib/src.zip /opt/java/openjdk/lib/src.zip || \
    find /usr -name "src.zip" -exec ln -sf {} /opt/java/openjdk/lib/src.zip \; 2>/dev/null || \
    echo "Source files not found, but that's okay"

# Install oh-my-zsh
RUN sh -c "$(curl -fsSL https://raw.githubusercontent.com/ohmyzsh/ohmyzsh/master/tools/install.sh)" "" --unattended

# Set zsh as default shell
RUN chsh -s /bin/zsh

# Add some useful oh-my-zsh plugins
RUN git clone https://github.com/zsh-users/zsh-autosuggestions ${ZSH_CUSTOM:-~/.oh-my-zsh/custom}/plugins/zsh-autosuggestions \
    && git clone https://github.com/zsh-users/zsh-syntax-highlighting.git ${ZSH_CUSTOM:-~/.oh-my-zsh/custom}/plugins/zsh-syntax-highlighting \
    && git clone https://github.com/zsh-users/zsh-completions ${ZSH_CUSTOM:-~/.oh-my-zsh/custom}/plugins/zsh-completions \
    && git clone https://github.com/zsh-users/zsh-history-substring-search ${ZSH_CUSTOM:-~/.oh-my-zsh/custom}/plugins/zsh-history-substring-search

# Install Coursier
RUN curl -fLo /usr/local/bin/cs https://github.com/coursier/launchers/raw/master/coursier \
    && chmod +x /usr/local/bin/cs

# Install Scala and SBT via Coursier
RUN cs setup --yes \
    && cs install scala:3.7.2 scalac:3.7.2 sbt:1.10.3 \
    && rm -rf /tmp/* \
    && rm -rf /root/.cache/coursier/tmp*

# Configure zsh with oh-my-zsh
# - Ensure PATH is exported before sourcing oh-my-zsh
# - Set fino-time as the theme before oh-my-zsh loads
# - Set a useful plugins list
RUN sed -i '/oh-my-zsh\.sh/i export PATH="/root/.local/share/coursier/bin:$PATH"' ~/.zshrc \
    && sed -i 's/^ZSH_THEME=.*/ZSH_THEME="fino-time"/' ~/.zshrc \
    && sed -i 's/^plugins=.*/plugins=(git zsh-autosuggestions zsh-syntax-highlighting scala sbt docker)/' ~/.zshrc

# Set up environment
ENV PATH="/root/.local/share/coursier/bin:$PATH"
ENV SHELL="/bin/zsh"

# Set working directory
WORKDIR /workspaces/torrent

# Install Scala tools via coursier (this will be done in postCreateCommand to avoid build issues)
# RUN cs setup --yes && cs install scala:3.7.0 scalac:3.7.0 sbt:1.10.3 