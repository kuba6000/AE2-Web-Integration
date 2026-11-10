import type { ThemeState, ThemeApplication, ThemeSettings } from '../../app/theme-api.js';
import type { Translator } from '../../app/i18n.js';
import { element } from './dom.js';
import { createResourceIcon, paintResourceIcon } from './resource-icon.js';
import { plainMinecraftText } from '../../app/minecraft-text.js';
import { renderMinecraftText } from './minecraft-text.js';
import { cpuHref } from '../../app/router.js';
import {
    renderCpuActions,
    renderCpuState,
    cpuOutcomeText,
    renderCpuSelectionMode,
    cpuRequesterText
} from './cpu-presentation.js';
import type { CpuTooltips } from './cpu-tooltips.js';
import { cpuSortCriteria, orderedCpus } from './cpu-list-order.js';
import { terminalIcons, craftingHammer } from './icons/pixel/terminal.js';
import { cpuChipIcon, cpuSearchIcon } from './icons/pixel/cpu-controls.js';

export function createCpuOverview(application: ThemeApplication, settings: ThemeSettings, tips: CpuTooltips) {
    const overview = element('div', '', 'cpu-overview');
    const overviewHeader = element('div', '', 'terminal-heading');
    const title = element('h2');
    const controls = element('div', '', 'cpu-overview-controls');
    const buttons = element('div', '', 'cpu-selector-buttons');
    buttons.role = 'group';
    const sortButton = element('button', '', 'tool-button');
    const direction = element('button', '', 'tool-button');
    const filterButton = element('button', '', 'tool-button');
    const searchToggle = element('button', '', 'tool-button');
    searchToggle.innerHTML = cpuSearchIcon;
    for (const button of [sortButton, direction, filterButton, searchToggle]) {
        button.type = 'button';
        tips.bindTool(button);
        buttons.append(button);
    }
    const searchLabel = element('label', '', 'cpu-overview-search');
    const searchName = element('span', '', 'sr-only');
    const search = element('input');
    search.type = 'search';
    search.id = 'cpu-overview-search-input';
    searchToggle.setAttribute('aria-controls', search.id);
    searchLabel.append(searchName, search);
    controls.append(buttons, searchLabel);
    overviewHeader.append(title, controls);
    const overviewBody = element('div', '', 'terminal-body');
    const listStatus = element('p', '', 'cpu-list-status');
    listStatus.role = 'status';
    const listScroll = element('div', '', 'terminal-scroll');
    listScroll.role = 'region';
    listScroll.tabIndex = 0;
    const list = element('ul', '', 'cpu-list');
    listScroll.append(list);
    const overviewIcons = application.icons.observe(listScroll, paintResourceIcon);
    overviewBody.append(listStatus, listScroll);
    overview.append(overviewHeader, overviewBody);
    const overviewSize = new ResizeObserver(() => {
        const bounds = list.getBoundingClientRect();
        if (bounds.width === 0) return;
        overview.style.setProperty('--grid-inset', `${bounds.left - overview.getBoundingClientRect().left}px`);
    });
    overviewSize.observe(listScroll);

    type CpuRow = ReturnType<typeof createOverviewRow>;
    let rows = new Map<string, CpuRow>();
    let state: ThemeState['cpus'];
    let locale: Translator;
    let gridKey = '';
    let searchOpen = false;
    let overviewActive = false;
    let readingPosition = 0;
    const savedSort = settings.get('cpuOverviewSort');
    let sort = cpuSortCriteria.find((criterion) => criterion.value === savedSort) || cpuSortCriteria[0];
    const availableCriteria = cpuSortCriteria.filter(
        (criterion) => application.state.capabilities.cpuSelectionMode || criterion.value !== 'automation'
    );
    const effectiveSort = () => availableCriteria.find((criterion) => criterion === sort) || availableCriteria[0];
    let descending = settings.get('cpuOverviewSortOrder') === 'descending';
    const filters = [
        { value: 'all', label: 'all', icon: terminalIcons.all },
        { value: 'busy', label: 'cpuBusy', icon: craftingHammer },
        { value: 'idle', label: 'cpuIdle', icon: cpuChipIcon }
    ] as const;
    const savedFilter = settings.get('cpuOverviewFilter');
    let filter = filters.find((entry) => entry.value === savedFilter) || filters[0];

    sortButton.addEventListener('click', () => {
        sort = availableCriteria[(availableCriteria.indexOf(effectiveSort()) + 1) % availableCriteria.length];
        settings.set('cpuOverviewSort', sort.value);
        readingPosition = listScroll.scrollTop = 0;
        renderList();
    });
    direction.addEventListener('click', () => {
        descending = !descending;
        settings.set('cpuOverviewSortOrder', descending ? 'descending' : 'ascending');
        readingPosition = listScroll.scrollTop = 0;
        renderList();
    });
    filterButton.addEventListener('click', () => {
        filter = filters[(filters.indexOf(filter) + 1) % filters.length];
        settings.set('cpuOverviewFilter', filter.value);
        readingPosition = listScroll.scrollTop = 0;
        renderList();
    });
    function setSearch(open: boolean) {
        searchOpen = open;
        if (!open) search.value = '';
        readingPosition = listScroll.scrollTop = 0;
        tips.hide();
        renderList();
        if (open) search.focus();
    }
    searchToggle.addEventListener('click', () => setSearch(!searchOpen));
    search.addEventListener('input', () => {
        readingPosition = listScroll.scrollTop = 0;
        tips.hide();
        renderList();
    });
    search.addEventListener('keydown', (event) => {
        if (event.key !== 'Escape') return;
        event.preventDefault();
        event.stopPropagation();
        setSearch(false);
        searchToggle.focus();
        tips.hide();
    });
    listScroll.addEventListener('scroll', () => {
        tips.hide();
        if (overviewActive && (state.cpus.length || state.status !== 'loading')) readingPosition = listScroll.scrollTop;
    });

    function renderControls() {
        const t = locale.common;
        const criterion = effectiveSort();
        buttons.setAttribute('aria-label', t('cpus'));
        sortButton.innerHTML = criterion.icon;
        sortButton.setAttribute('aria-label', `${t('cpuSelectorSort')}: ${t(criterion.label)}`);
        sortButton.setAttribute(
            'aria-description',
            locale.t('nextState', {
                state: t(availableCriteria[(availableCriteria.indexOf(criterion) + 1) % availableCriteria.length].label)
            })
        );
        direction.innerHTML = terminalIcons[descending ? 'descending' : 'ascending'];
        direction.setAttribute(
            'aria-label',
            `${t('cpuSelectorDirection')}: ${t(descending ? 'descending' : 'ascending')}`
        );
        if (criterion.value === 'state')
            direction.setAttribute('aria-description', t(descending ? 'cpuBusyFirst' : 'cpuIdleFirst'));
        else direction.removeAttribute('aria-description');
        filterButton.innerHTML = filter.icon;
        filterButton.setAttribute('aria-label', `${t('cpuShow')}: ${t(filter.label)}`);
        filterButton.setAttribute(
            'aria-description',
            locale.t('nextState', { state: t(filters[(filters.indexOf(filter) + 1) % filters.length].label) })
        );
        searchLabel.hidden = !searchOpen;
        searchName.textContent = t('searchCpus');
        search.placeholder = t('searchCpus');
        searchToggle.setAttribute('aria-expanded', String(searchOpen));
        searchToggle.setAttribute('aria-label', t(searchOpen ? 'closeCpuSearch' : 'searchCpus'));
        if (searchOpen) searchToggle.setAttribute('aria-description', t('clearCpuSearch'));
        else searchToggle.removeAttribute('aria-description');
        tips.refreshTool(buttons);
    }

    function renderList() {
        renderControls();
        const t = locale.common;
        const visible = orderedCpus(state.cpus, effectiveSort().value, descending, search.value).filter(
            (cpu) => filter.value === 'all' || cpu.isBusy === (filter.value === 'busy')
        );
        let message = '';
        if (state.error) message = t(state.error);
        else if (!state.cpus.length) message = t(state.status === 'loading' ? 'loading' : 'noCpus');
        else if (!visible.length) message = t('noMatchingCpus');
        listStatus.textContent = message;
        listStatus.hidden = !message;
        const current: Map<string, CpuRow> = new Map();
        const scrollTop = readingPosition;
        const focused =
            document.activeElement instanceof HTMLElement && list.contains(document.activeElement)
                ? document.activeElement
                : null;
        for (const [index, cpu] of visible.entries()) {
            const entry = rows.get(cpu.key) || createOverviewRow();
            renderOverviewRow(entry, cpu, gridKey);
            if (list.children[index] !== entry.li) list.insertBefore(entry.li, list.children[index] || null);
            current.set(cpu.key, entry);
        }
        for (const [key, entry] of rows) if (!current.has(key)) entry.li.remove();
        rows = current;
        if (focused?.isConnected && document.activeElement !== focused) focused.focus({ preventScroll: true });
        listScroll.scrollTop = scrollTop;
        if (state.cpus.length || state.status !== 'loading') readingPosition = listScroll.scrollTop;
        overviewIcons.update(
            visible
                .filter((cpu) => cpu.isBusy && cpu.finalOutput)
                .map((cpu) => ({ element: current.get(cpu.key)!.output, icon: cpu.finalOutput?.icon })),
            state.overviewIcons
        );
    }
    function createOverviewRow() {
        const li = element('li', '', 'inset-frame cpu-card');
        const header = element('div', '', 'cpu-card-heading');
        const heading = element('h3');
        const link = element('a', '', 'cpu-card-link');
        const badge = element('span', '', 'cpu-card-state');
        const mode = element('span');
        tips.bindTool(mode);
        header.append(heading, mode, badge);
        const work = element('div', '', 'cpu-card-output');
        const outputLabel = element('p', '', 'cpu-card-label');
        const output = element('p', '', 'cpu-card-output-value');
        const requesters = element('p', '', 'cpu-card-requesters');
        const icon = createResourceIcon();
        work.append(outputLabel, output, requesters);
        const metrics = element('dl', '', 'cpu-card-metrics');
        const fields = ['cpuCapacityLabel', 'cpuUsedStorageLabel', 'cpuCoprocessorsLabel'].map((label) => {
            const group = element('div');
            const term = element('dt');
            const value = element('dd');
            group.append(term, value);
            metrics.append(group);
            return { label, term, value };
        });
        const identity = element('details', '', 'cpu-card-identity');
        const identityLabel = element('summary');
        const key = element('code');
        identity.append(identityLabel, key);
        const actions = element('div');
        const notice = element('p', '', 'cpu-card-notice');
        notice.role = 'status';
        link.append(header, work, metrics);
        li.append(link, identity, actions, notice);
        return {
            li,
            link,
            heading,
            badge,
            mode,
            requesters,
            outputLabel,
            output,
            icon,
            fields,
            identityLabel,
            key,
            actions,
            notice
        };
    }

    function renderOverviewRow(entry: CpuRow, cpu: ThemeState['cpus']['cpus'][number], gridKey: string) {
        const t = locale.common;
        const displayName = plainMinecraftText(cpu.name).trim() ? cpu.name : t('cpuUnnamed');
        const identity = `${plainMinecraftText(displayName)} · ${cpu.key}`;
        entry.link.href = cpuHref(gridKey, cpu.key);
        entry.heading.replaceChildren(renderMinecraftText(displayName));
        entry.link.setAttribute('aria-label', identity);
        entry.link.title = cpu.key;
        entry.li.dataset.state = cpu.isBusy ? (cpu.isPaused ? 'paused' : 'busy') : 'idle';
        renderCpuState(entry.badge, cpu.isBusy, cpu.isPaused, locale);
        renderCpuSelectionMode(
            entry.mode,
            application.state.capabilities.cpuSelectionMode ? cpu.selectionMode : null,
            locale
        );
        tips.refreshTool(entry.mode);
        entry.outputLabel.textContent = t('cpuOutput');
        entry.output.replaceChildren();
        if (cpu.isBusy && cpu.finalOutput) {
            const name = element('strong');
            name.append(renderMinecraftText(cpu.finalOutput.displayName));
            entry.output.append(
                entry.icon,
                name,
                element('span', `× ${locale.number(cpu.finalOutput.quantity)}`, 'cpu-card-quantity')
            );
        } else {
            entry.output.textContent = t(cpu.isBusy ? 'cpuOutputUnknown' : 'cpuIdleMessage');
        }
        entry.requesters.textContent = cpuRequesterText(cpu, locale);
        entry.requesters.hidden = !entry.requesters.textContent;
        const values = [
            locale.bytes(cpu.availableStorage),
            cpu.usedStorage >= 0 ? locale.bytes(cpu.usedStorage) : t('cpuValueUnavailable'),
            locale.number(cpu.coProcessors)
        ];
        entry.fields.forEach((field, index) => {
            field.term.textContent = t(field.label);
            field.value.textContent = values[index];
        });
        entry.identityLabel.textContent = t('cpuIdentifier');
        entry.key.textContent = cpu.key;
        renderCpuActions(entry.actions, cpu.key, cpu, state, locale, application.cpus, identity);
        entry.notice.textContent = cpuOutcomeText(state.outcomes[cpu.key], state.status, locale);
        entry.notice.hidden = !entry.notice.textContent;
        entry.notice.classList.toggle(
            'api-error',
            !!state.outcomes[cpu.key]?.uncertain ||
                (!!state.outcomes[cpu.key]?.notice && state.outcomes[cpu.key].notice !== 'cpuCancelled')
        );
    }

    return {
        element: overview,
        render(route: ThemeState['route'], next: ThemeState['cpus'], translator: Translator) {
            state = next;
            locale = translator;
            const selected = route.view === 'cpus' && !!route.cpuKey;
            overviewActive = route.view === 'cpus' && !selected;
            const nextGrid = route.gridKey ?? '';
            if (gridKey !== nextGrid) {
                gridKey = nextGrid;
                searchOpen = false;
                search.value = '';
                readingPosition = listScroll.scrollTop = 0;
            }
            overview.hidden = selected;
            if (route.view !== 'cpus' || selected) overviewIcons.update([], null);
            if (route.view !== 'cpus') return;
            if (selected) return;
            const t = locale.common;
            title.textContent = t('cpus');
            listScroll.setAttribute('aria-label', t('cpus'));
            renderList();
        },
        dispose() {
            overviewSize.disconnect();
            overviewIcons.dispose();
            overview.remove();
        }
    };
}
