package ru.it_spectrum.ai.jdbc.mcp.config;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import ru.it_spectrum.ai.jdbc.mcp.JdbcMcpServerApplication;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server started with {@code JDBC_MCP_TRANSPORT=http}: real tools answer over streamable HTTP,
 * tool calls of one session run in parallel (no {@code immediateExecution}), and the request guard
 * refuses foreign hosts, foreign origins and a missing token.
 */
class HttpTransportTest {

    private static final String TOKEN = "test-token";

    // The server's rolling log under <data-dir>/logs stays open after the context closes, and Windows
    // cannot delete an open file.
    @TempDir(cleanup = CleanupMode.NEVER)
    static Path dataDir;

    private static ConfigurableApplicationContext context;
    private static String baseUrl;

    /** Two calls meet at the barrier only when the server runs them at the same time. */
    @Component
    static class RendezvousTool {
        private final CyclicBarrier barrier = new CyclicBarrier(2);

        @McpTool(name = "testRendezvous", description = "Test only: waits for a second concurrent call.")
        public String testRendezvous() throws InterruptedException {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                return "together";
            } catch (TimeoutException | BrokenBarrierException e) {
                barrier.reset();
                return "alone";
            }
        }
    }

    @BeforeAll
    static void startServer() throws Exception {
        Path database = dataDir.resolve("app.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.execute("create table orders (id integer primary key, status text)");
        }
        Path connections = dataDir.resolve("connections.json");
        Files.writeString(connections, """
                {"connections": {"app": {"url": "jdbc:sqlite:%s", "description": "HTTP transport test"}}}
                """.formatted(database.toAbsolutePath().toString().replace('\\', '/')));

        context = new SpringApplicationBuilder(JdbcMcpServerApplication.class, RendezvousTool.class)
                .run("--jdbc-mcp.transport=http",
                        "--server.port=0",
                        "--jdbc-mcp.data-dir=" + dataDir.toAbsolutePath(),
                        "--jdbc-mcp.connections-file=" + connections.toAbsolutePath(),
                        "--jdbc-mcp.http.token=" + TOKEN);
        baseUrl = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
    }

    @AfterAll
    static void stopServer() {
        if (context != null) {
            context.close();
        }
    }

    private static McpSyncClient client() {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint("/mcp")
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + TOKEN))
                .build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build();
        client.initialize();
        return client;
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }

    @Test
    void realToolsAnswerOverHttp() {
        try (McpSyncClient client = client()) {
            McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder()
                    .name("listConnections").arguments(Map.of()).build());

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(text(result)).contains("\"app\"", "HTTP transport test");

            McpSchema.CallToolResult tables = client.callTool(McpSchema.CallToolRequest.builder()
                    .name("listTables").arguments(Map.of("connection", "app")).build());
            assertThat(text(tables)).contains("orders");
        }
    }

    @Test
    void toolCallsOfOneSessionRunInParallel() throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try (McpSyncClient client = client()) {
            McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                    .name("testRendezvous").arguments(Map.of()).build();
            List<Future<McpSchema.CallToolResult>> calls = List.of(
                    callers.submit(() -> client.callTool(request)),
                    callers.submit(() -> client.callTool(request)));

            for (Future<McpSchema.CallToolResult> call : calls) {
                assertThat(text(call.get(20, TimeUnit.SECONDS))).isEqualTo("together");
            }
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void aMissingTokenIsRefused() throws Exception {
        HttpResponse<String> response = post(HttpRequest.newBuilder());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
    }

    @Test
    void aForeignOriginIsRefused() throws Exception {
        HttpResponse<String> response = post(HttpRequest.newBuilder()
                .header("Authorization", "Bearer " + TOKEN)
                .header("Origin", "https://attacker.example"));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("rejected");
    }

    @Test
    void aForeignHostIsRefused() throws Exception {
        // java.net.http does not let a caller set Host, so this one is written by hand.
        URI uri = URI.create(baseUrl);
        try (Socket socket = new Socket(uri.getHost(), uri.getPort())) {
            OutputStream out = socket.getOutputStream();
            out.write(("POST /mcp HTTP/1.1\r\nHost: attacker.example\r\nAuthorization: Bearer " + TOKEN
                    + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String statusLine = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                    StandardCharsets.US_ASCII)).readLine();

            assertThat(statusLine).contains(" 403");
        }
    }

    private static HttpResponse<String> post(HttpRequest.Builder builder) throws Exception {
        HttpRequest request = builder.uri(URI.create(baseUrl + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
