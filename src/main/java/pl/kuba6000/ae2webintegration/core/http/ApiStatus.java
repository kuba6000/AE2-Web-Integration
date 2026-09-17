package pl.kuba6000.ae2webintegration.core.http;

import java.net.HttpURLConnection;

/** Machine-readable response codes; operation-specific success HTTP statuses remain explicit. */
public enum ApiStatus {

    OK(HttpURLConnection.HTTP_OK),
    INVALID_USER(HttpURLConnection.HTTP_UNAUTHORIZED),
    INVALID_PASSWORD(HttpURLConnection.HTTP_UNAUTHORIZED),
    NOT_ONLINE(HttpURLConnection.HTTP_CONFLICT),
    UNAUTHORIZED(HttpURLConnection.HTTP_UNAUTHORIZED),
    BAD_PARAM(HttpURLConnection.HTTP_BAD_REQUEST),
    INVALID_QUANTITY(HttpURLConnection.HTTP_BAD_REQUEST),
    NO_PERMISSIONS(HttpURLConnection.HTTP_FORBIDDEN),
    CSRF_REJECTED(HttpURLConnection.HTTP_FORBIDDEN),
    GRID_NOT_FOUND(HttpURLConnection.HTTP_NOT_FOUND),
    CPU_NOT_FOUND(HttpURLConnection.HTTP_NOT_FOUND),
    ITEM_NOT_FOUND(HttpURLConnection.HTTP_NOT_FOUND),
    ITEM_IDENTITY_UNKNOWN(HttpURLConnection.HTTP_NOT_FOUND),
    TRACKING_NOT_FOUND(HttpURLConnection.HTTP_NOT_FOUND),
    INVALID_ID(HttpURLConnection.HTTP_NOT_FOUND),
    CPU_NOT_BUSY(HttpURLConnection.HTTP_CONFLICT),
    ALL_CPU_BUSY(HttpURLConnection.HTTP_CONFLICT),
    AMBIGUOUS_ITEM_KEY(HttpURLConnection.HTTP_CONFLICT),
    JOB_NOT_DONE(HttpURLConnection.HTTP_CONFLICT),
    FAIL(HttpURLConnection.HTTP_CONFLICT),
    REQUEST_TOO_LARGE(HttpURLConnection.HTTP_ENTITY_TOO_LARGE),
    UNSUPPORTED_MEDIA_TYPE(HttpURLConnection.HTTP_UNSUPPORTED_TYPE),
    NOT_FOUND(HttpURLConnection.HTTP_NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpURLConnection.HTTP_BAD_METHOD),
    TOO_MANY_REQUESTS(429), // NOPMD - HTTP Too Many Requests; Java 8 has no constant.
    INTERNAL_ERROR(HttpURLConnection.HTTP_INTERNAL_ERROR),
    SERVER_BUSY(HttpURLConnection.HTTP_UNAVAILABLE),
    SERVER_STOPPING(HttpURLConnection.HTTP_UNAVAILABLE),
    TIMEOUT(HttpURLConnection.HTTP_UNAVAILABLE);

    private final int httpStatus;

    ApiStatus(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }

}
