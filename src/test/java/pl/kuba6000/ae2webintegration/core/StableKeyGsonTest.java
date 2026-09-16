package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import com.github.bsideup.jabel.Desugar;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

class StableKeyGsonTest {

    private static final String TOKEN = "AAAAAAAAAAAAAAAAAAAAAA";
    private final Gson gson = GSONUtils.GSON_BUILDER.create();

    @Test
    void scalarUsesCanonicalStringAndRoundTrips() {
        StableKey key = StableKey.parse(TOKEN);

        assertEquals('"' + TOKEN + '"', gson.toJson(key));
        assertEquals(key, gson.fromJson('"' + TOKEN + '"', StableKey.class));
    }

    @Test
    void malformedTokensAndNonStringValuesAreCodecErrors() {
        for (String json : Arrays.asList(
            "\"bad\"",
            "\"AAAAAAAAAAAAAAAAAAAAAB\"",
            "\"AAAAAAAAAAAAAAAAAAAAAA==\"",
            "42",
            "true",
            "{}",
            "[]")) {
            assertThrows(JsonParseException.class, () -> gson.fromJson(json, StableKey.class), json);
        }
    }

    @Test
    void typedDtoFieldRoundTripsWithoutExposingIdentityInternals() {
        StableKey key = StableKey.parse(TOKEN);
        String json = "{\"key\":\"" + TOKEN + "\"}";
        assertEquals(JsonParser.parseString(json), gson.toJsonTree(new KeyDto(key)));
        assertEquals(
            key,
            gson.fromJson(json, KeyDto.class)
                .key());
    }

    @Test
    void mapKeysUseCanonicalObjectNamesAndRejectMalformedNames() {
        StableKey key = StableKey.parse(TOKEN);
        Type type = new TypeToken<Map<StableKey, String>>() {}.getType();
        Map<StableKey, String> entries = Collections.singletonMap(key, "entry");
        String json = "{\"" + TOKEN + "\":\"entry\"}";

        assertEquals(JsonParser.parseString(json), gson.toJsonTree(entries, type));
        assertEquals(entries, gson.fromJson(json, type));
        assertThrows(JsonParseException.class, () -> gson.fromJson("{\"bad\":\"entry\"}", type));
        // Existing maps still use ordinary Gson object-key behavior, not complex-key arrays.
        Map<KeyDto, String> unrelated = Collections.singletonMap(new KeyDto(key), "entry");
        assertEquals(JsonParser.parseString("{\"existing-key\":\"entry\"}"), gson.toJsonTree(unrelated));
    }

    @Test
    void nullableKeysRemainNullInScalarsAndFields() {
        assertEquals("null", gson.toJson(null, StableKey.class));
        assertNull(gson.fromJson("null", StableKey.class));
        assertEquals(JsonParser.parseString("{\"key\":null}"), gson.toJsonTree(new KeyDto(null)));
        assertNull(
            gson.fromJson("{\"key\":null}", KeyDto.class)
                .key());
    }

    @Desugar
    private record KeyDto(StableKey key) {

        @Override
        public @NotNull String toString() {
            return "existing-key";
        }
    }
}
