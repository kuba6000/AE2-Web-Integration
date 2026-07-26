package pl.kuba6000.ae2webintegration.core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The only parser standing between every endpoint and whatever a client chooses to send, so its job is
 * as much "never throw" as "decode correctly".
 */
class HTTPUtilsTest {

    @Test
    void nullAndEmptyYieldAnEmptyMap() {
        assertTrue(
            HTTPUtils.parseQueryString(null)
                .isEmpty());
        assertTrue(
            HTTPUtils.parseQueryString("")
                .isEmpty());
    }

    @Test
    void parsesPairs() {
        Map<String, String> parsed = HTTPUtils.parseQueryString("grid=10&id=3");
        assertEquals("10", parsed.get("grid"));
        assertEquals("3", parsed.get("id"));
        assertEquals(2, parsed.size());
    }

    @Test
    void bareKeyMapsToAnEmptyValue() {
        // ?logout, ?cancel, ?submit and ?track all rely on this shape.
        Map<String, String> parsed = HTTPUtils.parseQueryString("logout");
        assertTrue(parsed.containsKey("logout"));
        assertEquals("", parsed.get("logout"));
    }

    @Test
    void plusDecodesToSpace() {
        assertEquals(
            "two words",
            HTTPUtils.parseQueryString("q=two+words")
                .get("q"));
    }

    @Test
    void percentEscapesDecodeAsUtf8() {
        // Escapes rather than literals: the assertion is about byte-level decoding, and it should not
        // depend on how this file happens to be read.
        assertEquals(
            "za\u017C\u00F3\u0142\u0107",
            HTTPUtils.parseQueryString("name=za%C5%BC%C3%B3%C5%82%C4%87")
                .get("name"));
    }

    @Test
    void anEmptyValueIsKept() {
        Map<String, String> parsed = HTTPUtils.parseQueryString("a=&b=2");
        assertEquals("", parsed.get("a"));
        assertEquals("2", parsed.get("b"));
    }

    @Test
    void emptySegmentsAreIgnored() {
        Map<String, String> parsed = HTTPUtils.parseQueryString("a=1&&b=2&");
        assertEquals("1", parsed.get("a"));
        assertEquals("2", parsed.get("b"));
        assertEquals(2, parsed.size());
    }

    @Test
    void duplicateKeysKeepTheLastValue() {
        assertEquals(
            "2",
            HTTPUtils.parseQueryString("a=1&a=2")
                .get("a"));
    }

    // --- hostile input must not throw ---

    @Test
    void aMalformedEscapeIsSkippedRatherThanThrowing() {
        // URLDecoder raises IllegalArgumentException on %ZZ; letting that escape would drop the whole
        // request, and it is reachable pre-auth from both the query string and a POST body.
        Map<String, String> parsed = HTTPUtils.parseQueryString("bad=%ZZ&good=1");
        assertEquals("1", parsed.get("good"), "a broken parameter must not take the rest with it");
        assertFalse(parsed.containsKey("bad"));
    }

    @Test
    void aTrailingPercentIsSkippedRatherThanThrowing() {
        Map<String, String> parsed = HTTPUtils.parseQueryString("good=1&bad=100%");
        assertEquals("1", parsed.get("good"));
        assertFalse(parsed.containsKey("bad"));
    }

    @Test
    void aMalformedKeyIsSkippedRatherThanThrowing() {
        Map<String, String> parsed = HTTPUtils.parseQueryString("%ZZ=x&good=1");
        assertEquals("1", parsed.get("good"));
        assertEquals(1, parsed.size());
    }

    @Test
    void onlyMalformedInputYieldsAnEmptyMapAndNoThrow() {
        assertTrue(
            HTTPUtils.parseQueryString("%ZZ")
                .isEmpty());
    }
}
