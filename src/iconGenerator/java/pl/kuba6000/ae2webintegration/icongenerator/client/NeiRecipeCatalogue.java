package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.OreDictionary;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.GuiCraftingRecipe;
import codechicken.nei.recipe.GuiUsageRecipe;
import codechicken.nei.recipe.ICraftingHandler;
import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.IUsageHandler;
import codechicken.nei.recipe.RecipeCatalysts;
import codechicken.nei.recipe.StackInfo;
import codechicken.nei.recipe.TemplateRecipeHandler;

/** Client-thread traversal of the concrete recipe stacks exposed by registered NEI handlers. */
final class NeiRecipeCatalogue {

    private final List<IRecipeHandler> handlers = new ArrayList<>();
    private int handlerIndex;
    private @Nullable IRecipeHandler registered;
    private @Nullable IRecipeHandler loaded;
    private int handlerStage;
    private int recipeCount;
    private int recipeIndex;
    private int role;
    private List<PositionedStack> slots = Collections.emptyList();
    private int slotIndex;
    private ItemStack[] alternatives = new ItemStack[0];
    private int alternativeIndex;
    private @Nullable ItemStack fluidItem;
    private String context = "nei-recipes";
    private String handlerContext = "nei-recipes";
    private String slotContext = "nei-recipes";
    private String roleContext = "nei-recipes";
    private long handlersLoaded;
    private long recipesVisited;
    private long itemCandidates;
    private long fluidCandidates;
    private long skipped;
    private long maxLoadNanos;

    NeiRecipeCatalogue() {
        Set<IRecipeHandler> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        append(GuiCraftingRecipe.craftinghandlers, seen);
        append(GuiCraftingRecipe.serialCraftingHandlers, seen);
        append(GuiUsageRecipe.usagehandlers, seen);
        append(GuiUsageRecipe.serialUsageHandlers, seen);
    }

    private void append(List<? extends IRecipeHandler> source, Set<IRecipeHandler> seen) {
        synchronized (source) {
            for (IRecipeHandler handler : source) {
                // GT registers different recipe-map instances with the same class/handler ID.
                if (seen.add(handler)) handlers.add(handler);
            }
        }
    }

    boolean done() {
        return registered == null && handlerIndex >= handlers.size() && fluidItem == null;
    }

    @NotNull
    String context() {
        return context;
    }

    @Nullable
    IconCandidate next() {
        if (fluidItem != null) {
            ItemStack stack = fluidItem;
            fluidItem = null;
            context += "/fluid";
            FluidStack fluid = StackInfo.getFluid(stack);
            if (fluid == null || fluid.getFluid() == null) return null;
            FluidStack copy = fluid.copy();
            copy.amount = FluidContainerRegistry.BUCKET_VOLUME;
            fluidCandidates++;
            return IconCandidate.fluid(context, copy);
        }
        if (alternativeIndex < alternatives.length) {
            context = slotContext + "/" + alternativeIndex;
            ItemStack stack = alternatives[alternativeIndex++];
            if (stack == null || stack.getItem() == null
                || Items.blaze_rod.getDamage(stack) == OreDictionary.WILDCARD_VALUE) {
                skipped++;
                return null;
            }
            // Decode on the next step even when this item's identity/rendering is unsupported.
            fluidItem = stack.copy();
            itemCandidates++;
            return IconCandidate.item(context, stack.copy());
        }
        if (slotIndex < slots.size()) {
            slotContext = context = roleContext + "/slot/" + slotIndex;
            PositionedStack slot = slots.get(slotIndex++);
            alternatives = new ItemStack[0];
            alternativeIndex = 0;
            if (slot != null) alternatives = Objects.requireNonNull(slot.items, "NEI recipe alternatives");
            return null;
        }
        if (registered == null) {
            if (handlerIndex >= handlers.size()) return null;
            int index = handlerIndex++;
            registered = handlers.get(index);
            handlerStage = 0;
            loaded = null;
            recipeCount = 0;
            recipeIndex = 0;
            role = 0;
            handlerContext = context = "nei-recipes/handler/" + index;
            String name = registered.getClass()
                .getName() + "/"
                + registered.getOverlayIdentifier();
            handlerContext += "/" + name.substring(0, Math.min(name.length(), 160));
            return null;
        }
        if (handlerStage == 0) {
            handlerStage++;
            roleContext = context = handlerContext + "/catalysts";
            slots = Collections.emptyList();
            slotIndex = 0;
            slots = Objects.requireNonNull(RecipeCatalysts.getRecipeCatalysts(registered), "NEI recipe catalysts");
            return null;
        }
        if (handlerStage == 1) {
            handlerStage++;
            context = handlerContext + "/load";
            long start = System.nanoTime();
            try {
                if (registered instanceof TemplateRecipeHandler template) loaded = template.getAllRecipeHandler();
                else if (registered instanceof ICraftingHandler crafting) loaded = crafting.getRecipeHandler("all");
                else if (registered instanceof IUsageHandler usage) loaded = usage.getUsageHandler("all");
                if (loaded != null) {
                    recipeCount = loaded.numRecipes();
                    handlersLoaded++;
                }
            } finally {
                maxLoadNanos = Math.max(maxLoadNanos, System.nanoTime() - start);
            }
            return null;
        }
        if (loaded == null || recipeIndex >= recipeCount) {
            registered = null;
            loaded = null;
            return null;
        }
        int currentRole = role++;
        roleContext = context = handlerContext + "/recipe/" + recipeIndex + "/role/" + currentRole;
        slots = Collections.emptyList();
        slotIndex = 0;
        if (currentRole == 0) {
            recipesVisited++;
            slots = Objects.requireNonNull(loaded.getIngredientStacks(recipeIndex), "NEI recipe inputs");
        } else if (currentRole == 1) {
            PositionedStack result = loaded.getResultStack(recipeIndex);
            slots = result == null ? Collections.emptyList() : Collections.singletonList(result);
        } else if (currentRole == 2) {
            slots = Objects.requireNonNull(loaded.getOtherStacks(recipeIndex), "NEI recipe extra stacks");
        } else {
            recipeIndex++;
            role = 0;
        }
        return null;
    }

    void counts(@NotNull Map<String, Long> counts) {
        counts.put("neiRecipeHandlers", (long) handlers.size());
        counts.put("neiRecipeHandlersLoaded", handlersLoaded);
        counts.put("neiRecipesVisited", recipesVisited);
        counts.put("neiRecipeItemCandidates", itemCandidates);
        counts.put("neiRecipeFluidCandidates", fluidCandidates);
        counts.put("neiRecipeSkipped", skipped);
        counts.put("neiRecipeMaxLoadNanos", maxLoadNanos);
    }
}
