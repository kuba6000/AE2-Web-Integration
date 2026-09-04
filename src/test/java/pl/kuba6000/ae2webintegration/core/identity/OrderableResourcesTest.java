package pl.kuba6000.ae2webintegration.core.identity;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

class OrderableResourcesTest {

    private enum Key implements IAEKey {

        RECIPE,
        EMITTER,
        BOTH,
        ABSENT;

        @Override
        public String web$getItemID() {
            return "test:" + name();
        }

        @Override
        public String web$getDisplayName() {
            return name();
        }

        @Override
        public boolean web$isCraftable(IAEGrid grid) {
            return true;
        }

        @Override
        public boolean web$isSameType(IAEKey other) {
            return this == other;
        }
    }

    @Test
    void exposesRecipeAndEmitterMembershipAndBoundedOneEntryTraversal() {
        Set<IAEKey> recipes = new LinkedHashSet<>(Arrays.asList(Key.RECIPE, Key.BOTH));
        Set<IAEKey> emitters = new LinkedHashSet<>(Arrays.asList(Key.EMITTER, Key.BOTH));
        OrderableResources resources = new OrderableResources(
            recipes::iterator,
            emitters::iterator,
            key -> recipes.contains(key) || emitters.contains(key),
            () -> true);

        assertTrue(resources.contains(Key.RECIPE));
        assertTrue(resources.contains(Key.EMITTER));
        assertFalse(resources.contains(Key.ABSENT));
        List<IAEKey> visited = new ArrayList<>();
        resources.openCursor()
            .forEachRemaining(visited::add);
        assertEquals(Arrays.asList(Key.RECIPE, Key.BOTH, Key.EMITTER, Key.BOTH), visited);
        assertTrue(resources.isCurrent(resources.getRevision()));
    }

    @Test
    void invalidationRejectsOldCursorsBeforeNativeCollectionsAreTouched() {
        Set<IAEKey> recipes = new LinkedHashSet<>(Arrays.asList(Key.RECIPE));
        OrderableResources resources = new OrderableResources(
            recipes::iterator,
            () -> Collections.<IAEKey>emptyList()
                .iterator(),
            recipes::contains,
            () -> true);
        long previous = resources.getRevision();
        Iterator<IAEKey> stale = resources.openCursor();
        resources.beginUpdate();
        recipes.add(Key.EMITTER);

        assertFalse(resources.isReady());
        assertFalse(resources.isCurrent(previous));
        assertThrows(OrderableResources.UnavailableException.class, stale::hasNext);
        assertThrows(OrderableResources.UnavailableException.class, stale::next);
        assertThrows(OrderableResources.UnavailableException.class, resources::openCursor);
        assertThrows(OrderableResources.UnavailableException.class, () -> resources.contains(Key.EMITTER));

        resources.endUpdate();
        assertTrue(resources.isReady());
        assertTrue(resources.contains(Key.EMITTER));
        assertFalse(resources.isCurrent(previous));
        assertThrows(OrderableResources.UnavailableException.class, stale::next);
        List<IAEKey> current = new ArrayList<>();
        resources.openCursor()
            .forEachRemaining(current::add);
        assertEquals(Arrays.asList(Key.RECIPE, Key.EMITTER), current);
    }

    @Test
    void nestedUpdatesAndDeferredNativeRefreshStayUnavailableUntilBothFinish() {
        AtomicBoolean nativeReady = new AtomicBoolean(true);
        OrderableResources resources = new OrderableResources(
            () -> Collections.<IAEKey>emptyList()
                .iterator(),
            () -> Collections.<IAEKey>emptyList()
                .iterator(),
            key -> true,
            nativeReady::get);
        resources.beginUpdate();
        resources.beginUpdate();
        resources.endUpdate();
        assertFalse(resources.isReady());
        nativeReady.set(false);
        resources.endUpdate();
        assertFalse(resources.isReady());
        assertThrows(OrderableResources.UnavailableException.class, resources::openCursor);
        nativeReady.set(true);
        assertTrue(resources.isReady());
        assertThrows(IllegalStateException.class, resources::endUpdate);
    }

    @Test
    void rejectsMembershipResultIfNativeCallbackChangesTheSource() {
        OrderableResources[] source = new OrderableResources[1];
        source[0] = new OrderableResources(
            () -> Collections.<IAEKey>emptyList()
                .iterator(),
            () -> Collections.<IAEKey>emptyList()
                .iterator(),
            key -> {
                source[0].beginUpdate();
                source[0].endUpdate();
                return true;
            },
            () -> true);
        assertThrows(OrderableResources.UnavailableException.class, () -> source[0].contains(Key.RECIPE));
    }

    @Test
    void nativeCursorConstructionCannotReturnAnAlreadyInvalidatedView() {
        OrderableResources[] source = new OrderableResources[1];
        source[0] = new OrderableResources(() -> {
            source[0].beginUpdate();
            source[0].endUpdate();
            return Arrays.<IAEKey>asList(Key.RECIPE)
                .iterator();
        },
            () -> Collections.<IAEKey>emptyList()
                .iterator(),
            key -> true,
            () -> true);
        assertThrows(
            OrderableResources.UnavailableException.class,
            () -> source[0].openCursor()
                .hasNext());
    }
}
