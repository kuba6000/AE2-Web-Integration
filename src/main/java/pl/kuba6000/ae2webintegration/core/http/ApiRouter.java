package pl.kuba6000.ae2webintegration.core.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.bsideup.jabel.Desugar;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import pl.kuba6000.ae2webintegration.core.AE2Controller.RequestContext;
import pl.kuba6000.ae2webintegration.core.WebPrincipal;
import pl.kuba6000.ae2webintegration.core.ae2request.IRequest;
import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;

/** Complete method/path dispatch with bounded JSON input and explicit request execution threads. */
public final class ApiRouter implements HttpHandler {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");
    private static final int MAX_BODY_BYTES = 8 * 1024;

    @Desugar
    private record Route(Endpoint endpoint, Constructor<? extends IRequest> factory, String[] segments) {}

    private final List<Route> routes = new ArrayList<>();
    private final Function<HttpExchange, RequestContext> authenticate;
    private final Predicate<HttpExchange> rateLimited;
    private final Consumer<ISyncedRequest> dispatch;

    public ApiRouter(Function<HttpExchange, RequestContext> authenticate, Predicate<HttpExchange> rateLimited,
        Consumer<ISyncedRequest> dispatch) {
        this.authenticate = authenticate;
        this.rateLimited = rateLimited;
        this.dispatch = dispatch;
    }

    public void register(Class<? extends IRequest> type) {
        Endpoint endpoint = type.getAnnotation(Endpoint.class);
        if (endpoint == null) throw new IllegalArgumentException("Missing endpoint declaration: " + type.getName());
        if (!ISyncedRequest.class.isAssignableFrom(type) && !IAsyncRequest.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException("Endpoint must declare an execution thread: " + type.getName());
        }
        String signature = endpoint.path()
            .replaceAll("\\{[^/{}]+}", "{}");
        for (Route route : routes) {
            if (route.endpoint()
                .method() == endpoint.method() && route.endpoint()
                    .path()
                    .replaceAll("\\{[^/{}]+}", "{}")
                    .equals(signature)) {
                throw new IllegalArgumentException("Duplicate route: " + endpoint.method() + " " + endpoint.path());
            }
        }
        try {
            routes.add(
                new Route(
                    endpoint,
                    type.getConstructor(),
                    endpoint.path()
                        .split("/", -1)));
        } catch (NoSuchMethodException exception) {
            throw new IllegalArgumentException("Endpoint requires public constructor", exception);
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        RequestContext arrival = new RequestContext(exchange, WebPrincipal.anonymous());
        exchange.getResponseHeaders()
            .set("Access-Control-Allow-Origin", "*");
        if (rateLimited.test(exchange)) {
            ApiResponse.error(ApiStatus.TOO_MANY_REQUESTS)
                .send(exchange);
            return;
        }
        String[] path = exchange.getRequestURI()
            .getRawPath()
            .split("/", -1);
        String method = exchange.getRequestMethod();
        Route selected = null;
        List<String> allowed = new ArrayList<>();
        for (Route route : routes) {
            if (!matches(route.segments(), path)) continue;
            String declaredMethod = route.endpoint()
                .method()
                .name();
            allowed.add(declaredMethod);
            if (declaredMethod.equals("GET")) allowed.add("HEAD");
            if (declaredMethod.equals(method) || method.equals("HEAD") && declaredMethod.equals("GET"))
                selected = route;
        }
        if (allowed.isEmpty()) {
            ApiResponse.error(ApiStatus.NOT_FOUND)
                .send(exchange);
            return;
        }
        allowed.add("OPTIONS");
        if (method.equals("OPTIONS")) {
            exchange.getResponseHeaders()
                .set("Allow", String.join(", ", allowed));
            exchange.getResponseHeaders()
                .set("Access-Control-Allow-Methods", String.join(", ", allowed));
            // Never allow the same-origin cookie/local mutation marker in a cross-origin preflight.
            exchange.getResponseHeaders()
                .set("Access-Control-Allow-Headers", "Content-Type, Authorization");
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_NO_CONTENT, -1);
            exchange.close();
            return;
        }
        if (selected == null) {
            exchange.getResponseHeaders()
                .set("Allow", String.join(", ", allowed));
            ApiResponse.error(ApiStatus.METHOD_NOT_ALLOWED)
                .send(exchange);
            return;
        }
        RequestContext credentials = authenticate.apply(exchange);
        if (credentials == null && selected.endpoint()
            .authenticated()) {
            ApiResponse.error(ApiStatus.UNAUTHORIZED)
                .send(exchange);
            return;
        }
        boolean unsafe = !method.equals("GET") && !method.equals("HEAD");
        if (unsafe && selected.endpoint()
            .authenticated()
            && !exchange.getRequestHeaders()
                .containsKey("Authorization")
            && !"true".equals(
                exchange.getRequestHeaders()
                    .getFirst("X-AE2-Request"))) {
            ApiResponse.error(ApiStatus.CSRF_REJECTED)
                .send(exchange);
            return;
        }
        ApiResponse response;
        try {
            Map<String, String> parameters = new LinkedHashMap<>();
            for (int index = 0; index < path.length; index++) {
                String segment = selected.segments()[index];
                if (segment.startsWith("{") && segment.endsWith("}")) {
                    try {
                        parameters.put(
                            segment.substring(1, segment.length() - 1),
                            URLDecoder.decode(path[index].replace("+", "%2B"), "UTF-8"));
                    } catch (IllegalArgumentException exception) {
                        throw new InvalidInput(ApiStatus.BAD_PARAM);
                    }
                }
            }
            JsonObject body = readJsonBody(exchange, unsafe);
            RequestContext context = arrival.withInputs(
                credentials == null ? WebPrincipal.anonymous() : credentials.getPrincipal(),
                parameters,
                body);
            IRequest request = selected.factory()
                .newInstance();
            if (request.init(context)) {
                if (request instanceof ISyncedRequest synced) dispatch.accept(synced);
                else((IAsyncRequest) request).handle();
            }
            response = request.getResponse();
        } catch (InvalidInput exception) {
            response = ApiResponse.error(exception.error);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOG.error("API request failed", exception);
            response = ApiResponse.error(ApiStatus.INTERNAL_ERROR);
        }
        response.send(exchange);
    }

    private static boolean matches(String[] template, String[] path) {
        if (template.length != path.length) return false;
        for (int index = 0; index < template.length; index++) {
            if (template[index].startsWith("{") && template[index].endsWith("}")) {
                if (path[index].isEmpty()) return false;
            } else if (!template[index].equals(path[index])) return false;
        }
        return true;
    }

    private static JsonObject readJsonBody(HttpExchange exchange, boolean unsafe) throws IOException, InvalidInput {
        byte[] bytes = new byte[MAX_BODY_BYTES + 1];
        int size = 0;
        try (InputStream input = exchange.getRequestBody()) {
            while (size < bytes.length) {
                int count = input.read(bytes, size, bytes.length - size);
                if (count < 0) break;
                size += count;
            }
        }
        if (size > MAX_BODY_BYTES) throw new InvalidInput(ApiStatus.REQUEST_TOO_LARGE);
        if (size == 0) return null;
        if (!unsafe) throw new InvalidInput(ApiStatus.BAD_PARAM);
        String contentType = exchange.getRequestHeaders()
            .getFirst("Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].trim()
            .equalsIgnoreCase("application/json")) {
            throw new InvalidInput(ApiStatus.UNSUPPORTED_MEDIA_TYPE);
        }
        String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder()
                .decode(ByteBuffer.wrap(bytes, 0, size))
                .toString();
        } catch (CharacterCodingException exception) {
            throw new InvalidInput(ApiStatus.BAD_PARAM);
        }
        // The current input DTOs contain scalar members only. Read strict JSON directly: old Gson's
        // convenience parsers turn leniency on and otherwise accept forms such as {count:7}.
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            JsonObject object = new JsonObject();
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (object.has(name)) throw new InvalidInput(ApiStatus.BAD_PARAM);
                switch (reader.peek()) {
                    case STRING -> object.addProperty(name, reader.nextString());
                    case BOOLEAN -> object.addProperty(name, reader.nextBoolean());
                    case NUMBER -> object.addProperty(name, new BigDecimal(reader.nextString()));
                    case NULL -> {
                        reader.nextNull();
                        object.add(name, JsonNull.INSTANCE);
                    }
                    default -> throw new InvalidInput(ApiStatus.BAD_PARAM);
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new InvalidInput(ApiStatus.BAD_PARAM);
            return object;
        } catch (IOException | IllegalStateException | IllegalArgumentException exception) {
            throw new InvalidInput(ApiStatus.BAD_PARAM);
        }
    }

    private static final class InvalidInput extends Exception {

        private static final long serialVersionUID = 1L;
        private final ApiStatus error;

        private InvalidInput(ApiStatus error) {
            this.error = error;
        }
    }
}
