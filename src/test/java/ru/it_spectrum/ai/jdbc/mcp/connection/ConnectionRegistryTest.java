package ru.it_spectrum.ai.jdbc.mcp.connection;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcMcpProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.StructureSnapshotProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.UsageProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ConnectionRegistryTest {

    private static ConnectionDefinition definition(String name) {
        return new ConnectionDefinition(name, null,
                new JdbcProperties("jdbc:postgresql://localhost:5432/" + name, "u", "p", null,
                        30, 1000, 500, "strict", 40, 0, 10_000, 5_000, 60_000),
                new JdbcMcpProperties("build/test-connections", name),
                new UsageProperties(false, List.of(), List.of(), false, false, false, 0),
                new StructureSnapshotProperties(List.of(), 300),
                DatabaseKind.POSTGRESQL, null);
    }

    private static ConnectionContext context(ConnectionDefinition definition) {
        return ConnectionContext.ofBeans(definition, Map.of());
    }

    @Test
    void theNameSelectsTheConnection() {
        ConnectionRegistry registry = new ConnectionRegistry(
                List.of(definition("a"), definition("b")), ConnectionRegistryTest::context);

        assertThat(registry.resolve("b").name()).isEqualTo("b");
        assertThat(registry.resolve(" a ").name()).isEqualTo("a");
    }

    @Test
    void aMissingNameListsTheAvailableOnes() {
        ConnectionRegistry registry = new ConnectionRegistry(
                List.of(definition("a"), definition("b")), ConnectionRegistryTest::context);

        for (String requested : new String[]{null, "", "  "}) {
            assertThatThrownBy(() -> registry.resolve(requested))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No connection given")
                    .hasMessageContaining("a, b");
        }
    }

    @Test
    void aSingleConnectionIsStillNamedExplicitly() {
        ConnectionRegistry registry = new ConnectionRegistry(
                List.of(definition("only")), ConnectionRegistryTest::context);

        assertThat(registry.resolve("only").name()).isEqualTo("only");
        assertThatThrownBy(() -> registry.resolve(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only");
    }

    @Test
    void unknownNameListsTheAvailableOnes() {
        ConnectionRegistry registry = new ConnectionRegistry(
                List.of(definition("orders"), definition("billing")),
                ConnectionRegistryTest::context);

        assertThatThrownBy(() -> registry.resolve("orderz"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown connection 'orderz'")
                .hasMessageContaining("orders, billing");
    }

    @Test
    void contextsAreBuiltOnFirstUseAndOnlyForTheConnectionAskedFor() {
        List<String> built = new ArrayList<>();
        ConnectionRegistry registry = new ConnectionRegistry(
                List.of(definition("a"), definition("b")), definition -> {
            built.add(definition.name());
            return context(definition);
        });

        assertThat(built).isEmpty();
        assertThat(registry.isInitialized("a")).isFalse();

        registry.resolve("b");
        registry.resolve("b");

        assertThat(built).containsExactly("b");
        assertThat(registry.isInitialized("b")).isTrue();
        assertThat(registry.isInitialized("a")).isFalse();
    }

    @Test
    void aBrokenConnectionDoesNotAffectTheOthersAndIsRetriedNextTime() {
        AtomicInteger attempts = new AtomicInteger();
        ConnectionRegistry registry = new ConnectionRegistry(
                List.of(definition("good"), definition("broken")), definition -> {
            if ("broken".equals(definition.name())) {
                attempts.incrementAndGet();
                throw new IllegalStateException("database is down");
            }
            return context(definition);
        });

        assertThatThrownBy(() -> registry.resolve("broken")).hasMessage("database is down");
        assertThat(registry.resolve("good").name()).isEqualTo("good");
        assertThatThrownBy(() -> registry.resolve("broken")).hasMessage("database is down");
        assertThat(attempts).hasValue(2);
        assertThat(registry.isInitialized("broken")).isFalse();
    }

    @Test
    void initialisingOneConnectionDoesNotBlockCallsToAnother() {
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ConnectionRegistry registry = new ConnectionRegistry(
                List.of(definition("slow"), definition("fast")), definition -> {
            if ("slow".equals(definition.name())) {
                slowStarted.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return context(definition);
        });

        Thread slow = new Thread(() -> registry.resolve("slow"));
        slow.start();
        try {
            assertThat(slowStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
                    () -> assertThat(registry.resolve("fast").name()).isEqualTo("fast"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            release.countDown();
        }
    }

    @Test
    void concurrentCallersShareOneContext() throws Exception {
        AtomicInteger built = new AtomicInteger();
        ConnectionRegistry registry = new ConnectionRegistry(
                List.of(definition("a")), definition -> {
            built.incrementAndGet();
            return context(definition);
        });

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        var contexts = ConcurrentHashMap.<ConnectionContext>newKeySet();
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    contexts.add(registry.resolve("a"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(built).hasValue(1);
        assertThat(contexts).hasSize(1);
    }

    @Test
    void closingTheRegistryClosesOnlyTheContextsThatWereBuilt() {
        List<String> closed = new ArrayList<>();
        ConnectionRegistry registry = new ConnectionRegistry(
                List.of(definition("a"), definition("b")),
                definition -> ConnectionContext.of(definition, type -> null, () -> true,
                        () -> closed.add(definition.name())));

        registry.resolve("a");
        registry.close();

        assertThat(closed).containsExactly("a");
    }
}
