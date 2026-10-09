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

import net.minecraft.client.Minecraft;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ReportedException;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.icons.export.IIconSource;
import pl.kuba6000.ae2webintegration.icongenerator.IconGeneratorMod;

/** Incremental native default/subitem discovery plus optional full JEI ingredients and registered fluids. */
final class IconCatalogue implements IIconSource<IconCandidate> {

    private static final Logger LOG = LogManager.getLogger(IconGeneratorMod.MOD_ID);
    private final Minecraft minecraft;
    private final boolean jeiInstalled;
    private boolean useJei;
    private boolean jeiUnavailable;
    private boolean prepared;
    private Iterator<Item> items = Collections.<Item>emptyList()
        .iterator();
    private Iterator<Map.Entry<String, Fluid>> fluids = Collections.<Map.Entry<String, Fluid>>emptyList()
        .iterator();
    private @Nullable JeiCatalogue jei;
    private @Nullable BiConsumer<String, Throwable> failures;
    private Iterator<ItemStack> variants = Collections.<ItemStack>emptyList()
        .iterator();
    private @Nullable Item currentItem;
    private List<CreativeTabs> tabs = Collections.emptyList();
    private int tabIndex;
    private int variantIndex;
    private int jeiIndex;
    private boolean done;
    private String context = "native";
    private int nativeCandidates;
    private int jeiCandidates;
    private int fluidCandidates;
    private int skippedEmpty;

    IconCatalogue(@NotNull Minecraft minecraft) {
        this.minecraft = minecraft;
        jeiInstalled = Loader.isModLoaded("jei");
    }

    @Override
    public @Nullable String prepare(@NotNull BiConsumer<String, Throwable> failures) {
        if (prepared) return null;
        try {
            useJei = jeiInstalled;
            jei = useJei ? JeiCatalogue.ready() : null;
        } catch (LinkageError failure) {
            jeiUnavailable = true;
            useJei = false;
            String warning = "JEI catalogue unavailable (incompatible integration); exporting native items and fluids only";
            LOG.warn(warning, failure);
            if (minecraft.player != null) minecraft.player.sendMessage(new TextComponentString(warning));
        }
        if (useJei && jei == null)
            return "Waiting for JEI to finish loading its full catalogue; /ae2webicons cancel to stop";
        this.failures = failures;
        List<Item> registered = new ArrayList<>();
        ForgeRegistries.ITEMS.forEach(registered::add);
        registered.sort(
            Comparator.comparing(
                item -> item.getRegistryName()
                    .toString()));
        items = registered.iterator();
        fluids = new TreeMap<>(FluidRegistry.getRegisteredFluids()).entrySet()
            .iterator();
        prepared = true;
        return null;
    }

    @Override
    public void verify() {
        if (jei != null) jei.verify();
    }

    @Override
    public @Nullable IconCandidate next() {
        if (variants.hasNext() || currentItem != null || items.hasNext()) return nextNative();
        if (jei != null && jeiIndex < jei.size()) {
            context = "jei/" + jeiIndex;
            jeiCandidates++;
            return jei.candidate(jeiIndex++);
        }
        if (fluids.hasNext()) {
            Map.Entry<String, Fluid> fluid = fluids.next();
            context = "fluid/" + fluid.getKey();
            fluidCandidates++;
            return IconCandidate.fluid(context, new FluidStack(fluid.getValue(), Fluid.BUCKET_VOLUME));
        }
        done = true;
        return null;
    }

    private @Nullable IconCandidate nextNative() {
        if (variants.hasNext()) {
            context = "native/" + currentItem.getRegistryName() + "/" + variantIndex++;
            nativeCandidates++;
            return itemCandidate(variants.next());
        }
        if (currentItem != null) {
            if (tabIndex < tabs.size()) {
                NonNullList<ItemStack> published = NonNullList.create();
                CreativeTabs tab = tabs.get(tabIndex++);
                try {
                    currentItem.getSubItems(tab, published);
                } catch (Throwable failure) {
                    failures.accept(context + "/subtypes", failure);
                }
                variants = published.iterator();
                return null;
            }
            currentItem = null;
        }
        if (!items.hasNext()) return null;
        Item item = items.next();
        context = "native/" + item.getRegistryName();
        tabs = new ArrayList<>();
        for (CreativeTabs tab : item.getCreativeTabs()) {
            if (tab != null && !tabs.contains(tab)) tabs.add(tab);
        }
        if (!tabs.contains(CreativeTabs.SEARCH)) tabs.add(CreativeTabs.SEARCH);
        currentItem = item;
        tabIndex = 0;
        variantIndex = 0;
        nativeCandidates++;
        return itemCandidate(item.getDefaultInstance());
    }

    private @Nullable IconCandidate itemCandidate(@NotNull ItemStack stack) {
        if (stack.isEmpty()) {
            skippedEmpty++;
            return null;
        }
        return IconCandidate.item(context, stack);
    }

    @Override
    public boolean done() {
        return done;
    }

    @Override
    public @NotNull String context() {
        return context;
    }

    @Override
    public @NotNull Map<String, Long> counts() {
        Map<String, Long> counts = new TreeMap<>();
        counts.put("nativeCandidates", (long) nativeCandidates);
        counts.put("jeiCandidates", (long) jeiCandidates);
        counts.put("fluidCandidates", (long) fluidCandidates);
        counts.put("skippedEmpty", (long) skippedEmpty);
        return counts;
    }

    @Override
    public @NotNull List<String> sources() {
        return Arrays.asList("native", useJei ? "jei" : jeiUnavailable ? "jei-unavailable" : "jei-absent", "fluids");
    }

    @Override
    public @NotNull String note() {
        return jeiUnavailable ? " (JEI unavailable; native catalogue only)" : "";
    }

    @Override
    public @NotNull Throwable failureCause(@NotNull Throwable failure) {
        if (failure instanceof JeiCatalogue.Changed changed) throw changed;
        return failure instanceof ReportedException ? failure.getCause() : failure;
    }
}
