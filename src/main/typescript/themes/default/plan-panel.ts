import type { ThemeState, ThemeApplication } from '../../app/theme-api.js';
import type { Translator } from '../../app/i18n.js';
import { element } from './dom.js';
import { plainMinecraftText } from '../../app/minecraft-text.js';
import { renderMinecraftText } from './minecraft-text.js';
import type { TooltipBindings } from './tooltip-bindings.js';
import { renderCpuSelectionMode, selectionModes } from './cpu-presentation.js';

export function createPlanPanel(application: ThemeApplication, tooltip: TooltipBindings) {
    const panel = element('aside');
    panel.id = 'plan-panel';
    const cpuTitle = element('h3');
    const output = element('p');
    output.className = 'plan-output';
    tooltip.bind(output, () => [element('span', output.textContent || '')]);
    const status = element('p');
    status.role = 'status';
    const reason = element('p');
    const bytes = element('p');
    const cpuHint = element('p');
    const cpuHeading = element('div');
    cpuHeading.className = 'plan-cpu-heading';
    cpuHeading.append(cpuTitle, bytes);
    const cpuList = element('div');
    cpuList.className = 'cpu-selector-list cpu-selector-scroll inset-frame';
    cpuList.role = 'radiogroup';
    cpuList.addEventListener('scroll', () => tooltip.hide());
    panel.append(output, status, reason, cpuHeading, cpuHint, cpuList);
    type Cpu = ThemeState['crafting']['cpus'][number];
    type CpuRow = ReturnType<typeof createCpuRow>;
    const cpuRows = new Map<string, CpuRow>();

    function createCpuRow(key: string) {
        const label = element('label');
        label.className = 'plan-cpu';
        const input = element('input');
        input.type = 'radio';
        input.name = 'plan-cpu';
        input.value = key;
        input.addEventListener('change', () => application.crafting.selectCpu(key));
        const name = element('strong');
        name.className = 'cpu-selector-name';
        const details = element('span');
        const mode = element('span');
        label.append(input, name, details, mode);
        const entry = { label, input, name, details, mode, tooltipContent: (): Node[] => [] };
        tooltip.bind(label, () => entry.tooltipContent(), input);
        return entry;
    }

    function renderCpuRow(row: CpuRow, cpu: Cpu, state: ThemeState['crafting'], locale: Translator) {
        const { common: t, number } = locale;
        const selectionMode = application.state.capabilities.cpuSelectionMode ? cpu.selectionMode : null;
        renderCpuSelectionMode(row.mode, selectionMode, locale);
        row.tooltipContent = () => {
            const name = element('strong');
            name.append(renderMinecraftText(cpu.name || t('cpuUnnamed')));
            const lines: Node[] = [
                name,
                element('span', t(cpu.isBusy ? (cpu.isPaused ? 'cpuPausedState' : 'cpuBusy') : 'cpuIdle')),
                element('span', t('cpuCapacity', { bytes: locale.bytes(cpu.availableStorage) })),
                element(
                    'span',
                    cpu.usedStorage >= 0
                        ? t('cpuUsedStorage', { bytes: locale.bytes(cpu.usedStorage) })
                        : t('cpuStorageUnknown')
                ),
                element('span', t('coprocessors', { count: cpu.coProcessors }))
            ];
            if (selectionMode) {
                lines.push(
                    element('span', `${t('cpuAutomaticSelection')}: ${t(selectionModes[selectionMode].label)}`),
                    element('span', t('cpuSelectionModeHelp'))
                );
            }
            if (cpu.isBusy) {
                const output = element('span');
                if (cpu.finalOutput)
                    output.append(
                        `${t('cpuOutput')}: `,
                        renderMinecraftText(cpu.finalOutput.displayName),
                        ` × ${number(cpu.finalOutput.quantity)}`
                    );
                else output.textContent = t('cpuOutputUnknown');
                lines.push(output);
            }
            const identifier = element('code', cpu.key);
            lines.push(identifier);
            return lines;
        };
        row.name.replaceChildren(renderMinecraftText(cpu.name || t('cpuUnnamed')));
        const description = `${t(cpu.isBusy ? 'cpuBusy' : 'cpuIdle')} · ${locale.bytes(cpu.availableStorage)} · ${t('coprocessors', { count: cpu.coProcessors })}`;
        row.details.textContent = description;
        row.input.setAttribute(
            'aria-label',
            `${plainMinecraftText(cpu.name || t('cpuUnnamed'))} · ${cpu.key} · ${description}`
        );
        row.input.disabled = !cpu.eligible || !!state.mutation || !!state.uncertain;
        row.input.checked = state.selectedCpu === cpu.key;
        row.label.dataset.eligibility = !cpu.eligible ? 'invalid' : cpu.isBusy ? 'merge' : 'ready';
    }

    return {
        element: panel,
        render(state: ThemeState['crafting'], locale: Translator) {
            const { common: t, number } = locale;
            output.replaceChildren();
            output.tabIndex = state.metadata ? 0 : -1;
            if (state.metadata)
                output.append(renderMinecraftText(state.metadata.displayName), ` × ${number(state.metadata.quantity)}`);
            status.textContent = t(state.status);
            status.classList.toggle('api-error', !!state.error || !!state.uncertain);
            if (state.uncertain) {
                const uncertainLabels = {
                    create: 'uncertainCreate',
                    delete: 'uncertainDelete',
                    submit: 'uncertainSubmit'
                };
                status.textContent = t(uncertainLabels[state.uncertain]);
            } else if (state.error) status.textContent = t(state.error);
            else if (state.plan?.isDone && (state.plan.isSimulating || state.plan.plan?.some((row) => row.missing > 0)))
                status.textContent = t('simulation');
            reason.textContent = state.errorDetail || '';
            bytes.textContent = state.plan?.isDone
                ? t('planBytes', { bytes: locale.bytes(state.plan.bytesTotal) })
                : '';
            cpuTitle.textContent = t('craftingCpu');
            cpuList.setAttribute('aria-label', t('craftingCpu'));
            cpuList.hidden = state.status !== 'ready';
            cpuHint.textContent =
                state.status === 'ready' && !state.cpus.some((cpu) => cpu.eligible) ? t('noEligibleCpu') : '';
            const remaining = new Set(cpuRows.keys());
            for (const cpu of state.cpus) {
                let row = cpuRows.get(cpu.key);
                if (!row) {
                    row = createCpuRow(cpu.key);
                    cpuRows.set(cpu.key, row);
                    cpuList.append(row.label);
                }
                renderCpuRow(row, cpu, state, locale);
                remaining.delete(cpu.key);
            }
            for (const key of remaining) {
                cpuRows.get(key)!.label.remove();
                cpuRows.delete(key);
            }
        },
        dispose() {
            panel.remove();
        }
    };
}
