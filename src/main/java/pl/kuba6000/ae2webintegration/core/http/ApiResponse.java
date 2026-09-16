package pl.kuba6000.ae2webintegration.core.http;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;
import com.sun.net.httpserver.HttpExchange;

import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

/** A completed response owns serialized data, never live game objects. */
@Desugar
public record ApiResponse(int httpStatus, @NotNull String json) {

    public static ApiResponse error(ApiStatus status) {
        return of(status.httpStatus(), new ErrorResponse(status, null));
    }

    public static ApiResponse of(int status, Object body) {
        return new ApiResponse(
            status,
            GSONUtils.GSON_BUILDER.create()
                .toJson(body));
    }

    public void send(HttpExchange exchange) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders()
            .set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders()
            .set("Cache-Control", "no-store");
        if (httpStatus == HttpURLConnection.HTTP_UNAUTHORIZED) {
            exchange.getResponseHeaders()
                .set("WWW-Authenticate", "Bearer");
        }
        if (exchange.getRequestMethod()
            .equals("HEAD")) {
            exchange.getResponseHeaders()
                .set("Content-Length", Integer.toString(body.length));
            exchange.sendResponseHeaders(httpStatus, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(httpStatus, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
