# Multi-stage build: compile the fat jar with a JDK, run it on a slim JRE.
#
#   docker build -t jdbc-mcp-server .
#   docker run -i --rm -v ~/.jdbc-mcp-server:/data jdbc-mcp-server
#
# /data holds connections.json plus the per-connection catalogs and logs the server writes.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Resolve the Gradle distribution and dependencies first so source edits reuse this layer.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
# A Windows checkout may hand us gradlew with CRLF endings and no executable bit.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew \
    && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src ./src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.source="https://github.com/igorolv/jdbc-mcp-server" \
      org.opencontainers.image.description="Read-only JDBC MCP server for PostgreSQL, Oracle, SQL Server, Firebird, SQLite, and any JDBC database" \
      org.opencontainers.image.licenses="Apache-2.0" \
      io.modelcontextprotocol.server.name="io.github.igorolv/jdbc-mcp-server"

RUN useradd --system --create-home --uid 10001 mcp \
    && mkdir -p /data && chown mcp:mcp /data
USER mcp
WORKDIR /app

COPY --from=build --chown=mcp:mcp /src/build/libs/jdbc-mcp-server.jar ./jdbc-mcp-server.jar

ENV JDBC_MCP_DATA_DIR=/data
VOLUME ["/data"]

# stdio transport: the MCP client talks over stdin/stdout, logs go to stderr and /data/logs.
ENTRYPOINT ["java", "-jar", "/app/jdbc-mcp-server.jar"]
