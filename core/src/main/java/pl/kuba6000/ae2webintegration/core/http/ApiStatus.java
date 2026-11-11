package pl.kuba6000.ae2webintegration.core.http;

import static java.net.HttpURLConnection.HTTP_BAD_METHOD;
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_CONFLICT;
import static java.net.HttpURLConnection.HTTP_ENTITY_TOO_LARGE;
import static java.net.HttpURLConnection.HTTP_FORBIDDEN;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;
import static java.net.HttpURLConnection.HTTP_UNSUPPORTED_TYPE;

/** Machine-readable response codes; operation-specific success HTTP statuses remain explicit. */
public enum ApiStatus {

    OK(HTTP_OK),
    INVALID_USER(HTTP_UNAUTHORIZED),
    INVALID_PASSWORD(HTTP_UNAUTHORIZED),
    NOT_ONLINE(HTTP_CONFLICT),
    UNAUTHORIZED(HTTP_UNAUTHORIZED),
    BAD_PARAM(HTTP_BAD_REQUEST),
    INVALID_QUANTITY(HTTP_BAD_REQUEST),
    NO_PERMISSIONS(HTTP_FORBIDDEN),
    SERVER_SETTINGS_DISABLED(HTTP_FORBIDDEN),
    CSRF_REJECTED(HTTP_FORBIDDEN),
    GRID_NOT_FOUND(HTTP_NOT_FOUND),
    CPU_NOT_FOUND(HTTP_NOT_FOUND),
    ITEM_NOT_FOUND(HTTP_NOT_FOUND),
    ITEM_IDENTITY_UNKNOWN(HTTP_NOT_FOUND),
    TRACKING_NOT_FOUND(HTTP_NOT_FOUND),
    INVALID_ID(HTTP_NOT_FOUND),
    CPU_PAUSE_UNSUPPORTED(HTTP_CONFLICT),
    CPU_NOT_BUSY(HTTP_CONFLICT),
    GROUP_NAME_TAKEN(HTTP_CONFLICT),
    GROUP_LIMIT_REACHED(HTTP_CONFLICT),
    AMBIGUOUS_ITEM_KEY(HTTP_CONFLICT),
    JOB_NOT_DONE(HTTP_CONFLICT),
    FAIL(HTTP_CONFLICT),
    UPDATE_CHANGED(HTTP_CONFLICT),
    CONFIG_CHANGED(HTTP_CONFLICT),
    REQUEST_TOO_LARGE(HTTP_ENTITY_TOO_LARGE),
    UNSUPPORTED_MEDIA_TYPE(HTTP_UNSUPPORTED_TYPE),
    NOT_FOUND(HTTP_NOT_FOUND),
    METHOD_NOT_ALLOWED(HTTP_BAD_METHOD),
    TOO_MANY_REQUESTS(429), // NOPMD - HTTP Too Many Requests; Java 8 has no constant.
    INTERNAL_ERROR(HTTP_INTERNAL_ERROR),
    SERVER_BUSY(HTTP_UNAVAILABLE),
    SERVER_STOPPING(HTTP_UNAVAILABLE),
    TIMEOUT(HTTP_UNAVAILABLE);

    private final int httpStatus;

    ApiStatus(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }

}
