package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.http.ApiResponse;
import pl.kuba6000.ae2webintegration.core.http.IResponseBody;

class ApiResponseTest {

    @Test
    void boundedSerializationCountsActualUtf8Bytes() {
        TextBody body = new TextBody("é");
        String expected = "{\"text\":\"é\"}";
        int bytes = expected.getBytes(StandardCharsets.UTF_8).length;
        assertEquals(
            expected,
            ApiResponse.bounded(200, body, bytes)
                .json());
        assertThrows(ApiResponse.SizeLimitExceeded.class, () -> ApiResponse.bounded(200, body, bytes - 1));
    }

    @Test
    void compressionIsNegotiatedForEligibleResponsesAndHeadReportsItsRepresentation() throws IOException {
        ApiResponse eligible = ApiResponse.bounded(200, new TextBody("hello hello hello"), 1000);
        Exchange compressed = new Exchange("GET", "br, *;q=0.5");
        eligible.send(compressed);
        assertEquals(
            "Accept-Encoding",
            compressed.getResponseHeaders()
                .getFirst("Vary"));
        assertEquals(
            "gzip",
            compressed.getResponseHeaders()
                .getFirst("Content-Encoding"));
        assertEquals(
            eligible.json(),
            IOUtils.toString(
                new GZIPInputStream(new ByteArrayInputStream(compressed.output.toByteArray())),
                StandardCharsets.UTF_8));
        assertEquals(compressed.output.size(), compressed.length);

        Exchange excluded = new Exchange("GET", "gzip;q=0, *;q=1");
        eligible.send(excluded);
        assertNull(
            excluded.getResponseHeaders()
                .getFirst("Content-Encoding"));
        assertEquals(eligible.json(), new String(excluded.output.toByteArray(), StandardCharsets.UTF_8));

        Exchange head = new Exchange("HEAD", "gzip");
        eligible.send(head);
        assertEquals(
            "gzip",
            head.getResponseHeaders()
                .getFirst("Content-Encoding"));
        assertEquals(
            Integer.toString(compressed.output.size()),
            head.getResponseHeaders()
                .getFirst("Content-Length"));
        assertEquals(0, head.output.size());
        assertEquals(-1, head.length);

        Exchange ordinary = new Exchange("GET", "gzip");
        new ApiResponse(200, "{}").send(ordinary);
        assertNull(
            ordinary.getResponseHeaders()
                .getFirst("Vary"));
        assertNull(
            ordinary.getResponseHeaders()
                .getFirst("Content-Encoding"));
    }

    private static final class TextBody implements IResponseBody {

        private final String text;

        private TextBody(String text) {
            this.text = text;
        }
    }

    private static final class Exchange extends TestGridFixtures.TestExchange {

        private final String method;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private long length;

        private Exchange(String method, String accepted) {
            super("");
            this.method = method;
            getRequestHeaders().set("Accept-Encoding", accepted);
        }

        @Override
        public String getRequestMethod() {
            return method;
        }

        @Override
        public OutputStream getResponseBody() {
            return output;
        }

        @Override
        public void sendResponseHeaders(int status, long responseLength) {
            length = responseLength;
        }
    }
}
