import type { ThemeState } from '../../app/theme-api.js';
import type { Translator } from '../../app/i18n.js';
import { element } from './dom.js';
import type { CpuResource } from '../../app/api-types.js';
import { registryId } from '../../app/resource-helpers.js';
import { plainMinecraftText } from '../../app/minecraft-text.js';
import { renderMinecraftText } from './minecraft-text.js';
import { selectionModes } from './cpu-presentation.js';

type ResourceTarget = { item: CpuResource; button: HTMLButtonElement };
export type CpuTooltips = ReturnType<typeof createCpuTooltips>;

export function createCpuTooltips(workspace: HTMLElement, hasSelectionMode: boolean, signal: AbortSignal) {
    const tooltip = element('div', '', 'tooltip');
    tooltip.id = 'cpu-resource-tooltip';
    tooltip.role = 'tooltip';
    tooltip.tabIndex = -1;
    tooltip.hidden = true;
    workspace.append(tooltip);

    let state: ThemeState['cpus'];
    let locale: Translator;
    let selectedCpu = '';
    let selectedTooltip: { row: ResourceTarget; x: number; y: number; pointer: boolean } | null = null;

    let toolTooltip: HTMLElement | null = null;
    let cpuTooltip: { target: HTMLElement; key: string; pointer: boolean; x: number; y: number } | null = null;

    function hideTooltip() {
        selectedTooltip?.row.button.removeAttribute('aria-describedby');
        cpuTooltip?.target.removeAttribute('aria-describedby');
        selectedTooltip = null;
        cpuTooltip = null;
        toolTooltip = null;
        tooltip.hidden = true;
        tooltip.classList.remove('cpu-info-tooltip');
        tooltip.scrollTop = 0;
    }

    function positionTooltip(x: number, y: number) {
        const box = tooltip.getBoundingClientRect();
        tooltip.style.left = `${Math.max(8, Math.min(x + 14, innerWidth - box.width - 8))}px`;
        tooltip.style.top = `${Math.max(8, y + box.height + 24 > innerHeight ? y - box.height - 10 : y + 16)}px`;
    }
    function refreshTooltip() {
        if (!selectedTooltip) return;
        const { row, x, y, pointer } = selectedTooltip;
        if (
            row.button.disabled ||
            !row.button.isConnected ||
            (pointer && !row.button.contains(document.elementFromPoint(x, y)))
        ) {
            hideTooltip();
            return;
        }
        const item = row.item;
        const name = element('strong');
        name.append(renderMinecraftText(item.displayName));
        const values = Object.entries({ cpuActive: item.active, cpuPending: item.pending, cpuStored: item.stored })
            .filter(([, quantity]) => quantity > 0)
            .map(([key, quantity]) => [key, locale.number(quantity)]);
        if (state.detail?.hasTrackingInfo)
            values.push(
                ['cpuTimeSpent', locale.duration(item.timeSpentCrafting)],
                ['cpuCraftedTotal', locale.number(item.craftedTotal)],
                ['cpuRate', `${locale.number(item.craftsPerSec)}/s`],
                ['cpuElapsedShare', `${locale.number(item.shareInCraftingTimeCombined * 100)}%`],
                ['cpuProcessingShare', `${locale.number(item.shareInCraftingTime * 100)}%`]
            );
        tooltip.replaceChildren(
            name,
            ...(registryId(item) ? [element('code', registryId(item))] : []),
            ...values.map(([key, value]) => element('span', `${locale.common(key)}: ${value}`))
        );
        tooltip.hidden = false;
        const box = row.button.getBoundingClientRect();
        positionTooltip(pointer ? x : box.left, pointer ? y : box.bottom);
    }

    function showResourceTooltip(row: ResourceTarget, x: number, y: number, pointer: boolean) {
        hideTooltip();
        selectedTooltip = { row, x, y, pointer };
        row.button.setAttribute('aria-describedby', tooltip.id);
        refreshTooltip();
    }

    function showToolTooltip(button: HTMLElement) {
        hideTooltip();
        toolTooltip = button;
        tooltip.replaceChildren(
            ...[button.getAttribute('aria-label'), button.getAttribute('aria-description')]
                .filter((text): text is string => !!text)
                .map((text) => element('span', text))
        );
        tooltip.hidden = false;
        const box = button.getBoundingClientRect();
        positionTooltip(box.right, box.top);
    }

    function refreshCpuTooltip() {
        if (!cpuTooltip) return;
        const { target, key, pointer, x, y } = cpuTooltip;
        const cpu = state.cpus.find((entry) => entry.key === key);
        if (
            !cpu ||
            !target.isConnected ||
            target.hidden ||
            (pointer &&
                document.activeElement !== tooltip &&
                !tooltip.matches(':hover') &&
                !target.contains(document.elementFromPoint(x, y)))
        ) {
            hideTooltip();
            return;
        }
        const current = key === selectedCpu && state.detail && !state.error ? state.detail : cpu;
        const t = locale.common;
        const name = element('strong');
        name.append(renderMinecraftText(plainMinecraftText(cpu.name).trim() ? cpu.name : cpu.key));
        const values = [
            element('span', t(current.isBusy ? (current.isPaused ? 'cpuPausedState' : 'cpuBusy') : 'cpuIdle')),
            element('span', `${t('cpuCapacityLabel')}: ${locale.bytes(cpu.availableStorage)}`),
            element(
                'span',
                `${t('cpuUsedStorageLabel')}: ${cpu.usedStorage >= 0 ? locale.bytes(cpu.usedStorage) : t('cpuValueUnavailable')}`
            ),
            element('span', `${t('cpuCoprocessorsLabel')}: ${locale.number(cpu.coProcessors)}`)
        ];
        if (hasSelectionMode)
            values.push(
                element(
                    'span',
                    `${t('cpuAutomaticSelection')}: ${t(current.selectionMode ? selectionModes[current.selectionMode].label : 'cpuSelectionUnknown')}`
                ),
                element('span', t('cpuSelectionModeHelp'))
            );
        if (current.isBusy) {
            const product = element('span');
            if (current.finalOutput)
                product.append(
                    renderMinecraftText(current.finalOutput.displayName),
                    ` × ${locale.number(current.finalOutput.quantity)}`
                );
            else product.textContent = t('cpuOutputUnknown');
            values.push(product);
            if (current.hasTrackingInfo) {
                if ('timeElapsed' in current)
                    values.push(element('span', `${t('cpuElapsedLabel')}: ${locale.duration(current.timeElapsed)}`));
                values.push(element('span', `${t('historyStartedLabel')}: ${locale.dateTime(current.timeStarted)}`));
            } else values.push(element('span', t('cpuTrackingUnavailable')));
        }
        const readingPosition = tooltip.scrollTop;
        tooltip.replaceChildren(name, element('code', cpu.key), ...values);
        tooltip.classList.add('cpu-info-tooltip');
        tooltip.hidden = false;
        tooltip.scrollTop = readingPosition;
        const box = target.getBoundingClientRect();
        positionTooltip(pointer ? x : box.left, pointer ? y : box.bottom);
    }

    function bindCpuTooltip(target: HTMLElement, key: () => string, isUpdating: () => boolean = () => false) {
        const show = (pointer = false, x = 0, y = 0) => {
            hideTooltip();
            cpuTooltip = { target, key: key(), pointer, x, y };
            target.setAttribute('aria-describedby', tooltip.id);
            refreshCpuTooltip();
        };
        target.addEventListener('pointerenter', (event) => {
            if (event.pointerType !== 'touch') show(true, event.clientX, event.clientY);
        });
        target.addEventListener('pointermove', (event) => {
            if (cpuTooltip?.target === target && cpuTooltip.pointer) {
                cpuTooltip.x = event.clientX;
                cpuTooltip.y = event.clientY;
                positionTooltip(event.clientX, event.clientY);
            }
        });
        target.addEventListener('focus', () => {
            if (!isUpdating()) show();
        });
        target.addEventListener('click', () => show());
        target.addEventListener('pointerleave', (event) => {
            if (
                cpuTooltip?.target === target &&
                event.relatedTarget !== tooltip &&
                !(event.relatedTarget instanceof Node && tooltip.contains(event.relatedTarget)) &&
                document.activeElement !== tooltip
            )
                hideTooltip();
        });
        target.addEventListener('blur', (event) => {
            if (!isUpdating() && cpuTooltip?.target === target && event.relatedTarget !== tooltip) hideTooltip();
        });
        target.addEventListener('keydown', (event) => {
            if (
                event.key === 'Tab' &&
                !event.shiftKey &&
                cpuTooltip?.target === target &&
                tooltip.scrollHeight > tooltip.clientHeight
            ) {
                event.preventDefault();
                cpuTooltip.pointer = false;
                tooltip.focus({ preventScroll: true });
            }
        });
    }
    tooltip.addEventListener('blur', (event) => {
        if (cpuTooltip && event.relatedTarget !== cpuTooltip.target) hideTooltip();
    });
    tooltip.addEventListener('pointerleave', (event) => {
        if (
            cpuTooltip &&
            event.relatedTarget !== cpuTooltip.target &&
            !(event.relatedTarget instanceof Node && cpuTooltip.target.contains(event.relatedTarget)) &&
            document.activeElement !== tooltip
        )
            hideTooltip();
    });
    tooltip.addEventListener('keydown', (event) => {
        if (event.key === 'Tab' && event.shiftKey && cpuTooltip) {
            event.preventDefault();
            cpuTooltip.target.focus({ preventScroll: true });
        }
    });

    document.addEventListener(
        'keydown',
        (event) => {
            if (event.key === 'Escape') {
                if (document.activeElement === tooltip) cpuTooltip?.target.focus({ preventScroll: true });
                hideTooltip();
            }
        },
        { signal }
    );
    document.addEventListener(
        'pointerdown',
        (event) => {
            if (selectedTooltip && event.target instanceof Node && !selectedTooltip.row.button.contains(event.target))
                hideTooltip();
            if (
                cpuTooltip &&
                event.target instanceof Node &&
                !cpuTooltip.target.contains(event.target) &&
                !tooltip.contains(event.target)
            )
                hideTooltip();
        },
        { signal }
    );
    window.addEventListener('resize', hideTooltip, { signal });

    function bindResource(row: ResourceTarget, isUpdating: () => boolean) {
        const button = row.button;
        button.addEventListener('pointerenter', (event) => {
            if (event.pointerType !== 'touch') showResourceTooltip(row, event.clientX, event.clientY, true);
        });
        button.addEventListener('pointermove', (event) => {
            if (selectedTooltip?.row === row && selectedTooltip.pointer) {
                selectedTooltip.x = event.clientX;
                selectedTooltip.y = event.clientY;
                positionTooltip(event.clientX, event.clientY);
            }
        });
        button.addEventListener('pointerleave', () => {
            if (selectedTooltip?.row === row && selectedTooltip.pointer) hideTooltip();
        });
        const showFocused = () => {
            const box = button.getBoundingClientRect();
            showResourceTooltip(row, box.left, box.bottom, false);
        };
        button.addEventListener('focus', () => {
            if (!isUpdating()) showFocused();
        });
        button.addEventListener('click', showFocused);
        button.addEventListener('blur', () => {
            if (!isUpdating() && selectedTooltip?.row === row) hideTooltip();
        });
    }
    function bindTool(button: HTMLElement) {
        button.addEventListener('pointerenter', () => showToolTooltip(button));
        button.addEventListener('focus', () => showToolTooltip(button));
        for (const event of ['pointerleave', 'blur']) button.addEventListener(event, () => hideTool(button));
    }
    function hideTool(button: HTMLElement) {
        if (toolTooltip === button) hideTooltip();
    }
    function refreshTool(container: HTMLElement) {
        if (toolTooltip && container.contains(toolTooltip)) showToolTooltip(toolTooltip);
    }
    return {
        update(next: ThemeState['cpus'], translator: Translator, key: string) {
            state = next;
            locale = translator;
            selectedCpu = key;
        },
        hide: hideTooltip,
        bindCpu: bindCpuTooltip,
        bindResource,
        bindTool,
        hideTool,
        refreshTool,
        refreshResource: refreshTooltip,
        refreshCpu: refreshCpuTooltip,
        dispose() {
            hideTooltip();
            tooltip.remove();
        }
    };
}
