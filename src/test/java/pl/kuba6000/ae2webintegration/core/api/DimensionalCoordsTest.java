package pl.kuba6000.ae2webintegration.core.api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class DimensionalCoordsTest {

    @Test
    void orderingUsesDimensionThenXYZAndAgreesWithEquality() {
        DimensionalCoords first = new DimensionalCoords("a", 1, 2, 3);
        assertEquals(0, first.compareTo(new DimensionalCoords("a", 1, 2, 3)));
        assertTrue(first.compareTo(new DimensionalCoords("b", Integer.MIN_VALUE, 0, 0)) < 0);
        assertTrue(first.compareTo(new DimensionalCoords("a", 2, 0, 0)) < 0);
        assertTrue(first.compareTo(new DimensionalCoords("a", 1, 3, 0)) < 0);
        assertTrue(first.compareTo(new DimensionalCoords("a", 1, 2, 4)) < 0);
        assertTrue(
            new DimensionalCoords("a", Integer.MIN_VALUE, 0, 0)
                .compareTo(new DimensionalCoords("a", Integer.MAX_VALUE, 0, 0)) < 0);
    }

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
