package ru.it_spectrum.ai.jdbc.mcp.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HttpRequestGuardTest {

    private static HttpRequestGuard loopback() {
        return new HttpRequestGuard(null, List.of(), List.of(), true);
    }

    @Test
    void loopbackHostsPassWithAnyPort() {
        HttpRequestGuard guard = loopback();

        assertThat(guard.check("127.0.0.1:8090", null, null)).isNull();
        assertThat(guard.check("localhost:8090", null, null)).isNull();
        assertThat(guard.check("LOCALHOST", null, null)).isNull();
        assertThat(guard.check("[::1]:8090", null, null)).isNull();
    }

    @Test
    void aForeignHostIsRefusedOnALoopbackServer() {
        HttpRequestGuard.Rejection rejection = loopback().check("attacker.example:8090", null, null);

        assertThat(rejection).isNotNull();
        assertThat(rejection.status()).isEqualTo(403);
        assertThat(loopback().check(null, null, null)).isNotNull();
    }

    @Test
    void listedHostsPassAndStarDisablesTheCheck() {
        HttpRequestGuard listed = new HttpRequestGuard(null, List.of(), List.of("db-gw.corp:8090"), true);
        assertThat(listed.check("DB-GW.corp", null, null)).isNull();
        assertThat(listed.check("other.corp", null, null)).isNotNull();

        HttpRequestGuard any = new HttpRequestGuard(null, List.of(), List.of("*"), true);
        assertThat(any.checksHost()).isFalse();
        assertThat(any.check("anything", null, null)).isNull();
    }

    @Test
    void aServerOnAllInterfacesChecksNoHostUnlessHostsAreListed() {
        assertThat(new HttpRequestGuard(null, List.of(), List.of(), false).checksHost()).isFalse();
        assertThat(new HttpRequestGuard(null, List.of(), List.of("gw"), false).checksHost()).isTrue();
    }

    @Test
    void originsAreCheckedOnlyWhenSent() {
        HttpRequestGuard guard = new HttpRequestGuard(null, List.of("https://tool.corp/"), List.of(), true);

        assertThat(guard.check("localhost", null, null)).isNull();
        assertThat(guard.check("localhost", "http://localhost:5173", null)).isNull();
        assertThat(guard.check("localhost", "http://127.0.0.1", null)).isNull();
        assertThat(guard.check("localhost", "https://tool.corp", null)).isNull();
        assertThat(guard.check("localhost", "https://attacker.example", null).status()).isEqualTo(403);
        assertThat(guard.check("localhost", "null", null)).isNotNull();
    }

    @Test
    void theTokenIsRequiredWhenConfigured() {
        HttpRequestGuard guard = new HttpRequestGuard(" s3cret ", List.of(), List.of(), true);

        assertThat(guard.requiresToken()).isTrue();
        assertThat(guard.check("localhost", null, "Bearer s3cret")).isNull();
        assertThat(guard.check("localhost", null, "bearer s3cret")).isNull();
        assertThat(guard.check("localhost", null, null).status()).isEqualTo(401);
        assertThat(guard.check("localhost", null, "Bearer wrong").status()).isEqualTo(401);
        assertThat(guard.check("localhost", null, "Basic czNjcmV0").status()).isEqualTo(401);
    }

    @Test
    void noTokenMeansNoAuthorizationCheck() {
        assertThat(loopback().requiresToken()).isFalse();
        assertThat(loopback().check("localhost", null, "Bearer anything")).isNull();
    }

    @Test
    void stripPortKeepsBareIpv6() {
        assertThat(HttpRequestGuard.stripPort("host:80")).isEqualTo("host");
        assertThat(HttpRequestGuard.stripPort("[::1]:80")).isEqualTo("[::1]");
        assertThat(HttpRequestGuard.stripPort("::1")).isEqualTo("::1");
        assertThat(HttpRequestGuard.stripPort("host")).isEqualTo("host");
    }

    @Test
    void loopbackAddressDetection() {
        assertThat(HttpTransportConfig.isLoopback("127.0.0.1")).isTrue();
        assertThat(HttpTransportConfig.isLoopback("::1")).isTrue();
        assertThat(HttpTransportConfig.isLoopback("0.0.0.0")).isFalse();
        assertThat(HttpTransportConfig.isLoopback("")).isFalse();
    }
}
