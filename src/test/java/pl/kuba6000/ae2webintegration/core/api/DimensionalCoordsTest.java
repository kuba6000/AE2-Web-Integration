package pl.kuba6000.ae2webintegration.core.api;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DimensionalCoordsTest {

    @Test
    void serializesStringDimensionIdWithoutHashingIt() {
        DimensionalCoords coords = new DimensionalCoords("minecraft:overworld", 1, 2, 3);

        String json = new Gson().toJson(coords);

        assertTrue(json.contains("\"dimid\":\"minecraft:overworld\""), json);
        assertFalse(json.contains(String.valueOf("minecraft:overworld".hashCode())), json);
    }

    @Test
    void serializesLegacyNumericDimensionIdAsText() {
        DimensionalCoords coords = new DimensionalCoords(0, 1, 2, 3);

        String json = new Gson().toJson(coords);

        assertTrue(json.contains("\"dimid\":\"0\""), json);
    }

    @Test
    void equalityUsesDimensionIdAndCoordinates() {
        DimensionalCoords first = new DimensionalCoords("minecraft:overworld", 1, 2, 3);
        DimensionalCoords same = new DimensionalCoords("minecraft:overworld", 1, 2, 3);
        DimensionalCoords differentDimension = new DimensionalCoords("minecraft:the_nether", 1, 2, 3);

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, differentDimension);
    }
}
