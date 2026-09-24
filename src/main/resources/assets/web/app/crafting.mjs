import { navigateToPlan } from './router.mjs';

/** Calculation state and actions. The application supplies route lifetime and scheduling. */
export function createCrafting(api, changed) {
    const state = {
        status: 'idle',
        plan: null,
        cpus: [],
        selectedCpu: '',
        mutation: null,
        uncertain: null,
        error: null,
        errorDetail: null,
        metadata: null
    };
    const metadata = new Map();
    const outcomes = new Map();
    let route = {};
    let generation = 0;
    let request;
    let reading = false;
    let selectedOnce = false;
    let readFailed = false;
    const identity = () => `${route.gridKey}/${route.view === 'plan' ? route.planId : 'create'}`;
    function finishMutation(key, outcome) {
        if (outcome) outcomes.set(key, outcome);
        else outcomes.delete(key);
        if (identity() === key && state.mutation) {
            Object.assign(state, { mutation: null }, outcome);
            changed();
        }
    }
    const isUncertain = (error) =>
        !error.status || ['NETWORK_ERROR', 'INVALID_RESPONSE', 'TIMEOUT', 'INTERNAL_ERROR'].includes(error.status);
    const eligible = (cpu) =>
        state.plan?.isDone &&
        !state.plan.isSimulating &&
        !state.plan.plan?.some((row) => row.missing > 0) &&
        cpu.availableStorage >= state.plan.bytesTotal &&
        (!cpu.isBusy ||
            (!!state.metadata?.itemKey &&
                cpu.finalOutput?.itemKey === state.metadata.itemKey &&
                cpu.usedStorage >= 0 &&
                cpu.availableStorage >= cpu.usedStorage + state.plan.bytesTotal));

    async function refresh() {
        if (
            route.view !== 'plan' ||
            reading ||
            state.mutation ||
            ['submitted', 'deleted', 'unavailable'].includes(state.status)
        )
            return;
        const version = generation;
        const current = route;
        reading = true;
        request = new AbortController();
        if (readFailed) {
            state.error = null;
            readFailed = false;
        }
        try {
            if (!state.plan?.isDone || state.uncertain) {
                const plan = await api.plan(current.gridKey, current.planId, request.signal);
                if (version !== generation) return;
                state.plan = plan;
                state.status = plan.isDone ? 'ready' : 'calculating';
            }
            if (state.plan.isDone) {
                const cpus = await api.cpus(current.gridKey, request.signal);
                if (version !== generation) return;
                state.cpus = Object.entries(cpus).map(([key, cpu]) => ({ ...cpu, key, eligible: eligible(cpu) }));
                if (state.selectedCpu && !state.cpus.some((cpu) => cpu.key === state.selectedCpu && cpu.eligible))
                    state.selectedCpu = '';
                if (!selectedOnce) {
                    state.selectedCpu = state.cpus.find((cpu) => cpu.eligible)?.key || '';
                    selectedOnce = true;
                }
            }
        } catch (error) {
            if (version !== generation || error.name === 'AbortError') return;
            state.error = error.status;
            readFailed = true;
            state.cpus = [];
            state.selectedCpu = '';
            if (['INVALID_ID', 'NO_PERMISSIONS', 'GRID_NOT_FOUND'].includes(error.status)) {
                state.plan = null;
                state.metadata = null;
                state.status = error.status === 'INVALID_ID' ? 'unavailable' : 'error';
            }
        } finally {
            if (version === generation) {
                reading = false;
                changed();
            }
        }
    }
    return {
        state,
        get pending() {
            return route.view === 'plan' && state.status === 'calculating' && !state.error;
        },
        route(next) {
            generation++;
            request?.abort();
            reading = false;
            route = next;
            selectedOnce = false;
            readFailed = false;
            Object.assign(state, {
                status: next.view === 'plan' ? 'loading' : 'idle',
                plan: null,
                cpus: [],
                selectedCpu: '',
                mutation: null,
                uncertain: null,
                error: null,
                errorDetail: null,
                metadata: metadata.get(identity()) || null
            });
            Object.assign(state, outcomes.get(identity()));
        },
        refresh,
        block(error) {
            generation++;
            request?.abort();
            reading = false;
            readFailed = true;
            Object.assign(state, {
                status: 'error',
                plan: null,
                metadata: null,
                cpus: [],
                selectedCpu: '',
                error,
                errorDetail: null
            });
            changed();
        },
        selectCpu(key) {
            state.selectedCpu = state.cpus.find((cpu) => cpu.key === key && cpu.eligible)?.key || '';
            changed();
        },
        async create(item, quantity) {
            if (
                route.view !== 'items' ||
                state.mutation ||
                state.uncertain ||
                !item?.craftable ||
                !item.itemKey ||
                !Number.isSafeInteger(quantity) ||
                quantity <= 0
            )
                return;
            const version = generation;
            const gridKey = route.gridKey;
            const key = identity();
            outcomes.set(key, { mutation: 'create' });
            state.mutation = 'create';
            state.error = null;
            changed();
            try {
                const { jobID } = await api.createPlan(gridKey, { itemKey: item.itemKey, quantity });
                metadata.set(`${gridKey}/${jobID}`, { itemKey: item.itemKey, itemname: item.itemname, quantity });
                finishMutation(key, null);
                if (version !== generation) return;
                navigateToPlan(gridKey, jobID);
            } catch (error) {
                finishMutation(key, isUncertain(error) ? { uncertain: 'create' } : null);
                if (version === generation) state.error = error.status;
            } finally {
                if (version === generation) {
                    state.mutation = null;
                    changed();
                }
            }
        },
        async submit() {
            if (
                state.mutation ||
                state.uncertain ||
                state.status !== 'ready' ||
                !state.cpus.some((cpu) => cpu.key === state.selectedCpu && cpu.eligible)
            )
                return;
            const key = identity();
            const version = ++generation;
            request?.abort();
            reading = false;
            outcomes.set(key, { mutation: 'submit' });
            state.mutation = 'submit';
            state.error = null;
            state.errorDetail = null;
            changed();
            try {
                await api.submitPlan(route.gridKey, route.planId, state.selectedCpu);
                finishMutation(key, { status: 'submitted' });
                if (version === generation) state.status = 'submitted';
            } catch (error) {
                finishMutation(key, isUncertain(error) ? { uncertain: 'submit' } : null);
                if (version === generation) {
                    if (isUncertain(error)) state.uncertain = 'submit';
                    state.error = error.status;
                    state.errorDetail = typeof error.data === 'string' ? error.data : null;
                    if (error.status === 'CPU_NOT_FOUND') {
                        state.selectedCpu = '';
                        selectedOnce = true;
                        state.mutation = null;
                        await refresh();
                    }
                    if (error.status === 'JOB_NOT_DONE') {
                        state.plan = null;
                        state.status = 'calculating';
                        state.cpus = [];
                        state.selectedCpu = '';
                        readFailed = true;
                    }
                    if (['INVALID_ID', 'NO_PERMISSIONS', 'GRID_NOT_FOUND'].includes(error.status)) {
                        state.status = error.status === 'INVALID_ID' ? 'unavailable' : 'error';
                        state.plan = null;
                        state.metadata = null;
                        state.cpus = [];
                        state.selectedCpu = '';
                        readFailed = true;
                    }
                }
            } finally {
                if (version === generation) {
                    state.mutation = null;
                    changed();
                }
            }
        },
        async remove() {
            if (
                route.view !== 'plan' ||
                state.mutation ||
                state.uncertain ||
                ['submitted', 'deleted', 'unavailable', 'error'].includes(state.status)
            )
                return;
            const version = ++generation;
            const key = identity();
            outcomes.set(key, { mutation: 'delete' });
            request?.abort();
            reading = false;
            state.mutation = 'delete';
            state.error = null;
            changed();
            try {
                await api.deletePlan(route.gridKey, route.planId);
                finishMutation(key, { status: 'deleted' });
                if (version === generation) state.status = 'deleted';
            } catch (error) {
                finishMutation(key, isUncertain(error) ? { uncertain: 'delete' } : null);
                if (version === generation) {
                    state.error = error.status;
                    if (['INVALID_ID', 'NO_PERMISSIONS', 'GRID_NOT_FOUND'].includes(error.status)) {
                        state.status = error.status === 'INVALID_ID' ? 'unavailable' : 'error';
                        state.plan = null;
                        state.metadata = null;
                        state.cpus = [];
                        state.selectedCpu = '';
                        readFailed = true;
                    }
                }
            } finally {
                if (version === generation) {
                    state.mutation = null;
                    changed();
                }
            }
        },
        dispose() {
            generation++;
            request?.abort();
            metadata.clear();
            outcomes.clear();
        }
    };
}
