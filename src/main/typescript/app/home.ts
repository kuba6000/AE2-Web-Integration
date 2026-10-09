import type { CpuInfo, Grid, IconMetadata } from './api-types.js';
import type { Api, ApiFailure } from './api.js';

export type HomeNetwork = {
    grid: Grid;
    status: 'loading' | 'ready' | 'error';
    cpus: (CpuInfo & { key: string })[];
    icons: IconMetadata | null;
    error: string | null;
};
export type HomeState = { networks: HomeNetwork[] };

/** Cross-network summaries; the application supplies discovery and owns the refresh timer. */
export function createHomeMonitor(api: Api, changed: () => void, iconsEnabled: () => boolean) {
    const state: HomeState = { networks: [] };
    let active = false;
    let generation = 0;
    let request: AbortController | undefined;
    let reading = false;

    function invalidate() {
        generation++;
        request?.abort();
        reading = false;
    }

    return {
        state,
        route(isHome: boolean) {
            invalidate();
            active = isHome;
            state.networks = [];
        },
        clear() {
            invalidate();
            state.networks = [];
        },
        invalidateIcons() {
            invalidate();
            for (const network of state.networks) network.icons = null;
        },
        async refresh(grids: Grid[]) {
            if (!active) return;
            const keys = new Set(grids.map((grid) => grid.key));
            if (
                reading &&
                (state.networks.length !== grids.length ||
                    state.networks.some((network) => !keys.has(network.grid.key)))
            )
                invalidate();
            if (reading) return;
            const version = generation;
            const controller = new AbortController();
            request = controller;
            reading = true;
            const previous = new Map(state.networks.map((network) => [network.grid.key, network]));
            state.networks = grids.map((grid) => ({
                ...(previous.get(grid.key) || { status: 'loading', cpus: [], icons: null, error: null }),
                grid
            }));
            changed();
            let next = 0;
            const pending = state.networks;
            async function worker() {
                while (version === generation && next < pending.length) {
                    const network = pending[next++];
                    try {
                        const result = await api.cpus(network.grid.key, controller.signal, iconsEnabled());
                        if (version !== generation) return;
                        Object.assign(network, {
                            status: 'ready',
                            cpus: Object.entries(result.data).map(([key, cpu]) => ({ ...cpu, key })),
                            icons: result.icons,
                            error: null
                        });
                    } catch (caught) {
                        const error = caught as ApiFailure;
                        if (version !== generation || error.name === 'AbortError') return;
                        Object.assign(network, {
                            status: 'error',
                            cpus: [],
                            icons: null,
                            error: error.status || 'NETWORK_ERROR'
                        });
                    }
                    changed();
                }
            }
            try {
                await Promise.all(Array.from({ length: Math.min(3, pending.length) }, worker));
            } finally {
                if (version === generation) reading = false;
            }
        },
        dispose() {
            invalidate();
            active = false;
            state.networks = [];
        }
    };
}
