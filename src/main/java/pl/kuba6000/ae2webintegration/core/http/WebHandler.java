package pl.kuba6000.ae2webintegration.core.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.Nullable;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import pl.kuba6000.ae2webintegration.core.AE2Controller.RequestContext;
import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.WebPrincipal;
import pl.kuba6000.ae2webintegration.core.auth.AuthService;
import pl.kuba6000.ae2webintegration.core.auth.AuthService.LoginResult;
import pl.kuba6000.ae2webintegration.core.auth.AuthService.RegistrationResult;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.utils.HTTPUtils;

/** Serves browser pages and adapts shared authentication operations to forms, cookies and redirects. */
public final class WebHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (AuthService.isRateLimited(exchange)) {
            exchange.getResponseHeaders()
                .set("Content-Type", "text/plain");
            sendText(exchange, 429, "Too Many Requests"); // NOPMD - HTTP Too Many Requests.
            return;
        }
        String path = exchange.getRequestURI()
            .getPath();
        if (path.equals("/favicon.ico")) {
            exchange.getResponseHeaders()
                .set("Content-Type", "image/x-icon");
            try (InputStream input = WebHandler.class.getResourceAsStream("/assets/favicon.ico")) {
                if (input == null) return;
                sendBytes(exchange, HttpURLConnection.HTTP_OK, IOUtils.toByteArray(input));
            }
            return;
        }
        if (!path.equals("/") && !path.isEmpty()
            && !path.equals("/index.php")
            && !path.equals("/index.html")
            && !path.equals("/index.htm")
            && !path.equals("/index.asp")
            && !path.equals("/index.aspx")
            && !path.equals("/index.jsp")) {
            sendText(exchange, HttpURLConnection.HTTP_NOT_FOUND, "<h1>Invalid url! (ERROR 404)</h1>");
            return;
        }

        RequestContext arrival = new RequestContext(exchange, WebPrincipal.anonymous());
        RequestContext authenticated = AuthService.authenticate(exchange);
        if (authenticated == null && !exchange.getRequestHeaders()
            .containsKey("Authorization")) {
            String cookieToken = AuthService.extractToken(exchange);
            if (cookieToken != null) {
                exchange.getResponseHeaders()
                    .add("Set-Cookie", sessionCookie(cookieToken, -1));
            } else if (exchange.getRequestMethod()
                .equals("POST") && handleForm(exchange, arrival.getLifecycleGeneration())) {
                    return;
                }
        }
        renderPage(exchange, authenticated);
    }

    /** Returns whether the form produced its own response instead of rendering the login page. */
    private static boolean handleForm(HttpExchange exchange, long generation) throws IOException {
        if (!isSameOriginBrowserPost(exchange)) {
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_FORBIDDEN, -1);
            return true;
        }
        // An oversized or incomplete form has no usable credentials and renders the login page.
        Map<String, String> input = HTTPUtils.parseQueryString(readBody(exchange));
        if (input.containsKey("register") && input.containsKey("password")) {
            RegistrationResult result = AuthService.register(generation, input.get("register"), input.get("password"));
            switch (result.status()) {
                case SERVER_BUSY, SERVER_STOPPING, TIMEOUT, INTERNAL_ERROR -> sendText(
                    exchange,
                    HttpURLConnection.HTTP_UNAVAILABLE,
                    result.status()
                        .name());
                case OK -> redirect(exchange, "?confirmregistration&token=" + result.token());
                default -> redirect(
                    exchange,
                    "?" + result.status()
                        .name());
            }
            return true;
        }
        if (input.containsKey("username") && input.containsKey("password")) {
            LoginResult result = AuthService
                .login(generation, input.get("username"), input.get("password"), input.containsKey("remember"));
            if (result.status() == ApiStatus.SERVER_STOPPING) {
                sendText(
                    exchange,
                    HttpURLConnection.HTTP_UNAVAILABLE,
                    result.status()
                        .name());
            } else if (result.status() != ApiStatus.OK) {
                redirect(
                    exchange,
                    "?" + result.status()
                        .name());
            } else {
                exchange.getResponseHeaders()
                    .add("Set-Cookie", sessionCookie(result.token(), result.validForSeconds()));
                redirect(exchange, ".");
            }
            return true;
        }
        return false;
    }

    private static void renderPage(HttpExchange exchange, @Nullable RequestContext context) throws IOException {
        String site = context == null ? "/assets/login.html" : "/assets/webpage.html";
        String response;
        try (InputStream input = WebHandler.class.getResourceAsStream(site)) {
            if (input == null) return;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                response = reader.lines()
                    .collect(Collectors.joining(System.lineSeparator()));
            }
        }
        response = response
            .replace("_REPLACE_ME_IS_PUBLIC_MODE", Config.INSTANCE.general.publicMode ? "true" : "false");
        response = response.replace(
            "_REPLACE_ME_VERSION_OUTDATED",
            Config.INSTANCE.general.checkForUpdates && CoreEngine.getAvailableUpdate() != null ? "true" : "false");
        if (context != null) {
            response = response.replace(
                "_REPLACE_ME_USERNAME",
                context.getPrincipal()
                    .getUsername());
            response = response.replace("_REPLACE_ME_IS_ADMIN", context.isAdmin() ? "true" : "false");
        }
        exchange.getResponseHeaders()
            .set("Content-Type", "text/html; charset=UTF-8");
        sendBytes(exchange, HttpURLConnection.HTTP_OK, response.getBytes(StandardCharsets.UTF_8));
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders()
            .add("Location", location);
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_MOVED_TEMP, -1);
    }

    private static void sendText(HttpExchange exchange, int status, String text) throws IOException {
        sendBytes(exchange, status, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static final int MAX_BODY_BYTES = 8 * 1024;

    /**
     * Reads a request body the way an unauthenticated boundary has to: bounded, explicitly UTF-8, and
     * without throwing on anything a client might send. An empty body is a legitimate input and yields an
     * empty string rather than an exception.
     *
     * @return the decoded body, or {@code null} when it is larger than {@link #MAX_BODY_BYTES}.
     */
    private static String readBody(HttpExchange t) throws IOException {
        try (InputStream in = t.getRequestBody()) {
            // One byte past the limit is enough to detect oversize without buffering the rest.
            byte[] buffer = new byte[MAX_BODY_BYTES + 1];
            int read = 0;
            while (read < buffer.length) {
                int count = in.read(buffer, read, buffer.length - read);
                if (count < 0) {
                    break;
                }
                read += count;
            }
            if (read > MAX_BODY_BYTES) {
                return null;
            }
            return new String(buffer, 0, read, StandardCharsets.UTF_8);
        }
    }

    /**
     * Lax, which is also what browsers apply to a cookie with no SameSite at all since Chrome 80 - so
     * stating it changes little today beyond covering older browsers. Strict would additionally block a
     * top-level navigation from another site, but it costs a login screen whenever someone follows a link
     * here. Migrated state-changing API routes must also enforce a CSRF policy; changing the HTTP
     * method alone does not provide that protection.
     * <p>
     * Deliberately no Secure attribute: the server speaks plain HTTP, and the cookie would then never be
     * sent at all.
     */
    private static String sessionCookie(String token, long maxAgeSeconds) {
        return "authenticationToken=" + token + "; Max-Age=" + maxAgeSeconds + "; HttpOnly; SameSite=Lax";
    }

    private static boolean isSameOriginBrowserPost(HttpExchange exchange) {
        String site = exchange.getRequestHeaders()
            .getFirst("Sec-Fetch-Site");
        if (site != null) return site.equals("same-origin") || site.equals("none");
        String origin = exchange.getRequestHeaders()
            .getFirst("Origin");
        if (origin == null) return true; // Non-browser clients and older browsers without Fetch Metadata.
        try {
            String scheme = "http";
            if (AuthService.isTrustedProxy(exchange)) {
                List<String> forwarded = exchange.getRequestHeaders()
                    .get("X-Forwarded-Proto");
                if (forwarded != null) {
                    // The trusted proxy must overwrite this header with the external protocol.
                    if (forwarded.size() != 1) return false;
                    scheme = forwarded.get(0)
                        .trim();
                    if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) return false;
                }
            }
            String host = exchange.getRequestHeaders()
                .getFirst("Host");
            if (host == null) return false;
            URI source = URI.create(origin);
            URI target = URI.create(scheme + "://" + host);
            int defaultPort = "https".equalsIgnoreCase(scheme) ? 443 : 80;
            int sourcePort = source.getPort() < 0 ? defaultPort : source.getPort();
            int targetPort = target.getPort() < 0 ? defaultPort : target.getPort();
            return scheme.equalsIgnoreCase(source.getScheme()) && source.getHost() != null
                && source.getRawUserInfo() == null
                && source.getRawPath()
                    .isEmpty()
                && source.getRawQuery() == null
                && source.getRawFragment() == null
                && target.getRawUserInfo() == null
                && target.getRawPath()
                    .isEmpty()
                && target.getRawQuery() == null
                && target.getRawFragment() == null
                && source.getHost()
                    .equalsIgnoreCase(target.getHost())
                && sourcePort == targetPort;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

}
