package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import mezz.jei.api.IRecipeRegistry;
import mezz.jei.api.ingredients.IIngredientRegistry;
import mezz.jei.api.ingredients.VanillaTypes;
import mezz.jei.api.recipe.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeWrapper;

/** Incremental traversal of the recipes and catalysts exposed by JEI's public runtime API. */
final class JeiRecipeCatalogue {

    private final IRecipeRegistry registry;
    private final IIngredientRegistry ingredients;
    private List<IRecipeCategory<?>> categories = Collections.emptyList();
    private boolean initialized;
    private int categoryIndex;
    private @Nullable IRecipeCategory<?> category;
    private int categoryStage;
    private List<? extends IRecipeWrapper> wrappers = Collections.emptyList();
    private int wrapperIndex;
    private @Nullable JeiRecipeIngredients recipe;
    private int role;
    private List<? extends List<?>> slots = Collections.emptyList();
    private int slotIndex;
    private List<?> alternatives = Collections.emptyList();
    private int alternativeIndex;
    private String context = "jei-recipes";
    private String categoryContext = "jei-recipes";
    private String roleContext = "jei-recipes";
    private String slotContext = "jei-recipes";
    private long recipesVisited;
    private long itemCandidates;
    private long fluidCandidates;
    private long skipped;
    private long maxLoadNanos;

    JeiRecipeCatalogue(@NotNull IRecipeRegistry registry, @NotNull IIngredientRegistry ingredients) {
        this.registry = registry;
        this.ingredients = ingredients;
    }

    boolean done() {
        return initialized && category == null && categoryIndex >= categories.size();
    }

    @NotNull
    String context() {
        return context;
    }

    @Nullable
    IconCandidate next() {
        if (!initialized) {
            initialized = true;
            categories = new ArrayList<>();
            for (IRecipeCategory<?> value : registry.getRecipeCategories()) categories.add(value);
            return null;
        }
        if (alternativeIndex < alternatives.size()) {
            context = slotContext + "/" + alternativeIndex;
            Object value = alternatives.get(alternativeIndex++);
            if (value instanceof ItemStack stack) {
                IconCandidate candidate = IconCandidate.recipeItem(context, stack);
                if (candidate == null) skipped++;
                else itemCandidates++;
                return candidate;
            }
            if (value instanceof FluidStack stack && stack.getFluid() != null) {
                FluidStack copy = stack.copy();
                copy.amount = Fluid.BUCKET_VOLUME;
                fluidCandidates++;
                return IconCandidate.fluid(context, copy);
            }
            skipped++;
            return null;
        }
        if (slotIndex < slots.size()) {
            slotContext = context = roleContext + "/slot/" + slotIndex;
            alternatives = Collections.emptyList();
            alternativeIndex = 0;
            alternatives = Objects.requireNonNull(slots.get(slotIndex++), "JEI recipe alternatives");
            return null;
        }
        if (recipe != null) {
            int currentRole = role++;
            roleContext = context = categoryContext + "/recipe/" + (wrapperIndex - 1) + "/role/" + currentRole;
            slots = Collections.emptyList();
            slotIndex = 0;
            if (currentRole == 0) slots = recipe.getInputs(VanillaTypes.ITEM);
            else if (currentRole == 1) slots = recipe.getOutputs(VanillaTypes.ITEM);
            else if (currentRole == 2) slots = recipe.getInputs(VanillaTypes.FLUID);
            else if (currentRole == 3) slots = recipe.getOutputs(VanillaTypes.FLUID);
            else recipe = null;
            return null;
        }
        if (category == null) {
            if (categoryIndex >= categories.size()) return null;
            int index = categoryIndex++;
            category = categories.get(index);
            categoryStage = 0;
            wrappers = Collections.emptyList();
            wrapperIndex = 0;
            categoryContext = context = "jei-recipes/category/" + index;
            String uid = category.getUid();
            categoryContext += "/" + uid.substring(0, Math.min(uid.length(), 160));
            return null;
        }
        if (categoryStage == 0) {
            categoryStage++;
            roleContext = context = categoryContext + "/catalysts";
            alternatives = Collections.emptyList();
            alternativeIndex = 0;
            slotContext = roleContext;
            alternatives = Objects.requireNonNull(registry.getRecipeCatalysts(category), "JEI recipe catalysts");
            return null;
        }
        if (categoryStage == 1) {
            categoryStage++;
            context = categoryContext + "/load";
            long start = System.nanoTime();
            try {
                wrappers = Objects.requireNonNull(registry.getRecipeWrappers(category), "JEI recipe wrappers");
            } finally {
                maxLoadNanos = Math.max(maxLoadNanos, System.nanoTime() - start);
            }
            return null;
        }
        if (wrapperIndex < wrappers.size()) {
            int index = wrapperIndex++;
            context = categoryContext + "/recipe/" + index;
            recipesVisited++;
            JeiRecipeIngredients next = new JeiRecipeIngredients(ingredients);
            wrappers.get(index)
                .getIngredients(next);
            recipe = next;
            role = 0;
            return null;
        }
        category = null;
        return null;
    }

    void counts(@NotNull Map<String, Long> counts) {
        counts.put("jeiRecipeCategories", (long) categories.size());
        counts.put("jeiRecipesVisited", recipesVisited);
        counts.put("jeiRecipeItemCandidates", itemCandidates);
        counts.put("jeiRecipeFluidCandidates", fluidCandidates);
        counts.put("jeiRecipeSkipped", skipped);
        counts.put("jeiRecipeMaxLoadNanos", maxLoadNanos);
    }
}
