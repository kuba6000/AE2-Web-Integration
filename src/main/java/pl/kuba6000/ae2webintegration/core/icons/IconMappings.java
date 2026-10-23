package pl.kuba6000.ae2webintegration.core.icons;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.identity.ItemIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/**
 * One response's compact page table, built by its owner from an immutable pack snapshot. After construction,
 * ownership may pass to a serialization thread; no further resolve calls or page mutations are allowed.
 */
public final class IconMappings {

    public final @NotNull String packId;
    public final int width = IconPack.CONTENT_SIZE;
    public final int height = IconPack.CONTENT_SIZE;
    public final @NotNull List<Page> pages = new ArrayList<>();
    public final @NotNull List<Animation> animations = new ArrayList<>();
    private final transient IconPack pack;
    private final transient Map<String, Integer> pageIndexes = new HashMap<>();
    // Pack loading canonicalizes sequences; identity lookup avoids hashing frames on request threads.
    private final transient Map<IconPack.Animation, Integer> animationIndexes = new IdentityHashMap<>();

    @Desugar
    public record Page(@NotNull String digest, int width, int height) {}

    /**
     * @param page      static fallback page index in this response's page table
     * @param animation index in this response's animation table, or null for a static icon
     */
    @Desugar
    public record Reference(int page, int x, int y, @Nullable Integer animation) {}

    /** One page contains every frame; coordinates are 64-pixel crops and durations are milliseconds. */
    @Desugar
    public record Animation(int page, @NotNull List<IconPack.Frame> frames) {}

    public IconMappings(@NotNull IconPack pack) {
        this.pack = pack;
        this.packId = pack.packId();
    }

    /** Server-thread lookup: exact matches never ask the native adapter to construct a base identity. */
    public @Nullable Reference resolve(@NotNull StableKey key, @NotNull ItemIdentityRegistry registry) {
        IconPack.Location location = pack.find(key);
        if (location != null) return reference(key, location);
        StableKey base = registry.resolveBaseKey(key);
        return base == null ? null : reference(base, pack.find(base));
    }

    /** Detached lookup safe for asynchronous history responses; exact identity takes precedence. */
    public @Nullable Reference resolve(@NotNull StableKey key, @Nullable StableKey base) {
        IconPack.Location location = pack.find(key);
        if (location != null) return reference(key, location);
        return base == null ? null : reference(base, pack.find(base));
    }

    private @Nullable Reference reference(@NotNull StableKey key, @Nullable IconPack.Location location) {
        if (location == null) return null;
        int page = pageIndex(location.page());
        return new Reference(page, location.x(), location.y(), animationIndex(pack.animation(key)));
    }

    private @Nullable Integer animationIndex(@Nullable IconPack.Animation sequence) {
        if (sequence == null) return null;
        Integer index = animationIndexes.get(sequence);
        if (index != null) return index;
        index = animations.size();
        animations.add(new Animation(pageIndex(sequence.page()), sequence.frames()));
        animationIndexes.put(sequence, index);
        return index;
    }

    private int pageIndex(@NotNull IconPack.Page page) {
        Integer index = pageIndexes.get(page.digest());
        if (index == null) {
            index = pages.size();
            pages.add(new Page(page.digest(), page.width(), page.height()));
            pageIndexes.put(page.digest(), index);
        }
        return index;
    }
}
