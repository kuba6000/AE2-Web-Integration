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

    private final PatternProviderReader reader = new PatternProviderReader();

    @Test
    void completeReadsReuseDecodedSlotsAndReportChanges() {
        AtomicInteger decodes = new AtomicInteger();
        IPatternSlotHandle broken = broken(decodes);
        IPatternSlotHandle[] slots = { null, broken, broken };
        IPatternProvider provider = provider(slots);
        IAEGrid grid = grid(provider);
        PatternProviderReader.Delta initial = reader.read(grid);
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

        assertTrue(
            reader.read(grid)
                .replacements()
                .isEmpty());
        assertEquals(2, decodes.get());

        slots[1] = null;
        assertEquals(
            PatternProvider.Kind.EMPTY,
            reader.read(grid)
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

        assertEquals(
            Collections.singleton(
                row.header()
                    .key()),
            reader.read(grid())
                .removed());
    }

    @Test
    void replacingInvalidEncodingWithoutChangingDisplayedSlotDoesNotRepublishProvider() {
        AtomicInteger decodes = new AtomicInteger();
        IPatternSlotHandle[] slots = { broken(decodes) };
        IAEGrid grid = grid(provider(slots));
        assertEquals(
            1,
            reader.read(grid)
                .replacements()
                .size());
        slots[0] = broken(decodes);
        assertTrue(
            reader.read(grid)
                .replacements()
                .isEmpty());
        assertTrue(
            reader.read(grid)
                .replacements()
                .isEmpty());
        assertEquals(2, decodes.get(), "Retain the replacement encoding even when the output is unchanged");
    }

    @Test
    void ambiguousProviderAddressesCannotOverwriteEachOther() {
        IPatternProvider first = provider(new IPatternSlotHandle[0]);
        IPatternProvider second = provider(new IPatternSlotHandle[0]);
        assertThrows(IllegalStateException.class, () -> reader.read(grid(first, second)));
        assertEquals(
            1,
            reader.read(grid(first))
                .replacements()
                .size(),
            "A failed scan cannot publish a partial baseline");
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
