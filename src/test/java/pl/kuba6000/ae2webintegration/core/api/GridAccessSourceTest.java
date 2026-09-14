package pl.kuba6000.ae2webintegration.core.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.UUID;

import org.junit.jupiter.api.Test;

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
}
