package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.registries.ForgeRegistries;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEFluidKey;

/** Native registries and creative display/search entries, optionally unioned with a completed EMI index. */
final class IconCatalogue {

    private final Iterator<Item> items;
    private final Iterator<Fluid> fluids;
    private final Iterator<CreativeModeTab> tabs;
    private final FeatureFlagSet features;
    private final @Nullable EmiCatalogue emi;
    private Iterator<ItemStack> variants = Collections.emptyIterator();
    private Iterator<ItemStack> searchVariants = Collections.emptyIterator();
    private final long creativeRebuildNanos;
    private boolean done;
    private int emiIndex;
    private int nativeCandidates;
    private int creativeCandidates;
    private int emiCandidates;
    private int fluidCandidates;
    private int skippedEmpty;
    private int skippedDisabled;
    private String context = "native";

    IconCatalogue(@NotNull Minecraft minecraft, @Nullable EmiCatalogue emi) {
        this.emi = emi;
        features = minecraft.level.enabledFeatures();
        long started = System.nanoTime();
        // Match ordinary creative visibility, without privileged operator entries.
        CreativeModeTabs.tryRebuildTabContents(features, false, minecraft.level.registryAccess());
        creativeRebuildNanos = System.nanoTime() - started;
        tabs = CreativeModeTabs.allTabs()
            .iterator();
        List<Item> registeredItems = new ArrayList<>();
        ForgeRegistries.ITEMS.forEach(registeredItems::add);
        registeredItems.sort(
            Comparator.comparing(
                item -> ForgeRegistries.ITEMS.getKey(item)
                    .toString()));
        items = registeredItems.iterator();
        List<Fluid> registeredFluids = new ArrayList<>();
        ForgeRegistries.FLUIDS.forEach(registeredFluids::add);
        registeredFluids.sort(
            Comparator.comparing(
                fluid -> ForgeRegistries.FLUIDS.getKey(fluid)
                    .toString()));
        fluids = registeredFluids.iterator();
    }

    void verify() {
        if (emi != null) emi.verify();
    }

    @Nullable
    IconCandidate next() {
        if (items.hasNext()) {
            Item item = items.next();
            context = "native/" + ForgeRegistries.ITEMS.getKey(item);
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
        if (emi != null && emiIndex < emi.size()) {
            context = "emi/" + emiIndex;
            emiCandidates++;
            return emi.candidate(emiIndex++);
        }
        if (fluids.hasNext()) {
            Fluid fluid = fluids.next();
            context = "fluid/" + ForgeRegistries.FLUIDS.getKey(fluid);
            if (fluid == Fluids.EMPTY) {
                skippedEmpty++;
                return null;
            }
            fluidCandidates++;
            return new IconCandidate(context, AEFluidKey.of(fluid));
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
        return IconCandidate.item(context, stack);
    }

    boolean done() {
        return done;
    }

    @NotNull
    String context() {
        return context;
    }

    @NotNull
    Map<String, Long> counts() {
        Map<String, Long> counts = new TreeMap<>();
        counts.put("nativeCandidates", (long) nativeCandidates);
        counts.put("creativeCandidates", (long) creativeCandidates);
        counts.put("emiCandidates", (long) emiCandidates);
        counts.put("fluidCandidates", (long) fluidCandidates);
        counts.put("skippedEmpty", (long) skippedEmpty);
        counts.put("skippedFeatureDisabled", (long) skippedDisabled);
        counts.put("creativeRebuildNanos", creativeRebuildNanos);
        return counts;
    }
}
