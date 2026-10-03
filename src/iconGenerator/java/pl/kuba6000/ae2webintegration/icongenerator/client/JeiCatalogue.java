package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import mezz.jei.api.ingredients.IIngredientRegistry;
import mezz.jei.api.ingredients.VanillaTypes;

/** Full public ingredient snapshot, taken after JEI's runtime callback on the client thread. */
final class JeiCatalogue {

    private static @Nullable IIngredientRegistry registry;
    private static boolean ready;
    private static long generation;
    private final long capturedGeneration;
    private final List<ItemStack> items;
    private final List<FluidStack> fluids;

    private JeiCatalogue(@NotNull IIngredientRegistry ingredients) {
        capturedGeneration = generation;
        items = new ArrayList<>(ingredients.getAllIngredients(VanillaTypes.ITEM));
        fluids = new ArrayList<>(ingredients.getAllIngredients(VanillaTypes.FLUID));
    }

    static void invalidate() {
        ready = false;
        registry = null;
        generation++;
    }

    static void register(@NotNull IIngredientRegistry ingredients) {
        registry = ingredients;
    }

    static void available() {
        ready = true;
    }

    static @Nullable JeiCatalogue ready() {
        return ready && registry != null ? new JeiCatalogue(registry) : null;
    }

    void verify() {
        if (!ready || capturedGeneration != generation) throw new Changed();
    }

    int size() {
        return items.size() + fluids.size();
    }

    @Nullable
    IconCandidate candidate(int index) {
        if (index < items.size()) return IconCandidate.item("jei/item/" + index, items.get(index));
        return IconCandidate.fluid("jei/fluid/" + (index - items.size()), fluids.get(index - items.size()));
    }

    static final class Changed extends IllegalStateException {

        Changed() {
            super("JEI catalogue changed during export; start a new export after reload completes");
        }
    }
}
