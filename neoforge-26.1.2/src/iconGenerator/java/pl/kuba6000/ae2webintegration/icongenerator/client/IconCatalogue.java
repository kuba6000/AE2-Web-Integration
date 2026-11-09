package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;

import net.minecraft.ReportedException;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.icons.export.IIconSource;

/** Native registry and creative display/search variants; this target has no compatible EMI integration. */
final class IconCatalogue implements IIconSource<IconCandidate> {

    private boolean prepared;

    private Iterator<Item> items = Collections.<Item>emptyList()
        .iterator();
    private Iterator<Fluid> fluids = Collections.emptyIterator();
    private Iterator<CreativeModeTab> tabs = Collections.emptyIterator();
    private final FeatureFlagSet features;
    private final HolderLookup.Provider registries;
    private Iterator<ItemStack> variants = Collections.emptyIterator();
    private Iterator<ItemStack> searchVariants = Collections.emptyIterator();
    private long creativeRebuildNanos;
    private boolean done;
    private int nativeCandidates;
    private int creativeCandidates;
    private int fluidCandidates;
    private int skippedEmpty;
    private int skippedDisabled;
    private String context = "native";

    IconCatalogue(@NotNull ClientLevel world) {
        features = world.enabledFeatures();
        registries = world.registryAccess();
    }

    @Override
    public @Nullable String prepare(@NotNull BiConsumer<String, Throwable> failures) {
        if (prepared) return null;
        initialize(failures);
        prepared = true;
        return null;
    }

    private void initialize(@NotNull BiConsumer<String, Throwable> failures) {
        long started = System.nanoTime();
        // Match ordinary creative visibility, without privileged operator entries.
        try {
            CreativeModeTabs.tryRebuildTabContents(features, false, registries);
            tabs = CreativeModeTabs.allTabs()
                .iterator();
        } catch (RuntimeException | LinkageError failure) {
            failures.accept("creative/setup", failure);
        } finally {
            creativeRebuildNanos = System.nanoTime() - started;
        }
        List<Item> registeredItems = new ArrayList<>();
        BuiltInRegistries.ITEM.forEach(registeredItems::add);
        registeredItems.sort(
            Comparator.comparing(
                item -> BuiltInRegistries.ITEM.getKey(item)
                    .toString()));
        items = registeredItems.iterator();
        List<Fluid> registeredFluids = new ArrayList<>();
        BuiltInRegistries.FLUID.forEach(registeredFluids::add);
        registeredFluids.sort(
            Comparator.comparing(
                fluid -> BuiltInRegistries.FLUID.getKey(fluid)
                    .toString()));
        fluids = registeredFluids.iterator();
    }

    @Override
    public void verify() {

    }

    @Override
    @Nullable
    public IconCandidate next() {
        if (items.hasNext()) {
            Item item = items.next();
            context = "native/" + BuiltInRegistries.ITEM.getKey(item);
            nativeCandidates++;
            return itemCandidate(item.getDefaultInstance());
        }
        if (variants.hasNext() || searchVariants.hasNext() || tabs.hasNext()) {
            if (!variants.hasNext() && !searchVariants.hasNext()) {
                CreativeModeTab tab = tabs.next();
                context = "creative/" + BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
                variants = tab.getDisplayItems()
                    .iterator();
                searchVariants = tab.getSearchTabDisplayItems()
                    .iterator();
            }
            if (variants.hasNext() || searchVariants.hasNext()) {
                creativeCandidates++;
                return itemCandidate(variants.hasNext() ? variants.next() : searchVariants.next());
            }
            return null;
        }
        if (fluids.hasNext()) {
            Fluid fluid = fluids.next();
            context = "fluid/" + BuiltInRegistries.FLUID.getKey(fluid);
            if (fluid == Fluids.EMPTY) {
                skippedEmpty++;
                return null;
            }
            fluidCandidates++;
            return IconCandidate.fluid(context, new FluidStack(fluid, 1), registries);
        }
        done = true;
        return null;
    }

    private @Nullable IconCandidate itemCandidate(@NotNull ItemStack stack) {
        if (stack.isEmpty()) {
            skippedEmpty++;
            return null;
        }
        if (!stack.getItem()
            .isEnabled(features)) {
            skippedDisabled++;
            return null;
        }
        return IconCandidate.item(context, stack, registries);
    }

    @Override
    public boolean done() {
        return done;
    }

    @Override
    @NotNull
    public String context() {
        return context;
    }

    @Override
    @NotNull
    public Map<String, Long> counts() {
        Map<String, Long> counts = new TreeMap<>();
        counts.put("nativeCandidates", (long) nativeCandidates);
        counts.put("creativeCandidates", (long) creativeCandidates);
        counts.put("fluidCandidates", (long) fluidCandidates);
        counts.put("skippedEmpty", (long) skippedEmpty);
        counts.put("skippedFeatureDisabled", (long) skippedDisabled);
        counts.put("creativeRebuildNanos", creativeRebuildNanos);
        return counts;
    }

    @Override
    public @NotNull List<String> sources() {
        return List.of("native", "creative", "fluids");
    }

    @Override
    public @NotNull String note() {
        return "";
    }

    @Override
    public @NotNull Throwable failureCause(@NotNull Throwable failure) {
        return failure instanceof ReportedException ? failure.getCause() : failure;
    }
}
