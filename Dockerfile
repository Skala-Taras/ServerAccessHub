# --- STAGE 1: Build ---
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /build

# Cache Maven dependencies (faster rebuilds)
COPY pom.xml .
RUN mvn dependency:go-offline -B || true

# Copy source and build
COPY src ./src
RUN mvn package -DskipTests -q

# --- STAGE 2: Runtime ---
FROM eclipse-temurin:21-jdk


# Creating a non-root group and user inside the container
RUN groupadd -g 1000 appgroup && \
    useradd -u 1000 -g appgroup -m -s /bin/bash appuser

# Install terminal tools for web shell
RUN apt-get update && apt-get install -y --no-install-recommends \
    bash \
    bsdutils \
    curl \
    wget \
    vim \
    nano \
    htop \
    net-tools \
    iputils-ping \
    dnsutils \
    procps \
    openssh-client \
    git \
    zip \
    unzip \
    tar \
    gzip \
    && rm -rf /var/lib/apt/lists/* \
    && apt-get clean

# Set working directory
WORKDIR /app

# Copy files
COPY --chown=ubuntu:ubuntu --from=build /build/target/*.jar app.jar
COPY --chown=ubuntu:ubuntu web ./web
COPY --chown=ubuntu:ubuntu keystore.jks .

# Create cloudStorage directory
RUN mkdir -p /app/cloudStorage && chown -R ubuntu:ubuntu /app/cloudStorage

# Set environment variables
ENV LANG=en_US.UTF-8
ENV TERM=xterm-256color

# Switch to non-root user
USER ubuntu

# Expose HTTPS port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD curl -fk https://localhost:8080/ || exit 1

# Start the server
CMD ["java", "-jar", "app.jar"]
