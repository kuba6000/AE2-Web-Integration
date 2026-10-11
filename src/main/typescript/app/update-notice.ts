import { createSettings } from './storage.js';
import { ApiError, type Api } from './api.js';
import type { AvailableUpdate } from './api-types.js';

const SNOOZE_MS = 7 * 24 * 60 * 60 * 1000;

/** Local snooze and administrator mutation for the recommendation loaded with this page. */
export function createUpdateNotice(base: URL, api: Api, update: AvailableUpdate | null) {
    const settings = createSettings(base, 'updates');
    const listeners = new Set<() => void>();
    let dismissed = false;
    let pending = false;
    let error: 'updateChanged' | 'updateSkipFailed' | 'updateSkipUnconfirmed' | null = null;
    function emit() {
        for (const listener of listeners) listener();
    }
    return {
        update,
        get pending() {
            return pending;
        },
        get error() {
            return error;
        },
        get visible() {
            const until = settings.get('snoozedUntil');
            const snoozed = typeof until === 'number' && Number.isFinite(until) && until > Date.now();
            return !!update && !update.skipped && !dismissed && !snoozed;
        },
        dismiss(snooze = false) {
            dismissed = true;
            if (snooze) settings.set('snoozedUntil', Date.now() + SNOOZE_MS);
            emit();
        },
        async skip() {
            if (!update || pending || dismissed || update.skipped) return;
            pending = true;
            error = null;
            emit();
            try {
                await api.skipUpdate(update.tag);
                dismissed = true;
            } catch (failure) {
                if (failure instanceof ApiError && failure.status === 'UPDATE_CHANGED') error = 'updateChanged';
                else if (
                    failure instanceof ApiError &&
                    ['NETWORK_ERROR', 'INVALID_RESPONSE', 'TIMEOUT'].includes(failure.status)
                )
                    error = 'updateSkipUnconfirmed';
                else error = 'updateSkipFailed';
            } finally {
                pending = false;
                emit();
            }
        },
        subscribe(listener: () => void) {
            listeners.add(listener);
            return () => listeners.delete(listener);
        }
    };
}
