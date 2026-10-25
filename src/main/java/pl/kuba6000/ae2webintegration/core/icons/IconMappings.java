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

/** Icon atlas pages referenced by resources in this response. */
public final class IconMappings {

    // Build from one immutable pack snapshot; do not mutate after handing off for serialization.
    /** Icon pack identifier used in atlas page URLs. */
    public final @NotNull String packId;
    /** Width of each resource icon in pixels. */
    public final int width = IconPack.CONTENT_SIZE;
    /** Height of each resource icon in pixels. */
    public final int height = IconPack.CONTENT_SIZE;
    /** Atlas pages, indexed by each resource's icon reference. */
    public final @NotNull List<Page> pages = new ArrayList<>();
    private final transient IconPack pack;
    private final transient Map<String, Integer> pageIndexes = new HashMap<>();

    /**
     * An atlas image.
     *
     * @param digest page identifier used in the atlas page URL
     * @param width  image width in pixels
     * @param height image height in pixels
     */
    @Desugar
    public record Page(@NotNull String digest, int width, int height) {}

    /**
     * Location of an icon within an atlas image.
     *
     * @param page zero-based index in this response's pages array
     * @param x    left edge in pixels
     * @param y    top edge in pixels
     */
    @Desugar
    public record Reference(int page, int x, int y) {}

    public IconMappings(@NotNull IconPack pack) {
        this.pack = pack;
        this.packId = pack.packId();
    }

    /** Server-thread lookup: exact matches never ask the native adapter to construct a base identity. */
    public @Nullable Reference resolve(@NotNull StableKey key, @NotNull ItemIdentityRegistry registry) {
        IconPack.Location location = pack.find(key);
        if (location == null) {
            StableKey base = registry.resolveBaseKey(key);
            if (base != null) location = pack.find(base);
        }
        return reference(location);
    }

    /** Detached lookup safe for asynchronous history responses; exact identity takes precedence. */
    public @Nullable Reference resolve(@NotNull StableKey key, @Nullable StableKey base) {
        IconPack.Location location = pack.find(key);
        if (location == null && base != null) location = pack.find(base);
        return reference(location);
    }

    private @Nullable Reference reference(@Nullable IconPack.Location location) {
        if (location == null) return null;
        IconPack.Page page = location.page();
        Integer index = pageIndexes.get(page.digest());
        if (index == null) {
            index = pages.size();
            pages.add(new Page(page.digest(), page.width(), page.height()));
            pageIndexes.put(page.digest(), index);
        }
        return new Reference(index, location.x(), location.y());
    }
}
