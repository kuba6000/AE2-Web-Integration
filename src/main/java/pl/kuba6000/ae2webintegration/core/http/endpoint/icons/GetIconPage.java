package pl.kuba6000.ae2webintegration.core.http.endpoint.icons;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;

import org.jetbrains.annotations.NotNull;

import com.sun.net.httpserver.HttpExchange;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.AE2Controller.RequestContext;
import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.http.ApiResponse;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.RequestInputs;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.PathParam;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;

/**
 * Streams an immutable atlas page after normal authentication. If-None-Match accepts a digest ETag.
 * 
 * @pathParam packId Lowercase SHA-256 identifier of the active pack.
 * @pathParam pageDigest Lowercase SHA-256 digest of the PNG bytes.
 * @response 200 Original atlas PNG bytes.
 * @responseMedia 200 image/png
 * @responseHeader 200 ETag Strong digest ETag.
 * @responseHeader 200 Content-Length Encoded page size in bytes.
 * @responseHeader 200 Cache-Control Private immutable browser caching.
 * @response 304 The supplied ETag matches the page.
 * @responseHeader 304 ETag Strong digest ETag.
 * @responseHeader 304 Cache-Control Private immutable browser caching.
 * @response 400 {@link ErrorResponse} Malformed identifier or unexpected request body.
 * @response 401 {@link ErrorResponse} Authentication required, including conditional reads.
 * @response 404 {@link ErrorResponse} Pack or page is not available.
 * @response 405 {@link ErrorResponse} Method not allowed.
 * @response 413 {@link ErrorResponse} Request body too large.
 * @response 429 {@link ErrorResponse} Request rate limit exceeded.
 * @response 500 {@link ErrorResponse} Unexpected failure before streaming began.
 * @response 503 {@link ErrorResponse} Server stopping.
 * @responseExample 400 {"status":"BAD_PARAM","data":null}
 * @responseExample 401 {"status":"UNAUTHORIZED","data":null}
 * @responseExample 404 {"status":"NOT_FOUND","data":null}
 * @responseExample 405 {"status":"METHOD_NOT_ALLOWED","data":null}
 * @responseExample 413 {"status":"REQUEST_TOO_LARGE","data":null}
 * @responseExample 429 {"status":"TOO_MANY_REQUESTS","data":null}
 * @responseExample 500 {"status":"INTERNAL_ERROR","data":null}
 * @responseExample 503 {"status":"SERVER_STOPPING","data":null}
 */
@Endpoint(method = HttpMethod.GET, path = "/api/icon-packs/{packId}/pages/{pageDigest}")
public final class GetIconPage {

    private static final int STREAM_BUFFER_BYTES = 8 * 1024;

    @PathParam("packId")
    @SuppressWarnings("NotNullFieldNotInitialized") // Bound by RequestInputs before serving.
    private @NotNull String packId;

    @PathParam("pageDigest")
    @SuppressWarnings("NotNullFieldNotInitialized") // Bound by RequestInputs before serving.
    private @NotNull String pageDigest;

    private GetIconPage() {}

    /** The router performs authentication and bounded request parsing before this HTTP-thread operation. */
    public static void handle(RequestContext context) throws IOException {
        GetIconPage page = new GetIconPage();
        try {
            RequestInputs.bind(page, context);
        } catch (IllegalArgumentException invalid) {
            ApiResponse.error(ApiStatus.BAD_PARAM)
                .send(context.getExchange());
            return;
        }
        page.send(context);
    }

    private void send(RequestContext context) throws IOException {
        HttpExchange exchange = context.getExchange();
        String digest = pageDigest;
        if (!packId.matches("[0-9a-f]{64}") || !digest.matches("[0-9a-f]{64}")) {
            ApiResponse.error(ApiStatus.BAD_PARAM)
                .send(exchange);
            return;
        }
        if (!AE2Controller.isCurrentHTTPLifecycle(context.getLifecycleGeneration())) {
            ApiResponse.error(ApiStatus.SERVER_STOPPING)
                .send(exchange);
            return;
        }
        IconPack pack = CoreEngine.getIconPack();
        IconPack.Page page = null;
        if (pack != null && pack.packId()
            .equals(packId)) {
            for (IconPack.Page candidate : pack.pages()) if (candidate.digest.equals(digest)) {
                page = candidate;
                break;
            }
        }
        if (page == null) {
            ApiResponse.error(ApiStatus.NOT_FOUND)
                .send(exchange);
            return;
        }
        // Acquire before committing headers so a concurrent stop cannot close the ZIP under this response.
        InputStream stream;
        try {
            stream = pack.openPage(digest);
        } catch (IOException e) {
            ApiResponse.error(ApiStatus.SERVER_STOPPING)
                .send(exchange);
            return;
        }
        try (InputStream input = stream) {
            String etag = "\"" + digest + "\"";
            exchange.getResponseHeaders()
                .set("ETag", etag);
            exchange.getResponseHeaders()
                .set("Cache-Control", "private, max-age=31536000, immutable");
            exchange.getResponseHeaders()
                .set("X-Content-Type-Options", "nosniff");
            String conditional = exchange.getRequestHeaders()
                .getFirst("If-None-Match");
            if (conditional != null) {
                for (String token : conditional.split(",")) {
                    String value = token.trim();
                    if (value.equals("*") || value.equals(etag) || value.equals("W/" + etag)) {
                        exchange.sendResponseHeaders(HttpURLConnection.HTTP_NOT_MODIFIED, -1);
                        exchange.close();
                        return;
                    }
                }
            }
            exchange.getResponseHeaders()
                .set("Content-Type", "image/png");
            exchange.getResponseHeaders()
                .set("Content-Length", Long.toString(page.bytes));
            if (exchange.getRequestMethod()
                .equals("HEAD")) {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, page.bytes);
            try (OutputStream output = exchange.getResponseBody()) {
                byte[] buffer = new byte[STREAM_BUFFER_BYTES];
                long remaining = page.bytes;
                while (remaining > 0) {
                    int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (count < 0) throw new IOException("Truncated atlas page");
                    output.write(buffer, 0, count);
                    remaining -= count;
                }
            }
        } finally {
            exchange.close();
        }
    }
}
