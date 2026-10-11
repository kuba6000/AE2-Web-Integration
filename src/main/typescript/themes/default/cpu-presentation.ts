import type { ThemeState, ThemeApplication } from '../../app/theme-api.js';
import type { Translator } from '../../app/i18n.js';
import { element } from './dom.js';
import type { CpuOutcome } from '../../app/cpus.js';
import type { CpuSelectionMode } from '../../app/api-types.js';
import { cancelIcon, pauseIcon, playIcon } from './icons/hackernoon/playback.js';

export const selectionModes: Record<CpuSelectionMode, { order: number; label: string }> = {
    PLAYER_ONLY: { order: 0, label: 'cpuPlayerOnly' },
    ALL: { order: 1, label: 'cpuAllSources' },
    AUTOMATION_ONLY: { order: 2, label: 'cpuAutomationOnly' }
};

export function cpuOutcomeText(
    outcome: CpuOutcome | undefined,
    status: ThemeState['cpus']['status'],
    locale: Translator
) {
    if (outcome?.uncertain) return locale.common('cpuMutationUncertain');
    if (!outcome?.notice) return '';
    if (outcome.notice === 'cpuCancelled' && status !== 'ready') return '';
    return locale.common(outcome.notice);
}

export function renderCpuActions(
    target: HTMLDivElement,
    key: string,
    cpu: ThemeState['cpus']['detail'] | ThemeState['cpus']['cpus'][number] | null,
    state: ThemeState['cpus'],
    locale: Translator,
    actions: ThemeApplication['cpus'],
    overviewIdentity = ''
) {
    target.className = 'cpu-actions';
    if (!target.firstChild) {
        const pause = element('button');
        pause.type = 'button';
        const cancel = element('button', '', 'cpu-cancel');
        cancel.type = 'button';
        target.append(pause, cancel);
    }
    const [pause, cancel] = [...target.children] as HTMLButtonElement[];
    const outcome = state.outcomes[key];
    const disabled = !!outcome?.mutation || !!outcome?.uncertain || state.status !== 'ready';
    pause.hidden = !cpu?.isBusy || !cpu.supportsPause;
    const pauseLabel = locale.common(cpu?.isPaused ? 'resumeCpuWork' : 'pauseCpuWork');
    const cancelLabel = locale.common('cancelCpuWork');
    pause.innerHTML = cpu?.isPaused ? playIcon : pauseIcon;
    pause.append(element('span', locale.common(cpu?.isPaused ? 'resumeCpuShort' : 'pauseCpuShort')));
    pause.disabled = disabled;
    pause.onclick = () => actions.pause(key, !cpu?.isPaused);
    cancel.hidden = !cpu?.isBusy;
    cancel.innerHTML = cancelIcon;
    cancel.append(element('span', locale.common('cancelCpuShort')));
    cancel.disabled = disabled;
    cancel.onclick = () => {
        if (window.confirm(locale.common('confirmCancelCpu', { cpu: overviewIdentity || key }))) actions.cancel(key);
    };
    for (const [button, description] of [
        [pause, pauseLabel],
        [cancel, cancelLabel]
    ] as const) {
        if (overviewIdentity) button.setAttribute('aria-label', `${description} · ${overviewIdentity}`);
        else button.setAttribute('aria-label', description);
    }
}

export function renderCpuState(target: HTMLElement, busy: boolean, paused: boolean, locale: Translator) {
    target.innerHTML = busy && paused ? pauseIcon : '';
    target.append(element('span', locale.common(busy ? (paused ? 'cpuPausedState' : 'cpuBusy') : 'cpuIdle')));
}
