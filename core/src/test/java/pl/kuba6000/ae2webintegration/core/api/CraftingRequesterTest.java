package pl.kuba6000.ae2webintegration.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.kuba6000.ae2webintegration.core.utils.GSONUtils.GSON_BUILDER;

import java.util.Collections;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderValidator;

@SuppressWarnings("PMD.AvoidMagicNumbers") // Arbitrary fixture values.
class CraftingRequesterTest {

    private final PlayerIdentity alice = new PlayerIdentity(new UUID(0, 1), "Alice");
    private final StableKey orderKey = StableKey.random();

    @Test
    void standingOrderCarriesTheOrderIdentityAndNoPlayer() {
        CraftingRequester requester = CraftingRequester.standingOrder(orderKey, "Keep plastic");
        assertEquals(CraftingRequester.Source.STANDING_ORDER, requester.source());
        assertEquals(orderKey, requester.orderKey());
        assertEquals("Keep plastic", requester.orderName());
        assertNull(requester.player());
    }

    @Test
    void otherSourcesCarryNoOrderIdentity() {
        for (CraftingRequester requester : new CraftingRequester[] { CraftingRequester.player(alice),
            CraftingRequester.automation(), CraftingRequester.web() }) {
            assertNull(requester.orderKey());
            assertNull(requester.orderName());
        }
    }

    @Test
    void inconsistentSourceAndIdentityCombinationsCannotBeConstructed() {
        for (CraftingRequester.Source source : new CraftingRequester.Source[] { CraftingRequester.Source.AUTOMATION,
            CraftingRequester.Source.WEB }) {
            assertFalse(rejected(source, null, null, null), source.name());
            assertTrue(rejected(source, alice, null, null), source.name());
            assertTrue(rejected(source, null, orderKey, "Order"), source.name());
        }
        assertTrue(rejected(CraftingRequester.Source.STANDING_ORDER, null, null, "Order"));
        assertTrue(rejected(CraftingRequester.Source.STANDING_ORDER, null, orderKey, null));
        assertTrue(rejected(CraftingRequester.Source.STANDING_ORDER, alice, orderKey, "Order"));
        assertTrue(rejected(CraftingRequester.Source.PLAYER, alice, orderKey, "Order"));
        assertTrue(rejected(CraftingRequester.Source.PLAYER, alice, null, "Order"));
        assertTrue(rejected(CraftingRequester.Source.PLAYER, null, null, null));
        assertFalse(rejected(CraftingRequester.Source.PLAYER, alice, null, null));
        assertFalse(rejected(CraftingRequester.Source.STANDING_ORDER, null, orderKey, "Order"));
    }

    @Test
    void orderNamesFollowTheDefinitionNameRules() {
        String longest = String.join("", Collections.nCopies(StandingOrderValidator.MAX_NAME_LENGTH, "x"));
        assertEquals(
            longest,
            CraftingRequester.standingOrder(orderKey, longest)
                .orderName());
        for (String name : new String[] { "", longest + "x", " padded", "padded ", "bad\nname", "bad\u0000name" }) {
            assertThrows(
                IllegalArgumentException.class,
                () -> CraftingRequester.standingOrder(orderKey, name),
                "Name accepted: " + name);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "PLAYER", "AUTOMATION", "WEB", "STANDING_ORDER" })
    void everySourceKeepsItsJsonShape(String name) {
        CraftingRequester.Source source = CraftingRequester.Source.valueOf(name);
        CraftingRequester requester = source == CraftingRequester.Source.PLAYER ? CraftingRequester.player(alice)
            : source == CraftingRequester.Source.STANDING_ORDER ? CraftingRequester.standingOrder(orderKey, "Order")
                : new CraftingRequester(source, null, null, null);
        Gson gson = GSON_BUILDER.create();
        JsonObject json = gson.toJsonTree(requester)
            .getAsJsonObject();
        assertEquals(
            name,
            json.get("source")
                .getAsString());
        assertEquals(
            source == CraftingRequester.Source.PLAYER,
            json.get("player")
                .isJsonObject());
        assertEquals(
            source == CraftingRequester.Source.STANDING_ORDER ? orderKey.toString() : null,
            json.get("orderKey")
                .isJsonNull() ? null
                    : json.get("orderKey")
                        .getAsString());
        assertEquals(
            source == CraftingRequester.Source.STANDING_ORDER ? "Order" : null,
            json.get("orderName")
                .isJsonNull() ? null
                    : json.get("orderName")
                        .getAsString());
    }

    @Test
    void sameRequesterComparesPlayersByUuidAndOrdersByKey() {
        PlayerIdentity renamed = new PlayerIdentity(alice.uuid, "Renamed");
        PlayerIdentity other = new PlayerIdentity(new UUID(0, 2), "Alice");
        StableKey otherKey = StableKey.random();
        assertTrue(
            CraftingRequester.player(alice)
                .isSameRequester(CraftingRequester.player(renamed)));
        assertFalse(
            CraftingRequester.player(alice)
                .isSameRequester(CraftingRequester.player(other)));
        assertTrue(
            CraftingRequester.standingOrder(orderKey, "Old")
                .isSameRequester(CraftingRequester.standingOrder(orderKey, "New")));
        assertFalse(
            CraftingRequester.standingOrder(orderKey, "Same")
                .isSameRequester(CraftingRequester.standingOrder(otherKey, "Same")));
        assertTrue(
            CraftingRequester.web()
                .isSameRequester(CraftingRequester.web()));
        assertTrue(
            CraftingRequester.automation()
                .isSameRequester(CraftingRequester.automation()));
        assertFalse(
            CraftingRequester.web()
                .isSameRequester(CraftingRequester.automation()));
        assertFalse(
            CraftingRequester.player(alice)
                .isSameRequester(CraftingRequester.standingOrder(orderKey, "Order")));
        assertFalse(
            CraftingRequester.standingOrder(orderKey, "Order")
                .isSameRequester(CraftingRequester.web()));
    }

    private static boolean rejected(CraftingRequester.Source source, PlayerIdentity player, StableKey key,
        String name) {
        try {
            new CraftingRequester(source, player, key, name);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }
}
