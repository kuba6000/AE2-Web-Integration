package pl.kuba6000.ae2webintegration.core.patterns;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternProvider;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternSlotHandle;

class PatternProviderReaderTest {

    @Test
    void resumesAtSlotBoundariesAndOnlyDecodesReplacedSlots() {
        AtomicInteger decodes = new AtomicInteger();
        IPatternSlotHandle broken = broken(decodes);
        IPatternSlotHandle[] slots = { null, broken, broken };
        IPatternProvider provider = provider(slots);
        IAEGrid grid = grid(provider);
        PatternProviderReader reader = new PatternProviderReader();
        reader.begin(grid);
        assertNull(reader.step(0L, () -> 0L));
        PatternProviderReader.Delta initial = reader.step(1L, () -> 0L);
        PatternProvider row = initial.replacements()
            .values()
            .iterator()
            .next();
        assertEquals(
            PatternProvider.Kind.EMPTY,
            row.slots()
                .get(0)
                .kind());
        assertEquals(
            PatternProvider.Kind.INVALID,
            row.slots()
                .get(2)
                .kind());
        assertEquals(2, decodes.get());

        reader.begin(grid);
        assertTrue(
            reader.step(1L, () -> 0L)
                .replacements()
                .isEmpty());
        assertEquals(2, decodes.get());

        slots[1] = null;
        reader.begin(grid);
        assertEquals(
            PatternProvider.Kind.EMPTY,
            reader.step(1L, () -> 0L)
                .replacements()
                .get(
                    row.header()
                        .key())
                .slots()
                .get(1)
                .kind());
        assertEquals(
            PatternProvider.Kind.INVALID,
            row.slots()
                .get(1)
                .kind(),
            "Previous snapshot stays detached");
        assertEquals(2, decodes.get());

        reader.begin(grid());
        assertEquals(
            Collections.singleton(
                row.header()
                    .key()),
            reader.step(1L, () -> 0L)
                .removed());
    }

    @Test
    void replacingInvalidEncodingWithoutChangingDisplayedSlotDoesNotRepublishProvider() {
        AtomicInteger decodes = new AtomicInteger();
        IPatternSlotHandle[] slots = { broken(decodes) };
        IAEGrid grid = grid(provider(slots));
        PatternProviderReader reader = new PatternProviderReader();
        reader.begin(grid);
        assertEquals(
            1,
            reader.step(1L, () -> 0L)
                .replacements()
                .size());
        slots[0] = broken(decodes);
        reader.begin(grid);
        assertTrue(
            reader.step(1L, () -> 0L)
                .replacements()
                .isEmpty());
        reader.begin(grid);
        assertTrue(
            reader.step(1L, () -> 0L)
                .replacements()
                .isEmpty());
        assertEquals(2, decodes.get(), "Retain the replacement encoding even when the output is unchanged");
    }

    @Test
    void ambiguousProviderAddressesCannotOverwriteEachOther() {
        IPatternProvider first = provider(new IPatternSlotHandle[0]);
        IPatternProvider second = provider(new IPatternSlotHandle[0]);
        PatternProviderReader reader = new PatternProviderReader();
        reader.begin(grid(first, second));
        assertThrows(IllegalStateException.class, () -> reader.step(1L, () -> 0L));
    }

    private static IAEGrid grid(IPatternProvider... providers) {
        return (IAEGrid) Proxy.newProxyInstance(
            IAEGrid.class.getClassLoader(),
            new Class<?>[] { IAEGrid.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("web$getPatternProviders")) return Arrays.asList(providers);
                throw new AssertionError(method);
            });
    }

    private static IPatternProvider provider(IPatternSlotHandle[] slots) {
        return (IPatternProvider) Proxy.newProxyInstance(
            IPatternProvider.class.getClassLoader(),
            new Class<?>[] { IPatternProvider.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "web$getName" -> "Furnace";
                case "web$getLocation" -> new DimensionalCoords("nether", 2, 3, 4);
                case "web$getPatternSide", "web$getPatternIcon", "web$getPatternPriority" -> null;
                case "web$isPatternProviderOnline", "web$isPatternProviderVisible" -> true;
                case "web$getPatternSlotCount" -> slots.length;
                case "web$getPatternRowSize" -> 9;
                case "web$getPatternSortValue" -> 0L;
                case "web$getPattern" -> slots[(int) args[0]];
                default -> throw new AssertionError(method);
            });
    }

    private static IPatternSlotHandle broken(AtomicInteger decodes) {
        return (IPatternSlotHandle) Proxy.newProxyInstance(
            IPatternSlotHandle.class.getClassLoader(),
            new Class<?>[] { IPatternSlotHandle.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "web$decode" -> {
                    decodes.incrementAndGet();
                    yield null;
                }
                case "web$sameAs" -> proxy == args[0];
                default -> throw new AssertionError(method);
            });
    }
}
