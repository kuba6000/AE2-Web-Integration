package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import cpw.mods.fml.common.registry.GameData;

/** Incremental registry traversal. Only one item's published variant list is retained at a time. */
final class IconCatalogue {

    private final Iterator<Item> items;
    private final Iterator<Map.Entry<String, Fluid>> fluids;
    private final @Nullable NeiCatalogue nei;
    private final BiConsumer<String, Throwable> failures;
    private Iterator<ItemStack> variants = Collections.<ItemStack>emptyList()
        .iterator();
    private @Nullable Item currentItem;
    private CreativeTabs[] tabs = new CreativeTabs[0];
    private int tabIndex;
    private int variantCount;
    private int variantIndex;
    private int neiIndex;
    private boolean done;
    private String context = "native";
    private int nativeCandidates;
    private int neiCandidates;
    private int fluidCandidates;

    @SuppressWarnings("unchecked") // Minecraft's registry exposes its vanilla raw Iterable.
    IconCatalogue(@Nullable NeiCatalogue nei, @NotNull BiConsumer<String, Throwable> failures) {
        this.nei = nei;
        this.failures = failures;
        List<Item> registry = new ArrayList<>();
        for (Item item : (Iterable<Item>) Item.itemRegistry) registry.add(item);
        registry.sort(
            Comparator.comparing(
                item -> GameData.getItemRegistry()
                    .getNameForObject(item)));
        items = registry.iterator();
        fluids = new TreeMap<>(FluidRegistry.getRegisteredFluids()).entrySet()
            .iterator();
    }

    void verify() {
        if (nei != null) nei.verify();
    }

    @Nullable
    IconCandidate next() {
        if (variants.hasNext() || currentItem != null || items.hasNext()) return nextNative();
        if (nei != null && neiIndex < nei.size()) {
            context = "nei/" + neiIndex;
            neiCandidates++;
            return IconCandidate.item(context, nei.copy(neiIndex++));
        }
        if (fluids.hasNext()) {
            Map.Entry<String, Fluid> fluid = fluids.next();
            context = "fluid/" + fluid.getKey();
            fluidCandidates++;
            return IconCandidate.fluid(context, new FluidStack(fluid.getValue(), FluidContainerRegistry.BUCKET_VOLUME));
        }
        done = true;
        return null;
    }

    private @Nullable IconCandidate nextNative() {
        if (variants.hasNext()) {
            context = "native/" + GameData.getItemRegistry()
                .getNameForObject(currentItem) + "/" + variantIndex++;
            nativeCandidates++;
            ItemStack stack = variants.next();
            if (stack == null) throw new IllegalArgumentException("Published item variant is null");
            return IconCandidate.item(context, stack.copy());
        }
        if (currentItem != null) {
            if (tabIndex < tabs.length) {
                discoverVariants(currentItem, tabs[tabIndex++]);
                return null;
            }
            Item item = currentItem;
            currentItem = null;
            if (variantCount == 0) {
                nativeCandidates++;
                return IconCandidate.item(context + "/default", new ItemStack(item));
            }
        }
        if (items.hasNext()) beginItem(items.next());
        return null;
    }

    private void discoverVariants(@NotNull Item item, @Nullable CreativeTabs tab) {
        List<ItemStack> published = new ArrayList<>();
        try {
            item.getSubItems(item, tab, published);
        } catch (Throwable failure) {
            failures.accept(context + "/subtypes", failure);
        }
        variantCount += published.size();
        variants = published.iterator();
    }

    private void beginItem(@NotNull Item item) {
        context = "native/" + GameData.getItemRegistry()
            .getNameForObject(item);
        CreativeTabs[] declaredTabs = item.getCreativeTabs();
        List<CreativeTabs> nonnullTabs = new ArrayList<>();
        if (declaredTabs != null) {
            for (CreativeTabs tab : declaredTabs) {
                if (tab != null && !nonnullTabs.contains(tab)) nonnullTabs.add(tab);
            }
        }
        tabs = nonnullTabs.isEmpty() ? new CreativeTabs[] { null } : nonnullTabs.toArray(new CreativeTabs[0]);
        currentItem = item;
        tabIndex = 0;
        variantCount = 0;
        variantIndex = 0;
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
        Map<String, Long> result = new TreeMap<>();
        result.put("nativeCandidates", (long) nativeCandidates);
        result.put("neiCandidates", (long) neiCandidates);
        result.put("fluidCandidates", (long) fluidCandidates);
        return result;
    }
}
