package ru.it_spectrum.ai.jdbc.mcp.config;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class McpStdioConcurrencyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void immediateExecutionKeepsToolResponsesSerialized() throws Exception {
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch fastStarted = new CountDownLatch(1);
        ExecutorService readerThread = Executors.newSingleThreadExecutor();

        try (PipedInputStream serverInput = new PipedInputStream();
             PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
             PipedInputStream clientInput = new PipedInputStream();
             PipedOutputStream serverOutput = new PipedOutputStream(clientInput);
             BufferedWriter requests = new BufferedWriter(new OutputStreamWriter(clientOutput, StandardCharsets.UTF_8));
             BufferedReader replies = new BufferedReader(new InputStreamReader(clientInput, StandardCharsets.UTF_8))) {

            StdioServerTransportProvider transport = new StdioServerTransportProvider(
                    McpJsonDefaults.getMapper(), serverInput, serverOutput);
            McpSchema.Tool slow = McpSchema.Tool.builder("slow", Map.of("type", "object", "properties", Map.of())).build();
            McpSchema.Tool fast = McpSchema.Tool.builder("fast", Map.of("type", "object", "properties", Map.of())).build();
            McpSyncServer server = McpServer.sync(transport)
                    .serverInfo("concurrency-test", "1")
                    .toolCall(slow, (exchange, request) -> {
                        slowStarted.countDown();
                        try {
                            if (!releaseSlow.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("slow tool was not released");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        return McpSchema.CallToolResult.builder().addTextContent("slow").build();
                    })
                    .toolCall(fast, (exchange, request) -> {
                        fastStarted.countDown();
                        return McpSchema.CallToolResult.builder().addTextContent("fast").build();
                    })
                    .immediateExecution(true)
                    .build();

            try {
                send(requests, "{" +
                        "\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"," +
                        "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}," +
                        "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}");
                assertThat(readNext(readerThread, replies)).contains("\"id\":1");
                send(requests, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

                send(requests, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"slow\",\"arguments\":{}}}");
                assertThat(slowStarted.await(3, TimeUnit.SECONDS)).isTrue();

                send(requests, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"fast\",\"arguments\":{}}}");
                assertThat(fastStarted.await(200, TimeUnit.MILLISECONDS)).isFalse();
                releaseSlow.countDown();
                assertThat(readNext(readerThread, replies)).contains("\"id\":2", "slow");
                assertThat(readNext(readerThread, replies)).contains("\"id\":3", "fast");
            } finally {
                releaseSlow.countDown();
                server.close();
            }
        } finally {
            readerThread.shutdownNow();
        }
    }

    // Re-enable when the resolved MCP SDK includes upstream fix 2bb1481 for #686.
    // This uses the SDK's concurrent default, not the production immediateExecution(true) setting.
    @Test
    @Disabled("MCP SDK 2.0.0 drops concurrent stdio responses; re-enable with upstream fix 2bb1481 (#686)")
    void simultaneousToolCompletionsDeliverEveryResponse() throws Exception {
        int calls = 24;
        CountDownLatch allStarted = new CountDownLatch(calls);
        CountDownLatch releaseAll = new CountDownLatch(1);

        try (PipedInputStream serverInput = new PipedInputStream();
             PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
             LineOutputStream serverOutput = new LineOutputStream();
             BufferedWriter requests = new BufferedWriter(new OutputStreamWriter(clientOutput, StandardCharsets.UTF_8))) {

            StdioServerTransportProvider transport = new StdioServerTransportProvider(
                    McpJsonDefaults.getMapper(), serverInput, serverOutput);
            McpSchema.Tool tool = McpSchema.Tool.builder("concurrent", Map.of("type", "object", "properties", Map.of()))
                    .build();
            McpSyncServer server = McpServer.sync(transport)
                    .serverInfo("concurrency-test", "1")
                    .toolCall(tool, (exchange, request) -> {
                        allStarted.countDown();
                        try {
                            if (!releaseAll.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("tool calls were not released");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        return McpSchema.CallToolResult.builder().addTextContent("done").build();
                    })
                    .build();

            try {
                send(requests, "{" +
                        "\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"," +
                        "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}," +
                        "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}");
                assertThat(serverOutput.readLine()).contains("\"id\":1");
                send(requests, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

                for (int id = 2; id < calls + 2; id++) {
                    send(requests, "{\"jsonrpc\":\"2.0\",\"id\":" + id +
                            ",\"method\":\"tools/call\",\"params\":{\"name\":\"concurrent\",\"arguments\":{}}}");
                }
                assertThat(allStarted.await(10, TimeUnit.SECONDS)).isTrue();
                releaseAll.countDown();

                Set<Integer> receivedIds = new HashSet<>();
                for (int i = 0; i < calls; i++) {
                    JsonNode response = JSON.readTree(serverOutput.readLine());
                    assertThat(response.get("error")).isNull();
                    assertThat(response.get("result").toString()).contains("done");
                    assertThat(receivedIds.add(response.get("id").asInt())).isTrue();
                }
                assertThat(receivedIds).containsExactlyInAnyOrderElementsOf(
                        java.util.stream.IntStream.range(2, calls + 2).boxed().toList());
            } finally {
                releaseAll.countDown();
                server.close();
            }
        }
    }

    private static final class LineOutputStream extends OutputStream {
        private final ByteArrayOutputStream currentLine = new ByteArrayOutputStream();
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

        @Override
        public synchronized void write(int value) {
            if (value == '\n') {
                lines.add(currentLine.toString(StandardCharsets.UTF_8));
                currentLine.reset();
            } else {
                currentLine.write(value);
            }
        }

        String readLine() throws InterruptedException {
            String line = lines.poll(3, TimeUnit.SECONDS);
            assertThat(line).as("stdio response").isNotNull();
            return line;
        }
    }

    private static void send(BufferedWriter requests, String message) throws Exception {
        requests.write(message);
        requests.newLine();
        requests.flush();
    }

    private static String readNext(ExecutorService readerThread, BufferedReader replies) throws Exception {
        Future<String> response = readerThread.submit(replies::readLine);
        return response.get(3, TimeUnit.SECONDS);
    }
}
