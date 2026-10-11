package pl.kuba6000.ae2webintegration.core.http;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.Locale;

import org.jetbrains.annotations.NotNull;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.AE2Controller.RequestContext;
import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.auth.AuthService;

/** Authenticated assets from validated server ZIPs, never arbitrary filesystem paths. */
public final class ThemeAssetHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (AuthService.isRateLimited(exchange)) {
            ApiResponse.error(ApiStatus.TOO_MANY_REQUESTS)
                .send(exchange);
            return;
        }
        RequestContext context = AuthService.authenticate(exchange);
        if (context == null) {
            ApiResponse.error(ApiStatus.UNAUTHORIZED)
                .send(exchange);
            return;
        }
        if (!AE2Controller.isCurrentHTTPLifecycle(context.getLifecycleGeneration())) {
            ApiResponse.error(ApiStatus.SERVER_STOPPING)
                .send(exchange);
            return;
        }
        String method = exchange.getRequestMethod();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            exchange.getResponseHeaders()
                .set("Allow", "GET, HEAD");
            ApiResponse.error(ApiStatus.METHOD_NOT_ALLOWED)
                .send(exchange);
            return;
        }
        String path = exchange.getRequestURI()
            .getPath()
            .substring("/themes/".length());
        int separator = path.indexOf('/');
        if (separator < 1) {
            ApiResponse.error(ApiStatus.NOT_FOUND)
                .send(exchange);
            return;
        }
        String asset = path.substring(separator + 1);
        String contentType = contentType(asset);
        byte[] bytes;
        try {
            bytes = CoreEngine.getThemes()
                .read(path.substring(0, separator), asset);
        } catch (IOException failure) {
            ApiResponse.error(ApiStatus.INTERNAL_ERROR)
                .send(exchange);
            return;
        }
        if (bytes == null) {
            ApiResponse.error(ApiStatus.NOT_FOUND)
                .send(exchange);
            return;
        }
        exchange.getResponseHeaders()
            .set("Content-Type", contentType);
        exchange.getResponseHeaders()
            .set("X-Content-Type-Options", "nosniff");
        // Replacing a ZIP at the next startup must not leave stale JS/CSS at the same URL.
        exchange.getResponseHeaders()
            .set("Cache-Control", "no-store");
        exchange.getResponseHeaders()
            .set("Content-Length", Integer.toString(bytes.length));
        if (method.equals("HEAD")) {
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static @NotNull String contentType(String path) {
        String extension = path.substring(path.lastIndexOf('.') + 1)
            .toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "js", "mjs" -> "text/javascript; charset=UTF-8";
            case "css" -> "text/css; charset=UTF-8";
            case "json" -> "application/json; charset=UTF-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "ico" -> "image/x-icon";
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            case "ttf" -> "font/ttf";
            case "otf" -> "font/otf";
            default -> "application/octet-stream";
        };
    }
}
