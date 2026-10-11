import type { ThemeState, ThemeApplication, ThemeSettings } from '../../app/theme-api.js';
import type { Translator } from '../../app/i18n.js';
import { element } from './dom.js';
import { createResourceIcon, paintResourceIcon } from './resource-icon.js';
import { plainMinecraftText } from '../../app/minecraft-text.js';
import { renderMinecraftText } from './minecraft-text.js';
import { cpuHref } from '../../app/router.js';
import { selectionModes, renderCpuState } from './cpu-presentation.js';
import { terminalIcons, craftingHammer } from './icons/pixel/terminal.js';
import { cpuChipIcon, cpuSearchIcon } from './icons/pixel/cpu-controls.js';
import type { CpuTooltips } from './cpu-tooltips.js';

export function createCpuSelector(application: ThemeApplication, settings: ThemeSettings, tips: CpuTooltips) {
    const panel = element('aside');
    panel.id = 'cpu-panel';
    const selectorTitle = element('h3');
    const selectorControls = element('div', '', 'cpu-selector-controls');
    const selectorButtons = element('div', '', 'cpu-selector-buttons');
    selectorButtons.role = 'group';
    const selectorSortButton = element('button', '', 'tool-button');
    selectorSortButton.type = 'button';
    const selectorDirection = element('button', '', 'tool-button');
    selectorDirection.type = 'button';
    const selectorSearchToggle = element('button', '', 'tool-button');
    selectorSearchToggle.type = 'button';
    selectorSearchToggle.innerHTML = cpuSearchIcon;
    selectorButtons.append(selectorSortButton, selectorDirection, selectorSearchToggle);
    const selectorSearchLabel = element('label', '', 'cpu-selector-search');
    const selectorSearchName = element('span', '', 'sr-only');
    const selectorSearch = element('input');
    selectorSearch.type = 'search';
    selectorSearch.id = 'cpu-selector-search-input';
    selectorSearchToggle.setAttribute('aria-controls', selectorSearch.id);
    selectorSearchLabel.append(selectorSearchName, selectorSearch);
    selectorControls.append(selectorButtons, selectorSearchLabel);
    const selectorScroll = element('div', '', 'cpu-selector-scroll inset-frame');
    selectorScroll.role = 'region';
    selectorScroll.tabIndex = 0;
    const selectorList = element('ul', '', 'cpu-selector-list');
    const selectorStatus = element('p', '', 'cpu-selector-status');
    selectorStatus.role = 'status';
    selectorScroll.append(selectorStatus, selectorList);
    const selectorIcons = application.icons.observe(selectorScroll, paintResourceIcon);

    panel.append(selectorTitle, selectorControls, selectorScroll);
    let state: ThemeState['cpus'];
    let locale: Translator;
    let updatingRows = false;
    let selectorRows = new Map<string, ReturnType<typeof createSelectorRow>>();
    let selectorGrid = '';
    let selectedCpu = '';
    let selectorSearchOpen = false;
    const selectorCriteria = [
        { value: 'name', label: 'name', icon: terminalIcons.name },
        { value: 'capacity', label: 'cpuCapacityLabel', icon: terminalIcons.stored },
        { value: 'coprocessors', label: 'cpuCoprocessorsLabel', icon: cpuChipIcon },
        { value: 'state', label: 'cpuBusyState', icon: craftingHammer },
        { value: 'automation', label: 'cpuAutomation', icon: terminalIcons.all }
    ] as const;
    const hasSelectionMode = application.state.capabilities.cpuSelectionMode === true;
    const availableSelectorCriteria = selectorCriteria.filter(
        (criterion) => hasSelectionMode || criterion.value !== 'automation'
    );
    const savedSelectorSort = settings.get('cpuSelectorSort');
    let selectorSort =
        selectorCriteria.find((criterion) => criterion.value === savedSelectorSort) || selectorCriteria[0];
    let selectorDescending = settings.get('cpuSelectorSortOrder') === 'descending';
    const effectiveSelectorSort = () =>
        availableSelectorCriteria.find((criterion) => criterion === selectorSort) || availableSelectorCriteria[0];

    for (const button of [selectorSortButton, selectorDirection, selectorSearchToggle]) tips.bindTool(button);
    selectorSortButton.addEventListener('click', () => {
        selectorSort =
            availableSelectorCriteria[
                (availableSelectorCriteria.indexOf(effectiveSelectorSort()) + 1) % availableSelectorCriteria.length
            ];
        settings.set('cpuSelectorSort', selectorSort.value);
        selectorScroll.scrollTop = 0;
        renderSelector();
    });
    selectorDirection.addEventListener('click', () => {
        selectorDescending = !selectorDescending;
        settings.set('cpuSelectorSortOrder', selectorDescending ? 'descending' : 'ascending');
        selectorScroll.scrollTop = 0;
        renderSelector();
    });
    function setSelectorSearch(open: boolean) {
        selectorSearchOpen = open;
        if (!open) selectorSearch.value = '';
        selectorScroll.scrollTop = 0;
        tips.hide();
        renderSelector();
        if (open) selectorSearch.focus();
    }
    selectorSearchToggle.addEventListener('click', () => setSelectorSearch(!selectorSearchOpen));
    selectorSearch.addEventListener('keydown', (event) => {
        if (event.key === 'Escape') {
            event.preventDefault();
            event.stopPropagation();
            setSelectorSearch(false);
            selectorSearchToggle.focus();
            tips.hide();
        }
    });

    function createSelectorRow(key: string) {
        const li = element('li');
        const link = element('a', '', 'cpu-selector-link');
        const name = element('span', '', 'cpu-selector-name');
        const badge = element('span', '', 'cpu-card-state');
        const icon = createResourceIcon();
        link.append(icon, name, badge);
        tips.bindCpu(
            link,
            () => key,
            () => updatingRows
        );
        li.append(link);
        return { li, link, name, badge, icon };
    }

    function renderControls() {
        const t = locale.common;
        const criterion = effectiveSelectorSort();
        selectorButtons.setAttribute('aria-label', t('cpuSelector'));
        selectorSearchLabel.hidden = !selectorSearchOpen;
        selectorSearchToggle.setAttribute('aria-expanded', String(selectorSearchOpen));
        selectorSearchToggle.setAttribute('aria-label', t(selectorSearchOpen ? 'closeCpuSearch' : 'searchCpus'));
        if (selectorSearchOpen) selectorSearchToggle.setAttribute('aria-description', t('clearCpuSearch'));
        else selectorSearchToggle.removeAttribute('aria-description');
        selectorSortButton.innerHTML = criterion.icon;
        selectorSortButton.setAttribute('aria-label', `${t('cpuSelectorSort')}: ${t(criterion.label)}`);
        selectorSortButton.setAttribute(
            'aria-description',
            locale.t('nextState', {
                state: t(
                    availableSelectorCriteria[
                        (availableSelectorCriteria.indexOf(criterion) + 1) % availableSelectorCriteria.length
                    ].label
                )
            })
        );
        selectorDirection.innerHTML = terminalIcons[selectorDescending ? 'descending' : 'ascending'];
        selectorDirection.setAttribute(
            'aria-label',
            `${t('cpuSelectorDirection')}: ${t(selectorDescending ? 'descending' : 'ascending')}`
        );
        if (criterion.value === 'state')
            selectorDirection.setAttribute('aria-description', t(selectorDescending ? 'cpuBusyFirst' : 'cpuIdleFirst'));
        else selectorDirection.removeAttribute('aria-description');
        tips.refreshTool(selectorButtons);
    }

    function visibleCpus() {
        const criterion = effectiveSelectorSort();
        const terms = selectorSearch.value.trim().toLocaleLowerCase(document.documentElement.lang).split(/\s+/);
        return state.cpus
            .filter((cpu) => {
                const text = `${plainMinecraftText(cpu.name)} ${cpu.key}`.toLocaleLowerCase(
                    document.documentElement.lang
                );
                return terms.every((term) => text.includes(term));
            })
            .sort((a, b) => {
                const name = (plainMinecraftText(a.name).trim() || a.key).localeCompare(
                    plainMinecraftText(b.name).trim() || b.key,
                    document.documentElement.lang
                );
                const aMode = a.selectionMode ? selectionModes[a.selectionMode].order : null;
                const bMode = b.selectionMode ? selectionModes[b.selectionMode].order : null;
                if (criterion.value === 'automation' && (aMode === null) !== (bMode === null))
                    return aMode === null ? 1 : -1;
                let primary = name;
                switch (criterion.value) {
                    case 'capacity':
                        primary = a.availableStorage - b.availableStorage;
                        break;
                    case 'coprocessors':
                        primary = a.coProcessors - b.coProcessors;
                        break;
                    case 'state':
                        primary = Number(a.isBusy) - Number(b.isBusy);
                        break;
                    case 'automation':
                        primary = (aMode ?? 0) - (bMode ?? 0);
                        break;
                }
                return (
                    (selectorDescending ? -primary : primary) || name || (a.key < b.key ? -1 : a.key > b.key ? 1 : 0)
                );
            });
    }

    function renderSelector() {
        const t = locale.common;
        renderControls();
        const visible = visibleCpus();
        const current = new Map<string, ReturnType<typeof createSelectorRow>>();
        const scrollTop = selectorScroll.scrollTop;
        const focused = [...selectorRows.values()].find((row) => row.link === document.activeElement);
        updatingRows = true;
        for (const [index, cpu] of visible.entries()) {
            const row = selectorRows.get(cpu.key) || createSelectorRow(cpu.key);
            const displayName = plainMinecraftText(cpu.name).trim() ? cpu.name : cpu.key;
            row.link.href = cpuHref(selectorGrid, cpu.key);
            row.link.setAttribute(
                'aria-label',
                `${plainMinecraftText(displayName)} · ${cpu.key} · ${t(cpu.isBusy ? (cpu.isPaused ? 'cpuPausedState' : 'cpuBusy') : 'cpuIdle')} · ${locale.bytes(cpu.availableStorage)}`
            );
            if (cpu.key === selectedCpu) row.link.setAttribute('aria-current', 'page');
            else row.link.removeAttribute('aria-current');
            row.name.replaceChildren(renderMinecraftText(displayName));
            row.icon.hidden = !cpu.isBusy || !cpu.finalOutput;
            renderCpuState(row.badge, cpu.isBusy, cpu.isPaused, locale);
            row.badge.append(element('span', `· ${locale.bytes(cpu.availableStorage)}`));
            if (selectorList.children[index] !== row.li)
                selectorList.insertBefore(row.li, selectorList.children[index] || null);
            current.set(cpu.key, row);
        }
        for (const [key, row] of selectorRows) if (!current.has(key)) row.li.remove();
        selectorRows = current;
        if (focused?.link.isConnected && document.activeElement !== focused.link)
            focused.link.focus({ preventScroll: true });
        updatingRows = false;
        let message = '';
        if (!visible.length) {
            if (state.cpus.length) message = t('noMatchingCpus');
            else message = t(state.status === 'loading' ? 'loading' : 'noCpus');
        }
        selectorStatus.textContent = message;
        selectorStatus.hidden = !selectorStatus.textContent;
        selectorScroll.scrollTop = scrollTop;
        selectorIcons.update(
            visible
                .filter((cpu) => cpu.isBusy && cpu.finalOutput)
                .map((cpu) => ({ element: current.get(cpu.key)!.link, icon: cpu.finalOutput?.icon })),
            state.overviewIcons
        );
    }
    selectorSearch.addEventListener('input', () => {
        tips.hide();
        selectorScroll.scrollTop = 0;
        renderSelector();
    });
    selectorScroll.addEventListener('scroll', tips.hide);

    return {
        element: panel,
        render(route: ThemeState['route'], next: ThemeState['cpus'], translator: Translator) {
            state = next;
            locale = translator;
            const selected = route.view === 'cpus' && !!route.cpuKey;
            const nextGrid = route.view === 'cpus' ? route.gridKey : '';
            if (selectorGrid !== nextGrid) {
                selectorGrid = nextGrid;
                selectorSearchOpen = false;
                selectorSearch.value = '';
                selectorScroll.scrollTop = 0;
            }
            selectedCpu = selected ? route.cpuKey || '' : '';

            panel.hidden = !selected;
            if (!selected) {
                selectorIcons.update([], null);
                selectorList.replaceChildren();
                selectorRows.clear();
                return;
            }
            const t = locale.common;
            panel.setAttribute('aria-label', t('cpus'));
            selectorTitle.textContent = t('cpus');
            selectorSearchName.textContent = t('searchCpus');
            selectorSearch.placeholder = t('searchCpus');
            selectorScroll.setAttribute('aria-label', t('cpuSelector'));
            renderSelector();
        },
        dispose() {
            selectorIcons.dispose();
            panel.remove();
        }
    };
}
