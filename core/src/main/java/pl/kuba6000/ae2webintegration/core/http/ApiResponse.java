package pl.kuba6000.ae2webintegration.core.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;
import com.sun.net.httpserver.HttpExchange;

import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

/** A completed response owns serialized data, never live game objects. */
@Desugar
public record ApiResponse(int httpStatus, @NotNull String json, boolean compressible) {

    public ApiResponse(int httpStatus, @NotNull String json) {
        this(httpStatus, json, false);
    }

    public static ApiResponse error(ApiStatus status) {
        return of(status.httpStatus(), new ErrorResponse(status, null));
    }

    public static ApiResponse of(int status, @NotNull IResponseBody body) {
        return new ApiResponse(
            status,
            GSONUtils.GSON_BUILDER.create()
                .toJson(body));
    }

    /** Serialize eagerly within an uncompressed UTF-8 byte budget and allow negotiated compression. */
    public static ApiResponse bounded(int status, @NotNull IResponseBody body, int maxBytes) {
        if (maxBytes < 0) throw new IllegalArgumentException("Negative response limit");
        LimitedBytes bytes = new LimitedBytes(maxBytes);
        Writer writer = new OutputStreamWriter(bytes, StandardCharsets.UTF_8);
        GSONUtils.GSON_BUILDER.create()
            .toJson(body, writer);
        try {
            writer.flush();
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to serialize response", failure);
        }
        return new ApiResponse(status, new String(bytes.toByteArray(), StandardCharsets.UTF_8), true);
    }

    /** Serialization exceeded the explicitly supplied UTF-8 byte limit. */
    public static final class SizeLimitExceeded extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }

    private static final class LimitedBytes extends ByteArrayOutputStream {

        private final int limit;

        private LimitedBytes(int limit) {
            this.limit = limit;
        }

        @Override
        public synchronized void write(int value) {
            if (count >= limit) throw new SizeLimitExceeded();
            super.write(value);
        }

        @Override
        public synchronized void write(byte @NotNull [] values, int offset, int length) {
            if (length > limit - count) throw new SizeLimitExceeded();
            super.write(values, offset, length);
        }
    }

    public void send(HttpExchange exchange) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        if (compressible) {
            addVary(exchange);
            if (acceptsGzip(
                exchange.getRequestHeaders()
                    .get("Accept-Encoding"))) {
                ByteArrayOutputStream compressed = new ByteArrayOutputStream();
                try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
                    gzip.write(body);
                }
                body = compressed.toByteArray();
                exchange.getResponseHeaders()
                    .set("Content-Encoding", "gzip");
            }
        }
        exchange.getResponseHeaders()
            .set("Content-Type", "application/json; charset=UTF-8");
        if (httpStatus >= HttpURLConnection.HTTP_BAD_REQUEST || exchange.getResponseHeaders()
            .getFirst("Cache-Control") == null) exchange.getResponseHeaders()
                .set("Cache-Control", "no-store");
        if (httpStatus == HttpURLConnection.HTTP_UNAUTHORIZED) {
            exchange.getResponseHeaders()
                .set("WWW-Authenticate", "Bearer");
        }
        exchange.getResponseHeaders()
            .set("Content-Length", Integer.toString(body.length));
        if (exchange.getRequestMethod()
            .equals("HEAD")) {
            exchange.sendResponseHeaders(httpStatus, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(httpStatus, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static void addVary(HttpExchange exchange) {
        List<String> existing = exchange.getResponseHeaders()
            .get("Vary");
        if (existing != null) {
            for (String value : existing) for (String token : value.split(","))
                if ("Accept-Encoding".equalsIgnoreCase(token.trim()) || "*".equals(token.trim())) return;
        }
        exchange.getResponseHeaders()
            .add("Vary", "Accept-Encoding");
    }

    private static boolean acceptsGzip(List<String> headers) {
        if (headers == null) return false;
        Double gzip = null;
        Double wildcard = null;
        for (String header : headers) {
            for (String item : header.split(",")) {
                String[] parts = item.split(";");
                String coding = parts[0].trim();
                if (!"gzip".equalsIgnoreCase(coding) && !"*".equals(coding)) continue;
                double quality = 1;
                for (int i = 1; i < parts.length; i++) {
                    String[] parameter = parts[i].trim()
                        .split("=", 2);
                    if (parameter.length == 2 && "q".equalsIgnoreCase(parameter[0].trim())) {
                        try {
                            quality = Double.parseDouble(parameter[1].trim());
                            if (!Double.isFinite(quality) || quality < 0 || quality > 1) quality = 0;
                        } catch (NumberFormatException invalid) {
                            quality = 0;
                        }
                    }
                }
                if ("gzip".equalsIgnoreCase(coding)) gzip = gzip == null ? quality : Math.min(gzip, quality);
                else wildcard = wildcard == null ? quality : Math.min(wildcard, quality);
            }
        }
        return gzip != null ? gzip > 0 : wildcard != null && wildcard > 0;
    }
}
