package pl.kuba6000.ae2webintegration.core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The only parser standing between every endpoint and whatever a client chooses to send, so its job is
 * as much "never throw" as "decode correctly".
 */
@SuppressWarnings("PMD.AvoidMagicNumbers")
class HTTPUtilsTest {

    private static Map<String, String> parameters(String... keysAndValues) {
        Map<String, String> expected = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) expected.put(keysAndValues[i], keysAndValues[i + 1]);
        return expected;
    }

    @SuppressWarnings("UnnecessaryUnicodeEscape") // Keep the decoded code points explicit beside the URI escapes.
    private static Stream<Arguments> wellFormedQueries() {
        return Stream.of(
            Arguments.of("null", null, parameters()),
            Arguments.of("empty", "", parameters()),
            Arguments.of("pairs", "grid=10&id=3", parameters("grid", "10", "id", "3")),
            // ?logout, ?cancel, ?submit and ?track all rely on a bare key mapping to an empty value.
            Arguments.of("bare key", "logout", parameters("logout", "")),
            Arguments.of("plus decodes to space", "q=two+words", parameters("q", "two words")),
            // Escapes rather than literals: the assertion is about byte-level decoding, and it should not
            // depend on how this file happens to be read.
            Arguments.of(
                "percent escapes decode as UTF-8",
                "name=za%C5%BC%C3%B3%C5%82%C4%87",
                parameters("name", "za\u017C\u00F3\u0142\u0107")),
            Arguments.of("empty value is kept", "a=&b=2", parameters("a", "", "b", "2")),
            Arguments.of("empty segments are ignored", "a=1&&b=2&", parameters("a", "1", "b", "2")),
            Arguments.of("duplicate keys keep the last value", "a=1&a=2", parameters("a", "2")),
            Arguments.of(
                "values are decoded once without creating additional parameters",
                "name=cell%26track%3D1&literalPlus=%2B&percent=%2526&space=two+words",
                parameters("name", "cell&track=1", "literalPlus", "+", "percent", "%26", "space", "two words")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wellFormedQueries")
    void wellFormedQueryStringsDecodeIntoTheirParameters(String description, String query,
        Map<String, String> expected) {
        assertEquals(expected, HTTPUtils.parseQueryString(query));
    }

    // --- hostile input must not throw ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedQueries")
    void aMalformedParameterIsSkippedRatherThanThrowing(String description, String query,
        Map<String, String> expected) {
        // URLDecoder raises IllegalArgumentException on %ZZ; letting that escape would drop the whole
        // request, and it is reachable pre-auth from both the query string and a POST body. A broken
        // parameter must not take the rest with it.
        assertEquals(expected, HTTPUtils.parseQueryString(query));
    }

    private static Stream<Arguments> malformedQueries() {
        return Stream.of(
            Arguments.of("malformed escape in a value", "bad=%ZZ&good=1", parameters("good", "1")),
            Arguments.of("trailing percent in a value", "good=1&bad=100%", parameters("good", "1")),
            Arguments.of("malformed escape in a key", "%ZZ=x&good=1", parameters("good", "1")),
            Arguments.of("only malformed input", "%ZZ", parameters()));
    }

    // --- numeric parameters: null rather than an exception ---

    @Test
    void parsesValidNumbers() {
        assertEquals(Long.valueOf(42L), HTTPUtils.parseLong("42"));
        assertEquals(Long.valueOf(-7L), HTTPUtils.parseLong("-7"));
        assertEquals(Long.valueOf(Long.MAX_VALUE), HTTPUtils.parseLong(String.valueOf(Long.MAX_VALUE)));
        assertEquals(Integer.valueOf(42), HTTPUtils.parseInt("42"));
        assertEquals(Integer.valueOf(-7), HTTPUtils.parseInt("-7"));
    }

    @Test
    void rejectsAnythingThatIsNotANumber() {
        // These reach init() from the query string, and init() is called without a try/catch, so throwing
        // here drops the connection instead of answering.
        assertNull(HTTPUtils.parseLong(null));
        assertNull(HTTPUtils.parseLong(""));
        assertNull(HTTPUtils.parseLong(" "));
        assertNull(HTTPUtils.parseLong("abc"));
        assertNull(HTTPUtils.parseLong("1.5"));
        assertNull(HTTPUtils.parseLong("0x10"));
        assertNull(HTTPUtils.parseInt(null));
        assertNull(HTTPUtils.parseInt("abc"));
    }

    @Test
    void rejectsNumbersTooLargeForTheType() {
        assertNull(HTTPUtils.parseLong("99999999999999999999"));
        assertNull(HTTPUtils.parseInt(String.valueOf(Long.MAX_VALUE)));
    }
}
