package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ReportedException;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.OreDictionary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.registry.GameData;
import pl.kuba6000.ae2webintegration.core.icons.export.IIconSource;
import pl.kuba6000.ae2webintegration.icongenerator.IconGeneratorMod;

/** Incremental native/NEI/OreDictionary discovery plus registered fluids. */
final class IconCatalogue implements IIconSource<IconCandidate> {

    private static final Logger LOG = LogManager.getLogger(IconGeneratorMod.MOD_ID);
    private final boolean neiInstalled;
    private boolean useNei;
    private boolean neiUnavailable;
    private boolean prepared;
    private boolean neiDisabled;

    private Iterator<Item> items = Collections.<Item>emptyList()
        .iterator();
    private Iterator<Map.Entry<String, Fluid>> fluids = Collections.<Map.Entry<String, Fluid>>emptyList()
        .iterator();
    private @Nullable NeiCatalogue nei;
    private @Nullable NeiRecipeCatalogue recipes;
    private long recipeFailures;
    private @Nullable BiConsumer<String, Throwable> failures;
    private Iterator<ItemStack> variants = Collections.<ItemStack>emptyList()
        .iterator();
    private @Nullable Item currentItem;
    private CreativeTabs[] tabs = new CreativeTabs[0];
    private int tabIndex;
    private int variantCount;
    private int variantIndex;
    private int neiIndex;
    private String[] oreNames = new String[0];
    private int oreNameIndex;
    private List<ItemStack> oreEntries = Collections.emptyList();
    private int oreEntryIndex;
    private int oreDictionaryCandidates;
    private int oreDictionarySkippedInvalid;
    private int oreDictionarySkippedWildcard;
    private boolean done;
    private String context = "native";
    private int nativeCandidates;
    private int neiCandidates;
    private int fluidCandidates;

    IconCatalogue() {
        neiInstalled = Loader.isModLoaded("NotEnoughItems");
    }

    @Override
    public @Nullable String prepare(@NotNull BiConsumer<String, Throwable> failures) {
        if (prepared) return null;
        try {
            neiDisabled = neiInstalled && NeiCatalogue.disabled();
            useNei = neiInstalled && !neiDisabled;
            nei = useNei ? NeiCatalogue.ready() : null;
            if (nei != null) recipes = new NeiRecipeCatalogue();
        } catch (RuntimeException | LinkageError failure) {
            nei = null;
            recipes = null;
            neiUnavailable = true;
            useNei = false;
            failures.accept("nei/setup", failure);
            String warning = "NEI catalogue unavailable (initialization failed); exporting native items, OreDictionary and fluids";
            LOG.warn(warning, failure);
        }
        if (useNei && nei == null) {
            return "Waiting for NEI to finish loading its full catalogue; /ae2webicons cancel to stop";
        }
        this.failures = failures;
        initialize();
        prepared = true;
        return null;
    }

    @SuppressWarnings("unchecked") // Minecraft's registry exposes its vanilla raw Iterable.
    private void initialize() {
        List<Item> registry = new ArrayList<>();
        for (Item item : (Iterable<Item>) Item.itemRegistry) registry.add(item);
        registry.sort(
            Comparator.comparing(
                item -> GameData.getItemRegistry()
                    .getNameForObject(item)));
        items = registry.iterator();
        oreNames = OreDictionary.getOreNames();
        Arrays.sort(oreNames);
        fluids = new TreeMap<>(FluidRegistry.getRegisteredFluids()).entrySet()
            .iterator();
    }

    @Override
    public void verify() {
        if (nei != null) nei.verify();
    }

    @Override
    @Nullable
    public IconCandidate next() {
        if (variants.hasNext() || currentItem != null || items.hasNext()) return nextNative();
        if (nei != null && neiIndex < nei.size()) {
            context = "nei/" + neiIndex;
            neiCandidates++;
            return IconCandidate.item(context, nei.copy(neiIndex++));
        }
        if (oreEntryIndex < oreEntries.size() || oreNameIndex < oreNames.length) return nextOreDictionary();
        if (fluids.hasNext()) {
            Map.Entry<String, Fluid> fluid = fluids.next();
            context = "fluid/" + fluid.getKey();
            fluidCandidates++;
            return IconCandidate.fluid(context, new FluidStack(fluid.getValue(), FluidContainerRegistry.BUCKET_VOLUME));
        }
        if (recipes != null && !recipes.done()) {
            try {
                return recipes.next();
            } catch (Throwable failure) {
                Throwable cause = failureCause(failure);
                if (cause instanceof VirtualMachineError fatal) throw fatal;
                if (cause instanceof ThreadDeath fatal) throw fatal;
                recipeFailures++;
                assert failures != null : "Catalogue must be prepared before discovery";
                failures.accept(recipes.context(), failure);
                return null;
            } finally {
                context = recipes.context();
            }
        }
        done = true;
        return null;
    }

    private @Nullable IconCandidate nextOreDictionary() {
        if (oreEntryIndex >= oreEntries.size()) {
            context = "ore-dictionary/" + oreNames[oreNameIndex];
            oreEntries = OreDictionary.getOres(oreNames[oreNameIndex++], false);
            oreEntryIndex = 0;
            return null;
        }
        context = "ore-dictionary/" + oreNames[oreNameIndex - 1] + "/" + oreEntryIndex;
        ItemStack stack = oreEntries.get(oreEntryIndex++);
        oreDictionaryCandidates++;
        if (stack == null || stack.getItem() == null || stack.stackSize <= 0) {
            oreDictionarySkippedInvalid++;
            return null;
        }
        if (Items.blaze_rod.getDamage(stack) == OreDictionary.WILDCARD_VALUE) {
            oreDictionarySkippedWildcard++;
            return null;
        }
        return IconCandidate.item(context, stack.copy());
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
            assert failures != null : "Catalogue must be prepared before discovery";
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
        Map<String, Long> result = new TreeMap<>();
        result.put("nativeCandidates", (long) nativeCandidates);
        result.put("neiCandidates", (long) neiCandidates);
        result.put("fluidCandidates", (long) fluidCandidates);
        result.put("oreDictionaryCandidates", (long) oreDictionaryCandidates);
        result.put("oreDictionarySkippedInvalid", (long) oreDictionarySkippedInvalid);
        result.put("oreDictionarySkippedWildcard", (long) oreDictionarySkippedWildcard);
        result.put("neiRecipeFailures", recipeFailures);
        if (recipes != null) recipes.counts(result);
        return result;
    }

    @Override
    public @NotNull List<String> sources() {
        String neiSource = useNei ? "nei"
            : neiUnavailable ? "nei-unavailable" : neiDisabled ? "nei-disabled" : "nei-absent";
        return useNei ? Arrays.asList("native", neiSource, "ore-dictionary", "fluids", "nei-recipes")
            : Arrays.asList("native", neiSource, "ore-dictionary", "fluids");
    }

    @Override
    public @NotNull String note() {
        if (neiUnavailable) return " (NEI unavailable; native and OreDictionary catalogues)";
        if (neiDisabled) return " (NEI disabled; native and OreDictionary catalogues)";
        return "";
    }

    @Override
    public @NotNull Throwable failureCause(@NotNull Throwable failure) {
        if (failure instanceof NeiCatalogue.Changed changed) throw changed;
        return failure instanceof ReportedException ? failure.getCause() : failure;
    }
}
