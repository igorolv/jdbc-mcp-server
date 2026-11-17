package ru.it_spectrum.ai.jdbc.mcp.config;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Admission check of the streamable HTTP transport, applied to every request before the MCP
 * endpoint sees it. The server has no user accounts, so this only keeps out what a local MCP server
 * must keep out:
 *
 * <ul>
 *   <li><b>Host</b> — while the server listens on a loopback address, the {@code Host} header must
 *       name a loopback host (or one of {@code allowedHosts}). A web page that rebinds its own DNS
 *       name to 127.0.0.1 still sends its own name here, so this stops DNS rebinding.</li>
 *   <li><b>Origin</b> — a request a browser sends carries {@code Origin}; it must be a loopback
 *       origin or one of {@code allowedOrigins}. Requests without {@code Origin} (MCP clients, curl)
 *       are not affected.</li>
 *   <li><b>Token</b> — when a token is configured, {@code Authorization: Bearer <token>} is required.</li>
 * </ul>
 *
 * <p>The MCP specification asks local servers to validate {@code Origin} and to bind to localhost;
 * the token is for a server reached over the network.
 */
public final class HttpRequestGuard {

    /** Turns the Host or Origin check off when listed. */
    public static final String ANY = "*";

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final byte[] token;
    private final Set<String> allowedOrigins;
    private final Set<String> allowedHosts;
    private final boolean checkHost;

    /** A refused request: the HTTP status to answer with and why. */
    public record Rejection(int status, String reason) {
    }

    /**
     * @param token          required bearer token; blank for none
     * @param allowedOrigins origins allowed besides loopback ones; {@value #ANY} allows any
     * @param allowedHosts   Host values allowed besides loopback ones; {@value #ANY} disables the check
     * @param loopbackBound  whether the server listens on a loopback address only. When it does not
     *                       and no hosts are listed, the Host check is off: remote clients name the
     *                       machine in many ways the server cannot know.
     */
    public HttpRequestGuard(String token, List<String> allowedOrigins, List<String> allowedHosts,
                            boolean loopbackBound) {
        this.token = token == null || token.isBlank() ? null : token.strip().getBytes(StandardCharsets.UTF_8);
        this.allowedOrigins = normalize(allowedOrigins);
        this.allowedHosts = normalize(allowedHosts).stream()
                .map(HttpRequestGuard::stripPort)
                .collect(Collectors.toUnmodifiableSet());
        this.checkHost = !this.allowedHosts.contains(ANY) && (loopbackBound || !this.allowedHosts.isEmpty());
    }

    public boolean requiresToken() {
        return token != null;
    }

    public boolean checksHost() {
        return checkHost;
    }

    /** {@code null} when the request may proceed. Header values may be {@code null} when absent. */
    public Rejection check(String host, String origin, String authorization) {
        if (checkHost && !hostAllowed(host)) {
            return new Rejection(403, "Host '" + host + "' is not allowed (JDBC_MCP_HTTP_ALLOWED_HOSTS)");
        }
        if (origin != null && !originAllowed(origin)) {
            return new Rejection(403, "Origin '" + origin + "' is not allowed (JDBC_MCP_HTTP_ALLOWED_ORIGINS)");
        }
        if (token != null && !tokenMatches(authorization)) {
            return new Rejection(401, "Missing or wrong bearer token (JDBC_MCP_HTTP_TOKEN)");
        }
        return null;
    }

    private boolean hostAllowed(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String name = stripPort(host.strip().toLowerCase(Locale.ROOT));
        return LOOPBACK_HOSTS.contains(name) || allowedHosts.contains(name);
    }

    private boolean originAllowed(String origin) {
        String value = trimSlash(origin.strip().toLowerCase(Locale.ROOT));
        if (allowedOrigins.contains(ANY) || allowedOrigins.contains(value)) {
            return true;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            return ("http".equals(scheme) || "https".equals(scheme))
                    && host != null && LOOPBACK_HOSTS.contains(host);
        } catch (IllegalArgumentException e) {
            return false; // "null" and other opaque origins
        }
    }

    private boolean tokenMatches(String authorization) {
        if (authorization == null) {
            return false;
        }
        String value = authorization.strip();
        if (value.length() < 7 || !value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        byte[] presented = value.substring(7).strip().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(token, presented);
    }

    private static Set<String> normalize(List<String> values) {
        if (values == null) {
            return Set.of();
        }
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(v -> trimSlash(v.strip().toLowerCase(Locale.ROOT)))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /** {@code host:port} → {@code host}; {@code [::1]:8090} → {@code [::1]}; a bare IPv6 address stays. */
    static String stripPort(String host) {
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            return end < 0 ? host : host.substring(0, end + 1);
        }
        int colon = host.indexOf(':');
        if (colon >= 0 && colon == host.lastIndexOf(':')) {
            return host.substring(0, colon);
        }
        return host;
    }
}
