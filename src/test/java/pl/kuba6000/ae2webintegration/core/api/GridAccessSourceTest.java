package pl.kuba6000.ae2webintegration.core.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
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
        GridAccessSource source = GridAccessSource
            .forPlayer(new PlayerIdentity(anna, "Anna"), "Controller", position, null, "owner");
        assertTrue(source.allows(anna));
        assertFalse(source.allows(piotr));
    }

    @Test
    void defaultGrantHonorsNamedExceptions() {
        GridAccessSource source = GridAccessSource
            .forEveryone("Security Terminal", position, "permissions", Collections.singleton(piotr));
        assertTrue(source.allows(anna));
        assertFalse(source.allows(piotr));
    }

    @Test
    void grantKeepsAnImmutableCopyOfItsExceptions() {
        Set<UUID> exclusions = new HashSet<>(Collections.singleton(piotr));
        GridAccessSource source = GridAccessSource
            .forEveryone("security_terminal", position, "security_default", exclusions);
        exclusions.clear();
        assertFalse(source.allows(piotr));
        assertTrue(source.allows(anna));
        assertThrows(
            UnsupportedOperationException.class,
            () -> source.excludedPlayers()
                .clear());
    }

    @Test
    void accessExplanationKeepsItsJsonSchemaAndRoundTripsPermissions() {
        Gson gson = GSONUtils.GSON_BUILDER.create();
        GridAccessSource source = GridAccessSource
            .forEveryone("security_terminal", position, "security_default", Collections.singleton(piotr));
        String expected = "{\"player\":null,\"kind\":\"security_terminal\","
            + "\"position\":{\"dimid\":\"world\",\"x\":1,\"y\":2,\"z\":3},"
            + "\"side\":null,\"reason\":\"security_default\","
            + "\"excludedPlayers\":[\"00000000-0000-0000-0000-000000000002\"]}";
        assertEquals(gson.fromJson(expected, JsonObject.class), gson.toJsonTree(source));
        GridAccessSource restored = gson.fromJson(expected, GridAccessSource.class);
        assertTrue(restored.allows(anna));
        assertFalse(restored.allows(piotr));
        assertEquals(position, restored.position());
    }
}
