package pl.kuba6000.ae2webintegration.core.crafting;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Supplier;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.crafting.CraftingRecipeCapture.Limit;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

/**
 * Admission bookkeeping shared by the detached capture builders. Every appended row consumes one entry;
 * descriptions additionally consume UTF-16 characters. These are retained data bounds, not bounds on
 * native getter cost or JVM heap bytes. The first rejection records its reason and closes the capture.
 */
final class CaptureBudget {

    final List<ResourceDescriptor> resources = new ArrayList<>();
    final EnumSet<Limit> limits = EnumSet.noneOf(Limit.class);
    private final int maxEntries;
    private int entries;
    private final int maxDescriptionCharacters;
    private long descriptionCharacters;

    CaptureBudget(int maxEntries, int maxDescriptionCharacters) {
        if (maxEntries < 0 || maxDescriptionCharacters < 0)
            throw new IllegalArgumentException("Negative capture budget");
        this.maxEntries = maxEntries;
        this.maxDescriptionCharacters = maxDescriptionCharacters;
    }

    boolean hasCapacity() {
        return limits.isEmpty() && entries < maxEntries;
    }

    void stopAtEntryLimit() {
        if (entries < maxEntries) throw new IllegalStateException("Entry budget not exhausted");
        limits.add(Limit.ENTRIES);
    }

    boolean admit() {
        if (!limits.isEmpty()) return false;
        if (entries >= maxEntries) {
            limits.add(Limit.ENTRIES);
            return false;
        }
        entries++;
        return true;
    }

    /** Invokes the native description supplier only after entry admission; rejection returns -1. */
    int addResource(@NotNull Supplier<ResourceDescriptor> descriptor) {
        if (!admit()) return -1;
        ResourceDescriptor value = descriptor.get();
        long characters = (long) value.displayName()
            .length() + length(value.registryNamespace()) + length(value.registryPath());
        if (characters > maxDescriptionCharacters - descriptionCharacters) {
            entries--;
            limits.add(Limit.DESCRIPTION_CHARACTERS);
            return -1;
        }
        descriptionCharacters += characters;
        resources.add(value);
        return resources.size() - 1;
    }

    private static int length(@Nullable String value) {
        return value == null ? 0 : value.length();
    }
}
