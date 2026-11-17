package ru.it_spectrum.ai.jdbc.mcp.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Settings and request filter of the streamable HTTP transport ({@code JDBC_MCP_TRANSPORT=http}).
 * Absent in stdio mode, where no web server starts. The MCP endpoint itself is Spring AI's
 * ({@code spring.ai.mcp.server.streamable-http.mcp-endpoint}, {@code /mcp}).
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(HttpTransportConfig.HttpTransportProperties.class)
public class HttpTransportConfig {

    private static final Logger log = LoggerFactory.getLogger(HttpTransportConfig.class);

    /**
     * {@code jdbc-mcp.http.*}.
     *
     * @param token          {@code JDBC_MCP_HTTP_TOKEN}: required bearer token, blank for none
     * @param allowedOrigins {@code JDBC_MCP_HTTP_ALLOWED_ORIGINS}: browser origins allowed besides loopback ones
     * @param allowedHosts   {@code JDBC_MCP_HTTP_ALLOWED_HOSTS}: Host header values allowed besides loopback names
     */
    @ConfigurationProperties(prefix = "jdbc-mcp.http")
    public record HttpTransportProperties(String token, List<String> allowedOrigins, List<String> allowedHosts) {
    }

    @Bean
    HttpRequestGuard httpRequestGuard(HttpTransportProperties properties, Environment environment) {
        String address = environment.getProperty("server.address", "");
        boolean loopback = isLoopback(address);
        HttpRequestGuard guard = new HttpRequestGuard(properties.token(), properties.allowedOrigins(),
                properties.allowedHosts(), loopback);
        log.info("MCP streamable HTTP transport on {}:{}{} (token required: {}, Host check: {})",
                address.isBlank() ? "*" : address, environment.getProperty("server.port", "8080"),
                environment.getProperty("spring.ai.mcp.server.streamable-http.mcp-endpoint", "/mcp"),
                guard.requiresToken(), guard.checksHost());
        if (!loopback && !guard.requiresToken()) {
            log.warn("The HTTP transport listens on a non-loopback address without JDBC_MCP_HTTP_TOKEN: "
                    + "anyone who can reach the port can query every configured database");
        }
        return guard;
    }

    @Bean
    FilterRegistrationBean<GuardFilter> httpRequestGuardFilter(HttpRequestGuard guard) {
        FilterRegistrationBean<GuardFilter> registration = new FilterRegistrationBean<>(new GuardFilter(guard));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) {
            return false; // all interfaces
        }
        try {
            return InetAddress.getByName(address.strip()).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** Answers a refused request itself; the MCP endpoint never sees it. */
    static final class GuardFilter extends OncePerRequestFilter {

        private final HttpRequestGuard guard;

        GuardFilter(HttpRequestGuard guard) {
            this.guard = guard;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            HttpRequestGuard.Rejection rejection = guard.check(request.getHeader("Host"),
                    request.getHeader("Origin"), request.getHeader("Authorization"));
            if (rejection == null) {
                chain.doFilter(request, response);
                return;
            }
            log.warn("Refused HTTP {} {} from {}: {}", request.getMethod(), request.getRequestURI(),
                    request.getRemoteAddr(), rejection.reason());
            response.setStatus(rejection.status());
            if (rejection.status() == HttpServletResponse.SC_UNAUTHORIZED) {
                response.setHeader("WWW-Authenticate", "Bearer");
            }
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"error\":\"" + rejection.reason().replace("\\", "\\\\").replace("\"", "\\\"")
                    + "\",\"kind\":\"rejected\"}");
        }
    }
}
