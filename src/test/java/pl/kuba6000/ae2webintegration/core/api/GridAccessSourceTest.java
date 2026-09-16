package pl.kuba6000.ae2webintegration.core.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.grid.GridAccessSource;
import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

@SuppressWarnings("PMD.AvoidMagicNumbers") // Arbitrary fixture coordinates.
class GridAccessSourceTest {

    private final UUID anna = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID piotr = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final DimensionalCoords position = new DimensionalCoords("world", 1, 2, 3);

    @Test
    void ownershipOnlyGrantsItsPlayer() {
        GridAccessSource source = new GridAccessSource(
            new PlayerIdentity(anna, "Anna"),
            "Controller",
            position,
            null,
            "owner");
        assertTrue(source.allows(anna));
        assertFalse(source.allows(piotr));
    }

    @Test
    void accessExplanationKeepsItsJsonSchemaAndRoundTripsPermissions() {
        Gson gson = GSONUtils.GSON_BUILDER.create();
        GridAccessSource source = new GridAccessSource(
            new PlayerIdentity(anna, "Anna"),
            "security_terminal",
            position,
            null,
            "security_card");
        String expected = "{\"player\":{\"uuid\":\"00000000-0000-0000-0000-000000000001\",\"name\":\"Anna\"},"
            + "\"kind\":\"security_terminal\","
            + "\"position\":{\"dimid\":\"world\",\"x\":1,\"y\":2,\"z\":3},"
            + "\"side\":null,\"reason\":\"security_card\"}";
        assertEquals(gson.fromJson(expected, JsonObject.class), gson.toJsonTree(source));
        GridAccessSource restored = gson.fromJson(expected, GridAccessSource.class);
        assertTrue(restored.allows(anna));
        assertFalse(restored.allows(piotr));
        assertEquals(position, restored.position());
    }
}
