package com.pellerex.api.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Request enrichment (JV-D7 parity — the .NET LogContextEnrichment / Go LoggingMiddleware
 * equivalent). Takes an inbound {@code X-Correlation-Id} header when it is safe to log (or mints a
 * UUID), echoes it on the response, and puts it in the MDC so EVERY record of the request carries
 * {@code CorrelationId} — in the ECS JSON console/file sinks, the text file pattern, and the
 * records the App Insights agent ships. Also emits one structured line per request completion,
 * except for the health addresses.
 *
 * <p>The MDC key names on the completion line are a CONTRACT with the Pellerex portal's Logs tab:
 * its KQL reads customDimensions.RequestMethod / RequestPath / StatusCode / Elapsed /
 * CorrelationId / Environment / UserAgent / ClientIPAddress / ClientPort / Port / MachineName by
 * exact name (the App Insights agent exports MDC entries as customDimensions). Renaming any of
 * them blanks the matching column in the portal.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** Inbound/outbound header carrying the per-request id. */
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    /** MDC key included on every log record within the request (portal-contract name). */
    public static final String CORRELATION_ID_MDC_KEY = "CorrelationId";

    /**
     * A caller's correlation id is taken as sent only when it matches this. The id comes from
     * outside and is written to the logs and to the reply, so only a run of at most 128 ASCII
     * letters, digits, hyphens, underscores and dots is. Any other is replaced with a new one.
     */
    private static final Pattern ACCEPTABLE_CORRELATION_ID =
            Pattern.compile("[A-Za-z0-9._-]{1,128}");

    /**
     * The health addresses ({@code HealthController}). Kubernetes calls them once a minute and the
     * health test writes its own line, so a request line for them would only repeat it.
     */
    private static final Set<String> HEALTH_PATHS =
            Set.of("/health/startup", "/health/live", "/health/ready");

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    private final String environment;
    private final String serverPort;
    private final String machineName;

    public CorrelationIdFilter(
            @Value("${logging.structured.ecs.service.environment:development}") String environment,
            @Value("${server.port:<port-number>}") String serverPort) {
        this.environment = environment;
        this.serverPort = serverPort;
        this.machineName = resolveMachineName();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || !ACCEPTABLE_CORRELATION_ID.matcher(correlationId).matches()) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        long start = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (!HEALTH_PATHS.contains(request.getRequestURI())) {
                logRequestLine(request, response, start);
            }
            MDC.clear();
        }
    }

    /** The request line, with the names the portal's Logs tab reads. */
    private void logRequestLine(
            HttpServletRequest request, HttpServletResponse response, long start) {
        double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
        String elapsed = String.format(Locale.ROOT, "%.3f", elapsedMs);
        MDC.put("StatusCode", String.valueOf(response.getStatus()));
        MDC.put("RequestMethod", request.getMethod());
        MDC.put("RequestPath", request.getRequestURI());
        MDC.put("Elapsed", elapsed);
        MDC.put("Environment", this.environment);
        MDC.put("UserAgent", valueOrEmpty(request.getHeader("User-Agent")));
        MDC.put("ClientIPAddress", clientIp(request));
        MDC.put("ClientPort", String.valueOf(request.getRemotePort()));
        MDC.put("Port", this.serverPort);
        MDC.put("MachineName", this.machineName);
        log.info(
                "HTTP {} {} responded {} in {} ms",
                request.getMethod(),
                request.getRequestURI(),
                response.getStatus(),
                elapsed);
    }

    /** Client IP, honouring X-Forwarded-For when the request came through the ingress. */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return valueOrEmpty(request.getRemoteAddr());
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String resolveMachineName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "";
        }
    }
}
