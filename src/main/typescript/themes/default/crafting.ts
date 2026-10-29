import { createTreeExportDialog } from './crafting-tree-export-dialog.js';
import { createCraftingTreeView } from './crafting-tree.js';
import { createCraftingDialog } from './crafting-dialog.js';
import { createPlanPanel } from './plan-panel.js';
import { createPlanActions } from './plan-actions.js';
import { element } from './dom.js';
import type { TooltipBindings } from './tooltip-bindings.js';
import { selectPlanResources } from '../../app/resource-helpers.js';
import type { ResourceStack, PlanItem } from '../../app/api-types.js';
import type { ThemeState, ThemeApplication, ThemeLifetime, ReadonlyData, ThemeSettings } from '../../app/theme-api.js';
import type { Translator } from '../../app/i18n.js';

import { createSlotGrid } from './slot-grid.js';
import { createResourceIcon, paintResourceIcon } from './resource-icon.js';
import { slotQuantity } from './resource-quantity.js';
import { terminalIcons, craftingHammer } from './icons/pixel/terminal.js';
import type { PlanSort } from '../../app/crafting.js';
import { plainMinecraftText } from '../../app/minecraft-text.js';
import { renderMinecraftText } from './minecraft-text.js';

function storageUseLevel(fraction: number) {
    if (fraction <= 0.25) return 'low';
    if (fraction <= 0.5) return 'medium';
    if (fraction <= 0.75) return 'high';
    return 'critical';
}

export type CraftingTooltipPresenter = TooltipBindings & {
    metadata(
        resource: Pick<ResourceStack, 'registryNamespace' | 'registryPath' | 'damage' | 'componentCount'>
    ): HTMLElement[];
};

export function createCraftingView(
    root: HTMLElement,
    application: ThemeApplication,
    workspace: HTMLElement,
    tooltip: CraftingTooltipPresenter,
    beforeDialog: (source: HTMLElement) => HTMLElement,
    lifetime: ThemeLifetime,
    settings: ThemeSettings
) {
    const dialog = createCraftingDialog(application, beforeDialog, lifetime);
    lifetime.onCleanup(dialog.dispose);
    const planView = element('section');
    planView.hidden = true;
    planView.className = 'crafting-plan';
    const header = element('div');
    header.className = 'terminal-heading';
    const title = element('h2');
    const searchLabel = element('label');
    searchLabel.className = 'search';
    const searchName = element('span');
    searchName.className = 'sr-only';
    const search = element('input');
    search.type = 'search';
    searchLabel.append(searchName, search);
    header.append(title, searchLabel);
    const planBody = element('div');
    planBody.className = 'terminal-body';
    const scroll = element('div');
    scroll.className = 'terminal-scroll';
    scroll.role = 'region';
    scroll.tabIndex = 0;
    const grid = element('ul');
    grid.className = 'resource-grid cpu-items';
    scroll.append(grid);
    const tree = createCraftingTreeView(application, tooltip);
    planBody.append(scroll, tree.element);
    const slots = createSlotGrid(grid, scroll, planView);
    lifetime.onCleanup(slots.dispose);
    const icons = application.icons.observe(scroll, paintResourceIcon);
    const tools = element('div');
    tools.className = 'terminal-tools plan-tools';
    const sortGroup = element('div');
    sortGroup.role = 'group';
    const sort = element('button');
    sort.type = 'button';
    sort.className = 'tool-button';
    const direction = element('button');
    direction.type = 'button';
    direction.className = 'tool-button';
    sortGroup.append(sort, direction);
    for (const control of [sort, direction])
        tooltip.bind(control, () => [element('span', control.getAttribute('aria-label') || '')]);
    tooltip.bind(search, () =>
        (search.getAttribute('aria-description') || '').split('\n').map((line) => element('span', line))
    );
    tools.append(sortGroup);
    workspace.insertBefore(tools, root);
    const criteria: PlanSort[] =
        application.state.capabilities.craftingPlanSteps === true
            ? ['name', 'quantity', 'steps']
            : ['name', 'quantity'];
    const effectiveSort = (): PlanSort =>
        criteria.includes(application.state.crafting.sort) ? application.state.crafting.sort : 'name';
    sort.addEventListener('click', () => {
        scroll.scrollTop = 0;
        application.crafting.sort(criteria[(criteria.indexOf(effectiveSort()) + 1) % criteria.length]);
    });
    direction.addEventListener('click', () => {
        scroll.scrollTop = 0;
        application.crafting.reverse();
    });
    search.addEventListener('input', () => {
        tooltip.hide();
        scroll.scrollTop = 0;
        application.crafting.search(search.value);
    });
    const panel = createPlanPanel(application, tooltip);
    lifetime.onCleanup(panel.dispose);
    workspace.append(panel.element);
    const treeExporter = createTreeExportDialog(application, settings, beforeDialog);
    lifetime.onCleanup(treeExporter.dispose);
    const actions = createPlanActions(application, workspace, tooltip, settings, beforeDialog, treeExporter);
    lifetime.onCleanup(actions.dispose);
    planView.append(header, planBody, actions.element);
    root.append(planView);
    let lastRows: ThemeState['crafting']['plan'] | undefined;
    let lastIcons: ThemeState['crafting']['icons'] | undefined;
    let lastPresentation = '';
    let lastLocale: Translator | undefined;
    let planRoute = '';

    function createResourceRow(row: ReadonlyData<PlanItem>, locale: Translator, showSteps: boolean) {
        const { common: t, number } = locale;
        const li = element('li');
        const button = element('button');
        button.type = 'button';
        button.className = 'item cpu-item slot-frame';
        button.dataset.crafting = row.missing > 0 ? 'missing' : row.requested > 0 ? 'pending' : 'stored';
        const name = element('strong');
        name.append(renderMinecraftText(row.displayName));
        const amounts = element('span');
        amounts.className = 'cpu-item-amounts';
        const counts: [string, number][] = [
            ['missingAmount', row.missing],
            ['requested', row.requested],
            ['planSteps', showSteps ? row.steps : 0],
            ['stored', row.stored]
        ];
        const values = counts.filter(([, value]) => value > 0);
        const storagePercent = row.usedPercent > 0 ? ` (${Math.round(row.usedPercent * 100)}%)` : '';
        for (const [key, value] of values) {
            const amount = element('span');
            amount.className = 'cpu-amount';
            const quantity = element('span', slotQuantity(value, locale));
            quantity.className = 'quantity';
            if (key === 'stored' && storagePercent) {
                const percent = element('span', storagePercent);
                percent.className = `storage-use storage-use-${storageUseLevel(row.usedPercent)}`;
                quantity.append(percent);
            }
            amount.append(element('span', t(key)), quantity);
            amounts.append(amount);
        }
        const description = [
            plainMinecraftText(row.displayName),
            ...values.map(([key, value]) => `${t(key)}: ${number(value)}${key === 'stored' ? storagePercent : ''}`)
        ];
        button.setAttribute('aria-label', description.join(' · '));
        tooltip.bind(button, () => {
            const name = element('strong');
            name.append(renderMinecraftText(row.displayName));
            return [
                name,
                ...tooltip.metadata(row),
                ...values.map(([key, value]) =>
                    element(
                        'span',
                        `${t(key === 'planSteps' ? 'steps' : key)}: ${number(value)}${key === 'stored' ? storagePercent : ''}`
                    )
                )
            ];
        });
        button.append(createResourceIcon(), name, amounts);
        li.append(button);
        return { li, button, metricCount: values.length };
    }

    return {
        open: dialog.open,
        order: dialog.order,

        render(terminal: ThemeState, locale: Translator, enabled: boolean) {
            const { route, crafting: state } = terminal;
            dialog.render(terminal, enabled);
            const nextRoute = route.view === 'plan' ? `${route.gridKey}/${route.planId}` : '';
            if (planRoute && planRoute !== nextRoute) {
                tooltip.hide();
                actions.close();
            }
            planRoute = nextRoute;
            const t = locale.common;
            planView.hidden = route.view !== 'plan';
            tools.hidden = route.view !== 'plan';
            panel.element.hidden = route.view !== 'plan';
            if (route.view !== 'plan') {
                icons.update([], null);
                panel.clearIcons();
                return;
            }
            tree.render(state.tree, locale);
            scroll.hidden = state.tree.visible;
            searchLabel.hidden = state.tree.visible;
            sortGroup.hidden = state.tree.visible;
            title.textContent = t('craftingPlan');
            panel.render(state, locale);
            searchName.textContent = t('searchPlanResources');
            search.placeholder = t('searchHint');
            search.setAttribute('aria-description', t('searchHelp'));
            if (search.value !== state.search) search.value = state.search;
            grid.setAttribute('aria-label', t('planResources'));
            scroll.setAttribute('aria-label', t('planResources'));
            const sortBy = effectiveSort();
            const sortKey = { name: 'name', quantity: 'quantity', steps: 'steps' }[sortBy];
            const sortIcon = {
                name: terminalIcons.name,
                quantity: terminalIcons.quantity,
                steps: craftingHammer
            }[sortBy];
            sort.innerHTML = sortIcon;
            sort.setAttribute('aria-label', `${t('sort')}: ${t(sortKey)}`);
            direction.innerHTML = terminalIcons[state.descending ? 'descending' : 'ascending'];
            direction.setAttribute(
                'aria-label',
                `${t('sortOrder')}: ${t(state.descending ? 'descending' : 'ascending')}`
            );
            sortGroup.setAttribute('aria-label', t('sort'));
            const presentation = `${state.search}/${sortBy}/${state.descending}`;
            if (
                lastRows !== state.plan ||
                lastIcons !== state.icons ||
                lastLocale !== locale ||
                presentation !== lastPresentation
            ) {
                lastRows = state.plan;
                lastIcons = state.icons;
                lastLocale = locale;
                lastPresentation = presentation;
                const language = document.documentElement.lang;
                const rows = selectPlanResources(state.plan?.plan || [], {
                    search: state.search,
                    language,
                    sort: sortBy,
                    sortOrder: state.descending ? 'descending' : 'ascending'
                });
                // Let the shared grid own its decorative cells across updates.
                for (const row of [...grid.children]) if (!row.classList.contains('empty-slot')) row.remove();
                let metricRows = 0;
                const targets = rows.map((row) => {
                    const entry = createResourceRow(row, locale, terminal.capabilities.craftingPlanSteps === true);
                    metricRows = Math.max(metricRows, entry.metricCount);
                    grid.insertBefore(entry.li, grid.querySelector('.empty-slot'));
                    return { element: entry.button, icon: row.icon };
                });
                grid.classList.toggle('plan-four-metrics', metricRows > 3);
                slots.update(rows.length);
                icons.update(targets, state.icons);
            }
            actions.render(state, locale);
        },
        dispose() {
            dialog.dispose();
            tree.dispose();
            treeExporter.dispose();
            icons.dispose();
            slots.dispose();
            tools.remove();
            panel.dispose();
            planView.remove();
            actions.dispose();
        }
    };
}
