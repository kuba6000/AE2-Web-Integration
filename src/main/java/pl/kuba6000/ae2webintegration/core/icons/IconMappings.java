package pl.kuba6000.ae2webintegration.core.icons;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.identity.ItemIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** One response's compact page table, built on the server thread from one immutable pack snapshot. */
public final class IconMappings {

    public final @NotNull String packId;
    public final int width = IconPack.CONTENT_SIZE;
    public final int height = IconPack.CONTENT_SIZE;
    public final @NotNull List<Page> pages = new ArrayList<>();
    private final transient IconPack pack;
    private final transient Map<String, Integer> pageIndexes = new HashMap<>();

    @Desugar
    public record Page(@NotNull String digest, int width, int height) {}

    @Desugar
    public record Reference(int page, int x, int y) {}

    public IconMappings(@NotNull IconPack pack) {
        this.pack = pack;
        this.packId = pack.packId();
    }

    /** Exact matches never ask the native adapter to construct a base identity. */
    public @Nullable Reference resolve(@NotNull StableKey key, @NotNull ItemIdentityRegistry registry) {
        IconPack.Location location = pack.find(key);
        if (location == null) {
            StableKey base = registry.resolveIconBase(key);
            if (base != null) location = pack.find(base);
        }
        if (location == null) return null;
        IconPack.Page page = location.page;
        Integer index = pageIndexes.get(page.digest);
        if (index == null) {
            index = pages.size();
            pages.add(new Page(page.digest, page.width, page.height));
            pageIndexes.put(page.digest, index);
        }
        return new Reference(index, location.x, location.y);
    }
}
