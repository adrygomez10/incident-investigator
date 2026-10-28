FROM node:22.21.0-bookworm-slim AS mcp
WORKDIR /opt/sentry
COPY mcp-runtime/package*.json ./
RUN npm ci --omit=dev --ignore-scripts
FROM maven:3.9.12-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN mvn -B -Dmaven.test.skip=true package
FROM debian:bookworm-slim AS github
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/github
# Fixed upstream release and verified release asset; amd64 image for the OVH demo.
RUN curl -fsSLo server.tar.gz https://github.com/github/github-mcp-server/releases/download/v2.0.3/github-mcp-server_Linux_x86_64.tar.gz \
 && echo 'f797c20fe02a755865d0e2d46184ab47e31bf1619b0855b8d89c531e45a651c2  server.tar.gz' | sha256sum -c - \
 && tar -xzf server.tar.gz && rm server.tar.gz
FROM eclipse-temurin:25-jre
RUN useradd --uid 10001 --create-home app && mkdir -p /app/corpus && chown -R app:app /app
WORKDIR /app
COPY --from=mcp /usr/local/bin/node /usr/local/bin/node
COPY --from=mcp /opt/sentry /opt/sentry
COPY --from=github /opt/github/github-mcp-server /usr/local/bin/github-mcp-server
COPY --from=build /build/target/incident-investigator-0.1.0-SNAPSHOT.jar app.jar
ENV SENTRY_MCP_SCRIPT=/opt/sentry/node_modules/@sentry/mcp-server/dist/index.js
USER 10001
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
