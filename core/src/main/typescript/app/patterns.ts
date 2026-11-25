import type { Api, ApiFailure } from './api.js';
import type { IconMetadata, PatternDelta, PatternProvider } from './api-types.js';
import { createViewStream, type IconRemap, type LiveStreamState } from './live-stream.js';

export type PatternsState = {
    status: 'idle' | 'loading' | 'ready' | 'error';
    error: string | null;
    providers: PatternProvider[];
    icons: IconMetadata | null;
    stream: LiveStreamState | null;
};

function mapIcons(provider: PatternProvider, remap: IconRemap): PatternProvider {
    return {
        ...provider,
        icon: provider.icon && remap(provider.icon),
        slots: provider.slots.map((slot) =>
            slot.kind === 'empty'
                ? slot
                : {
                      kind: 'pattern',
                      pattern: {
                          ...slot.pattern,
                          inputs: slot.pattern.inputs.map(remap),
                          outputs: slot.pattern.outputs.map(remap)
                      }
                  }
        )
    };
}

export function createPatterns(api: Api, changed: () => void, wantsIcons: () => boolean, wantsLive: () => boolean) {
    const state: PatternsState = { status: 'idle', error: null, providers: [], icons: null, stream: null };
    let key: string | null = null;
    let request: AbortController | null = null;
    const stream = createViewStream<PatternDelta>({
        enabled: wantsLive,
        icons: () => state.icons,
        status(next) {
            state.stream = next;
        },
        changed,
        resync(reason) {
            const grid = key;
            if (!grid) return;
            // A revoked/reset stream or atlas change invalidates the displayed snapshot immediately.
            if (reason === 'invalidate') clear();
            void load(grid);
        },
        apply(delta, remap, icons) {
            if (delta.removed.length || delta.providers.length) {
                const rows = new Map(state.providers.map((row) => [row.key, row]));
                for (const removed of delta.removed) rows.delete(removed);
                for (const provider of delta.providers) rows.set(provider.key, mapIcons(provider, remap));
                state.providers = [...rows.values()];
            }
            if (icons) state.icons = icons;
            return true;
        }
    });

    function invalidateIcons() {
        request?.abort();
        request = null;
        stream.stop();
        state.icons = null;
    }
    function clear() {
        invalidateIcons();
        key = null;
        state.status = 'idle';
        state.error = null;
        state.providers = [];
    }
    function block(status: string) {
        clear();
        state.status = 'error';
        state.error = status;
    }
    async function load(gridKey: string, automatic = false) {
        if (key !== gridKey) clear();
        else if (automatic && (stream.active || stream.reconnecting || request)) return;
        stream.stop();
        request?.abort();
        key = gridKey;
        const current = new AbortController();
        request = current;
        if (state.status !== 'ready') state.status = 'loading';
        state.error = null;
        changed();
        try {
            const withIcons = wantsIcons();
            const snapshot = await api.patternProviders(gridKey, current.signal, withIcons, wantsLive());
            if (current.signal.aborted) return;
            state.providers = snapshot.data;
            state.icons = snapshot.icons;
            state.status = 'ready';
            stream.start(
                snapshot,
                (token, handlers) => api.patternEvents(gridKey, token, withIcons, handlers),
                () => key === gridKey && !current.signal.aborted
            );
        } catch (error) {
            if (current.signal.aborted) return;
            block((error as ApiFailure).status ?? 'NETWORK_ERROR');
        } finally {
            if (request === current) request = null;
        }
        changed();
    }
    return { state, load, clear, block, invalidateIcons, stopLive: stream.stop };
}
