package pl.kuba6000.ae2webintegration.core.http;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.common.io.BaseEncoding;
import com.sun.net.httpserver.HttpExchange;

/**
 * Responses for static browser assets that the browser may store but must revalidate on every use.
 * <p>
 * The strong ETag is the SHA-256 of the exact response bytes, so a replaced file never matches the
 * validator of the file it replaced, while an unchanged file costs only a body-less 304.
 */
public final class StaticAssetResponse {

    private static final BaseEncoding HEX = BaseEncoding.base16()
        .lowerCase();

    private StaticAssetResponse() {}

    /** Returns the strong validator of {@code bytes}. */
    public static @NotNull String etag(byte @NotNull [] bytes) {
        try {
            return "\"" + HEX.encode(
                MessageDigest.getInstance("SHA-256")
                    .digest(bytes))
                + "\"";
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Returns whether an If-None-Match header value (a list, {@code *} or weak validators) matches {@code etag}. */
    public static boolean matches(@Nullable String ifNoneMatch, @NotNull String etag) {
        if (ifNoneMatch == null) return false;
        for (String token : ifNoneMatch.split(",")) {
            String value = token.trim();
            if (value.equals("*") || value.equals(etag) || value.equals("W/" + etag)) return true;
        }
        return false;
    }

    /** Sends {@code bytes}, or a 304 when the request already holds them; HEAD receives headers only. */
    public static void send(@NotNull HttpExchange exchange, @NotNull String contentType, byte @NotNull [] bytes)
        throws IOException {
        String etag = etag(bytes);
        exchange.getResponseHeaders()
            .set("ETag", etag);
        exchange.getResponseHeaders()
            .set("Cache-Control", "no-cache");
        exchange.getResponseHeaders()
            .set("X-Content-Type-Options", "nosniff");
        if (matches(
            exchange.getRequestHeaders()
                .getFirst("If-None-Match"),
            etag)) {
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_NOT_MODIFIED, -1);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders()
            .set("Content-Type", contentType);
        sendBytes(exchange, HttpURLConnection.HTTP_OK, bytes);
    }

    /** Writes a complete in-memory body; HEAD receives the headers and {@code Content-Length} only. */
    static void sendBytes(@NotNull HttpExchange exchange, int status, byte @NotNull [] bytes) throws IOException {
        if (exchange.getRequestMethod()
            .equals("HEAD")) {
            exchange.getResponseHeaders()
                .set("Content-Length", Integer.toString(bytes.length));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
