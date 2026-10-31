package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.ItemStack;

import org.jetbrains.annotations.NotNull;

import mezz.jei.api.ingredients.IIngredientRegistry;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.recipe.IIngredientType;

/** JEI's public recipe ingredient receiver, with slot alternatives retained until traversal. */
final class JeiRecipeIngredients implements IIngredients {

    private final IIngredientRegistry registry;
    private final Map<IIngredientType<?>, List<?>> inputs = new IdentityHashMap<>();
    private final Map<IIngredientType<?>, List<?>> outputs = new IdentityHashMap<>();

    JeiRecipeIngredients(@NotNull IIngredientRegistry registry) {
        this.registry = registry;
    }

    private <T> void set(Map<IIngredientType<?>, List<?>> target, IIngredientType<T> type, List<List<T>> slots) {
        List<List<T>> expanded = new ArrayList<>();
        for (List<T> slot : slots) {
            List<T> expandable = new ArrayList<>(slot.size());
            List<T> nonconsumables = new ArrayList<>();
            for (T ingredient : slot) {
                // JEI drops empty-count inputs. Keep concrete originals until identity is captured.
                if (ingredient instanceof ItemStack stack && stack.getCount() <= 0 && stack != ItemStack.EMPTY) {
                    nonconsumables.add(ingredient);
                } else {
                    expandable.add(ingredient);
                }
            }
            List<T> alternatives = new ArrayList<>(
                registry.getIngredientHelper(type)
                    .expandSubtypes(expandable));
            alternatives.addAll(nonconsumables);
            expanded.add(alternatives);
        }
        target.put(type, expanded);
    }

    private static <T> List<List<T>> slots(List<T> values) {
        List<List<T>> slots = new ArrayList<>();
        for (T value : values) slots.add(Collections.singletonList(value));
        return slots;
    }

    @SuppressWarnings("unchecked") // Each entry is written using the same typed ingredient key.
    private static <T> List<List<T>> get(Map<IIngredientType<?>, List<?>> values, IIngredientType<T> type) {
        return (List<List<T>>) values.getOrDefault(type, Collections.emptyList());
    }

    @Override
    public <T> void setInput(@NotNull IIngredientType<T> type, @NotNull T value) {
        setInputs(type, Collections.singletonList(value));
    }

    @Override
    public <T> void setInputs(@NotNull IIngredientType<T> type, @NotNull List<T> values) {
        setInputLists(type, slots(values));
    }

    @Override
    public <T> void setInputLists(@NotNull IIngredientType<T> type, @NotNull List<List<T>> values) {
        set(inputs, type, values);
    }

    @Override
    public <T> void setOutput(@NotNull IIngredientType<T> type, @NotNull T value) {
        setOutputs(type, Collections.singletonList(value));
    }

    @Override
    public <T> void setOutputs(@NotNull IIngredientType<T> type, @NotNull List<T> values) {
        setOutputLists(type, slots(values));
    }

    @Override
    public <T> void setOutputLists(@NotNull IIngredientType<T> type, @NotNull List<List<T>> values) {
        set(outputs, type, values);
    }

    @Override
    public <T> @NotNull List<List<T>> getInputs(@NotNull IIngredientType<T> type) {
        return get(inputs, type);
    }

    @Override
    public <T> @NotNull List<List<T>> getOutputs(@NotNull IIngredientType<T> type) {
        return get(outputs, type);
    }

    @Override
    public <T> void setInput(@NotNull Class<? extends T> type, @NotNull T value) {
        setInput(registry.getIngredientType(type), value);
    }

    @Override
    public <T> void setInputs(@NotNull Class<? extends T> type, @NotNull List<T> values) {
        setInputs(registry.getIngredientType(type), values);
    }

    @Override
    public <T> void setInputLists(@NotNull Class<? extends T> type, @NotNull List<List<T>> values) {
        setInputLists(registry.getIngredientType(type), values);
    }

    @Override
    public <T> void setOutput(@NotNull Class<? extends T> type, @NotNull T value) {
        setOutput(registry.getIngredientType(type), value);
    }

    @Override
    public <T> void setOutputs(@NotNull Class<? extends T> type, @NotNull List<T> values) {
        setOutputs(registry.getIngredientType(type), values);
    }

    @Override
    public <T> void setOutputLists(@NotNull Class<? extends T> type, @NotNull List<List<T>> values) {
        setOutputLists(registry.getIngredientType(type), values);
    }

    @Override
    public <T> @NotNull List<List<T>> getInputs(@NotNull Class<? extends T> type) {
        return getInputs(registry.getIngredientType(type));
    }

    @Override
    public <T> @NotNull List<List<T>> getOutputs(@NotNull Class<? extends T> type) {
        return getOutputs(registry.getIngredientType(type));
    }
}
