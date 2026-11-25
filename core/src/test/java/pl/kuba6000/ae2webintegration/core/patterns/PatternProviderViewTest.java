package pl.kuba6000.ae2webintegration.core.patterns;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

class PatternProviderViewTest {

    @Test
    void distinguishesAnEmptyPhysicalSlotFromAnInvalidEncodedPatternOnTheWire() {
        PatternProvider.Header header = new PatternProvider.Header(
            StableKey.random(),
            "Furnace",
            new DimensionalCoords("overworld", 1, 2, 3),
            null,
            null,
            true,
            true,
            9,
            null,
            0);
        PatternProvider provider = new PatternProvider(
            header,
            Arrays.asList(
                new PatternProvider.Slot(PatternProvider.Kind.EMPTY, emptyList(), emptyList()),
                new PatternProvider.Slot(PatternProvider.Kind.INVALID, emptyList(), emptyList())));
        JsonArray slots = GSONUtils.GSON.toJsonTree(PatternProviderView.rows(singletonList(provider), null))
            .getAsJsonArray()
            .get(0)
            .getAsJsonObject()
            .getAsJsonArray("slots");
        JsonObject empty = slots.get(0)
            .getAsJsonObject();
        assertEquals(
            "empty",
            empty.get("kind")
                .getAsString());
        JsonObject encoded = slots.get(1)
            .getAsJsonObject();
        assertEquals(
            "pattern",
            encoded.get("kind")
                .getAsString());
        JsonObject pattern = encoded.getAsJsonObject("pattern");
        assertEquals(
            "invalid",
            pattern.get("kind")
                .getAsString());
        assertEquals(
            0,
            pattern.getAsJsonArray("inputs")
                .size());
        assertEquals(
            0,
            pattern.getAsJsonArray("outputs")
                .size());
    }
}
