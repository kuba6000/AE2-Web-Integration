package pl.kuba6000.ae2webintegration.core.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.CraftingRequester;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

class CraftingRequesterContextTest {

    private final PlayerIdentity alice = new PlayerIdentity(new UUID(0, 1), "Alice");
    private final PlayerIdentity nativeActor = new PlayerIdentity(new UUID(0, 2), "WebPlayer");

    @Test
    void withoutAContextTheNativeRequesterDecides() {
        assertEquals(CraftingRequester.automation(), CraftingRequesterContext.resolve(null));
        assertEquals(CraftingRequester.player(alice), CraftingRequesterContext.resolve(alice));
    }

    @Test
    void anOpenContextOverridesTheNativeActorForEverySource() {
        StableKey order = StableKey.random();
        try (CraftingRequesterContext ignored = CraftingRequesterContext.open(alice)) {
            assertEquals(CraftingRequester.player(alice), CraftingRequesterContext.resolve(nativeActor));
        }
        try (CraftingRequesterContext ignored = CraftingRequesterContext.open(null)) {
            assertEquals(CraftingRequester.web(), CraftingRequesterContext.resolve(nativeActor));
        }
        try (CraftingRequesterContext ignored = CraftingRequesterContext.openStandingOrder(order, "Keep plastic")) {
            assertEquals(
                CraftingRequester.standingOrder(order, "Keep plastic"),
                CraftingRequesterContext.resolve(null));
        }
        assertEquals(CraftingRequester.player(nativeActor), CraftingRequesterContext.resolve(nativeActor));
    }

    @Test
    void closingANestedContextRestoresTheOuterOne() {
        StableKey order = StableKey.random();
        try (CraftingRequesterContext ignored = CraftingRequesterContext.open(alice)) {
            try (CraftingRequesterContext ignoredNested = CraftingRequesterContext.openStandingOrder(order, "Order")) {
                assertEquals(CraftingRequester.standingOrder(order, "Order"), CraftingRequesterContext.resolve(null));
            }
            assertEquals(CraftingRequester.player(alice), CraftingRequesterContext.resolve(null));
        }
        assertEquals(CraftingRequester.automation(), CraftingRequesterContext.resolve(null));
    }
}
