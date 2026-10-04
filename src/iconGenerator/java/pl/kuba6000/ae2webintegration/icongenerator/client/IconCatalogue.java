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

import net.minecraft.ReportedException;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.icons.export.IIconSource;
import pl.kuba6000.ae2webintegration.icongenerator.IconGeneratorMod;

/** Native registries and creative display/search entries, optionally unioned with a completed EMI index. */
final class IconCatalogue implements IIconSource<IconCandidate> {

    private static final Logger LOG = LogManager.getLogger(IconGeneratorMod.MOD_ID);
    private final Minecraft minecraft;
    private final boolean emiInstalled;
    private boolean useEmi;
    private boolean emiUnavailable;
    private boolean prepared;

    private Iterator<Item> items = Collections.<Item>emptyList()
        .iterator();
    private Iterator<Fluid> fluids = Collections.emptyIterator();
    private Iterator<CreativeModeTab> tabs = Collections.emptyIterator();
    private final FeatureFlagSet features;
    private @Nullable EmiCatalogue emi;
    private Iterator<ItemStack> variants = Collections.emptyIterator();
    private Iterator<ItemStack> searchVariants = Collections.emptyIterator();
    private long creativeRebuildNanos;
    private boolean done;
    private int emiIndex;
    private int nativeCandidates;
    private int creativeCandidates;
    private int emiCandidates;
    private int fluidCandidates;
    private int skippedEmpty;
    private int skippedDisabled;
    private String context = "native";

    IconCatalogue(@NotNull Minecraft minecraft) {
        this.minecraft = minecraft;
        emiInstalled = ModList.get()
            .isLoaded("emi");
        features = minecraft.level.enabledFeatures();
    }

    @Override
    public @Nullable String prepare(@NotNull BiConsumer<String, Throwable> failures) {
        if (prepared) return null;
        try {
            useEmi = emiInstalled;
            emi = useEmi ? EmiCatalogue.ready() : null;
            if (useEmi && emi == null && EmiCatalogue.failed()) {
                throw new IllegalStateException(
                    "EMI reload failed or is retrying; start a new export after EMI reload completes");
            }
        } catch (LinkageError failure) {
            emiUnavailable = true;
            useEmi = false;
            String warning = "EMI catalogue unavailable (incompatible integration); exporting native items and fluids only";
            LOG.warn(warning, failure);
            if (minecraft.player != null) minecraft.player.displayClientMessage(Component.literal(warning), false);
        }
        if (useEmi && emi == null) {
            return "Waiting for EMI to finish loading its full catalogue; /ae2webicons cancel to stop";
        }
        initialize();
        prepared = true;
        return null;
    }

    private void initialize() {
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

    @Override
    public void verify() {
        if (emi != null) emi.verify();
    }

    @Override
    @Nullable
    public IconCandidate next() {
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
            return new IconCandidate(context, new FluidStack(fluid, 1));
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
        counts.put("emiCandidates", (long) emiCandidates);
        counts.put("fluidCandidates", (long) fluidCandidates);
        counts.put("skippedEmpty", (long) skippedEmpty);
        counts.put("skippedFeatureDisabled", (long) skippedDisabled);
        counts.put("creativeRebuildNanos", creativeRebuildNanos);
        return counts;
    }

    @Override
    public @NotNull List<String> sources() {
        String emiSource = useEmi ? "emi" : emiUnavailable ? "emi-unavailable" : "emi-absent";
        return Arrays.asList("native", "creative", emiSource, "fluids");
    }

    @Override
    public @NotNull String note() {
        if (emiUnavailable) return " (EMI unavailable; native catalogue only)";
        return "";
    }

    @Override
    public @NotNull Throwable failureCause(@NotNull Throwable failure) {
        if (failure instanceof EmiCatalogue.Changed changed) throw changed;
        return failure instanceof ReportedException ? failure.getCause() : failure;
    }
}
