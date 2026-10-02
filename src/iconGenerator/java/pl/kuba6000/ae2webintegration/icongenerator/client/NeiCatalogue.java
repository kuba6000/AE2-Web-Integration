package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.item.ItemStack;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import codechicken.nei.ItemList;
import codechicken.nei.ItemListLoader;
import codechicken.nei.NEIClientConfig;
import codechicken.nei.RestartableTask;

/** Isolated optional bridge for the GTNH NEI catalogue, independent of visible search results. */
final class NeiCatalogue {

    // RestartableTask has no completion accessor. Its start/finish publication uses its own monitor.
    private static final Field TASK_THREAD = taskThread();
    private static final RestartableTask[] TASKS = { ItemListLoader.loadItems, ItemListLoader.warmTooltips,
        ItemListLoader.refreshItems, ItemListLoader.updateFilter };
    private final List<ItemStack> items;

    private NeiCatalogue(@NotNull List<ItemStack> items) {
        this.items = items;
    }

    static @Nullable NeiCatalogue ready() {
        return locked(0, () -> idle() && ItemList.loadFinished ? new NeiCatalogue(ItemList.items) : null);
    }

    static boolean disabled() {
        return locked(0, () -> NEIClientConfig.isLoaded() && !NEIClientConfig.isEnabled());
    }

    int size() {
        return items.size();
    }

    void verify() {
        locked(0, () -> {
            checkGeneration();
            return null;
        });
    }

    @NotNull
    ItemStack copy(int index) {
        return locked(0, () -> {
            checkGeneration();
            ItemStack item = items.get(index);
            if (item == null) throw new IllegalArgumentException("NEI catalogue contains a null item");
            return item.copy();
        });
    }

    private void checkGeneration() {
        if (!idle() || !ItemList.loadFinished || ItemList.items != items) {
            throw new Changed();
        }
    }

    static final class Changed extends IllegalStateException {

        private Changed() {
            super("NEI catalogue changed or restarted; retry export after it finishes");
        }
    }

    private static boolean idle() {
        try {
            for (RestartableTask task : TASKS) {
                if (TASK_THREAD.get(task) != null) return false;
            }
            return true;
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot inspect NEI catalogue completion", exception);
        }
    }

    private static <T> T locked(int index, Supplier<T> operation) {
        if (index == TASKS.length) return operation.get();
        synchronized (TASKS[index]) {
            return locked(index + 1, operation);
        }
    }

    private static @NotNull Field taskThread() {
        try {
            Field field = RestartableTask.class.getDeclaredField("thread");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unsupported NEI catalogue lifecycle", exception);
        }
    }
}
