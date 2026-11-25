package pl.kuba6000.ae2webintegration.core.patterns;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.http.IResponseBody;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.live.LiveSubscription;
import pl.kuba6000.ae2webintegration.core.live.LiveTopic;
import pl.kuba6000.ae2webintegration.core.live.LiveUpdate;
import pl.kuba6000.ae2webintegration.core.live.LiveView;

/** One network's complete provider rows. The hub only receives detached changes from the reader. */
public final class PatternProviderTopic extends LiveTopic {

    private final Map<StableKey, PatternProvider> rows = new LinkedHashMap<>();

    PatternProviderTopic(StableKey grid, long generation) {
        super(keyOf(grid), generation);
    }

    public static @NotNull Key keyOf(@NotNull StableKey grid) {
        return new Key(PatternProviderTopic.class, grid, null);
    }

    void observed(PatternProviderReader.Delta delta) {
        Set<StableKey> changed = new HashSet<>(delta.removed());
        changed.addAll(
            delta.replacements()
                .keySet());
        if (changed.isEmpty() && initialized()) return;
        rows.keySet()
            .removeAll(delta.removed());
        rows.putAll(delta.replacements());
        markInitialized();
        publish(changed, Collections.emptySet(), false, Collections.emptySet(), false);
    }

    List<PatternProvider> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(rows.values()));
    }

    @Override
    protected void invalidate() {
        super.invalidate();
        rows.clear();
    }

    @Override
    protected @NotNull LiveUpdate drain(@NotNull LiveSubscription.Pending pending) {
        List<PatternProvider> updated = new ArrayList<>();
        List<StableKey> removed = new ArrayList<>();
        for (StableKey key : pending.changed()) {
            PatternProvider row = rows.get(key);
            if (row == null) removed.add(key);
            else updated.add(row);
        }
        return new Update(view(), updated, removed);
    }

    @Desugar
    private record Update(LiveView view, List<PatternProvider> providers, List<StableKey> removed)
        implements LiveUpdate {

        public boolean describes() {
            return !providers.isEmpty();
        }

        public @NotNull IResponseBody body(@Nullable IconMappings icons) {
            return new Delta(view, PatternProviderView.rows(providers, icons), removed, icons);
        }
    }

    /**
     * @param view      updated snapshot version
     * @param providers full replacement rows, including newly added providers
     * @param removed   keys of providers no longer present
     * @param icons     icon atlas metadata for this event; null when absent
     */
    @Desugar
    public record Delta(@NotNull LiveView view, @NotNull List<PatternProviderView> providers,
        @NotNull List<StableKey> removed, @Nullable IconMappings icons) implements IResponseBody {}
}
