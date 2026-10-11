import { registryId } from './resource-metadata.js';
import type { StoredResource, ResourceStack } from '../../app/api-types.js';
import type { TerminalState, createTerminal } from '../../app/terminal.js';
import type { Translator as Locale } from '../../app/i18n.js';
type Terminal = ReturnType<typeof createTerminal>;

import { createSlotGrid } from './slot-grid.js';
import { createResourceIcon, paintResourceIcon } from './resource-icon.js';
import { slotQuantity } from './resource-quantity.js';
import { terminalIcons, craftingHammer } from './icons/pixel/terminal.js';
import type { PlanSort } from '../../app/crafting.js';
import { plainMinecraftText } from '../../app/minecraft-text.js';
import { renderMinecraftText } from './minecraft-text.js';
import { parseCraftingQuantity } from '../../app/crafting-quantity.js';

function storageUseLevel(fraction: number) {
    if (fraction <= 0.25) return 'low';
    if (fraction <= 0.5) return 'medium';
    if (fraction <= 0.75) return 'high';
    return 'critical';
}

function element<Tag extends keyof HTMLElementTagNameMap>(tag: Tag, text = ''): HTMLElementTagNameMap[Tag] {
    const node = document.createElement(tag);
    node.textContent = text;
    return node;
}

export type CraftingTooltipPresenter = {
    bind(target: HTMLElement, content: () => Node[], focusTarget?: HTMLElement): void;
    hide(): void;
    metadata(
        resource: Pick<ResourceStack, 'registryNamespace' | 'registryPath' | 'damage' | 'componentCount'>
    ): HTMLElement[];
};

export function createCraftingView(
    root: HTMLElement,
    application: Terminal,
    workspace: HTMLElement,
    tooltip: CraftingTooltipPresenter,
    beforeDialog: (source: HTMLElement) => HTMLElement
) {
    const dialog = element('dialog');
    dialog.className = 'window-frame crafting-dialog';
    dialog.setAttribute('aria-labelledby', 'craft-dialog-title');
    const dialogTitle = element('h2');
    dialogTitle.id = 'craft-dialog-title';
    const close = element('button', '×');
    close.type = 'button';
    close.className = 'close-dialog';
    const heading = element('div');
    heading.className = 'craft-dialog-heading';
    heading.append(dialogTitle, close);
    const product = element('p');
    product.className = 'craft-product';
    const productIcons = application.icons.observe(dialog, paintResourceIcon);
    const craft = element('button');
    craft.type = 'button';
    const order = element('form');
    const quantityLabel = element('label');
    const quantityText = element('span');
    const quantity = element('input');
    quantity.type = 'text';
    quantity.maxLength = 256;
    quantity.value = '1';
    quantity.required = true;
    quantityLabel.append(quantityText, quantity);
    const adjustments = element('div');
    adjustments.className = 'quantity-adjustments';
    let invalidQuantity = '';
    function readQuantity() {
        const value = parseCraftingQuantity(quantity.value);
        quantity.setAttribute('aria-invalid', String(value === null));
        quantity.setCustomValidity(value === null ? invalidQuantity : '');
        return value;
    }
    quantity.addEventListener('input', () => {
        quantity.setCustomValidity('');
        quantity.removeAttribute('aria-invalid');
    });
    for (const delta of [-1000, -64, -1, 1, 64, 1000]) {
        const button = element('button', `${delta > 0 ? '+' : ''}${delta}`);
        button.type = 'button';
        button.addEventListener('click', () => {
            const value = readQuantity();
            if (value !== null && Number.isSafeInteger(value + delta))
                quantity.value = String(Math.max(1, value + delta));
            else {
                quantity.setAttribute('aria-invalid', 'true');
                quantity.setCustomValidity(invalidQuantity);
                quantity.reportValidity();
            }
        });
        adjustments.append(button);
    }
    const calculate = element('button');
    calculate.type = 'submit';
    const orderMessage = element('p');
    orderMessage.role = 'status';
    const automatic = element('input');
    automatic.type = 'checkbox';
    const automaticText = element('span');
    const automaticLabel = element('label');
    automaticLabel.className = 'checkbox';
    automaticLabel.append(automatic, automaticText);
    const lightMode = element('input');
    lightMode.type = 'checkbox';
    const lightText = element('span');
    const lightLabel = element('label');
    lightLabel.className = 'checkbox';
    lightLabel.append(lightMode, lightText);
    const ignoreMissing = element('input');
    ignoreMissing.type = 'checkbox';
    const missingText = element('span');
    const missingLabel = element('label');
    missingLabel.className = 'checkbox';
    missingLabel.append(ignoreMissing, missingText);
    order.append(quantityLabel, adjustments, automaticLabel, lightLabel, missingLabel, calculate, orderMessage);
    const terminalMessage = element('p');
    terminalMessage.role = 'status';
    const orderControls = element('div');
    orderControls.append(craft, terminalMessage);

    let item: StoredResource;
    let opener: HTMLElement | null = null;
    let gridKey: string | null = null;
    let submitted = false;
    function open(selected: StoredResource, source: HTMLElement) {
        if (!selected.craftable || !selected.itemKey || application.state.route.view !== 'items') return;
        item = selected;
        gridKey = application.state.route.gridKey;
        opener = beforeDialog(source);
        quantity.value = '1';
        quantity.setCustomValidity('');
        quantity.removeAttribute('aria-invalid');
        automatic.checked = false;
        lightMode.checked = false;
        ignoreMissing.checked = false;
        submitted = false;
        product.replaceChildren(createResourceIcon(), renderMinecraftText(selected.displayName));
        if (!dialog.open) dialog.showModal();
        productIcons.update([{ element: product, icon: selected.icon }], application.state.itemIcons);
        terminalMessage.hidden = true;
        quantity.focus();
        quantity.select();
    }
    craft.addEventListener('click', () => open(item, craft));
    close.addEventListener('click', () => dialog.close());
    dialog.addEventListener('close', () => {
        productIcons.update([], null);
        terminalMessage.hidden = !terminalMessage.textContent;
        if (
            opener?.isConnected &&
            application.state.route.view === 'items' &&
            application.state.route.gridKey === gridKey
        )
            opener.focus({ preventScroll: true });
    });
    dialog.append(heading, product, order);
    document.body.append(dialog);
    order.addEventListener('submit', (event) => {
        event.preventDefault();
        const amount = readQuantity();
        if (amount === null) {
            quantity.reportValidity();
            return;
        }
        submitted = true;
        void application.crafting.create(
            item,
            amount,
            automatic.checked,
            application.state.capabilities.craftingLightMode === true && lightMode.checked,
            application.state.capabilities.craftingIgnoreMissing === true && ignoreMissing.checked
        );
    });

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
    planBody.append(scroll);
    const slots = createSlotGrid(grid, scroll, planView);
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
    workspace.append(panel);
    const start = element('button');
    start.type = 'button';
    start.addEventListener('click', () => application.crafting.submit());
    const more = element('button', '▾');
    more.type = 'button';
    more.setAttribute('aria-haspopup', 'menu');
    more.setAttribute('aria-expanded', 'false');
    const menu = element('div');
    menu.className = 'window-frame resource-menu plan-recalculate-menu';
    menu.role = 'menu';
    menu.popover = 'auto';
    menu.id = 'plan-recalculate-menu';
    more.setAttribute('aria-controls', menu.id);
    const variants = [
        { automatic: false, ignoreMissing: false, label: 'recalculate' },
        { automatic: true, ignoreMissing: false, label: 'recalculateAuto' },
        { automatic: false, ignoreMissing: true, label: 'recalculateMissing' },
        { automatic: true, ignoreMissing: true, label: 'recalculateMissingAuto' }
    ]
        .filter((variant) => !variant.ignoreMissing || application.state.capabilities.craftingIgnoreMissing === true)
        .map((variant) => {
            const button = element('button');
            button.type = 'button';
            button.role = 'menuitem';
            button.addEventListener('click', () => {
                menu.hidePopover();
                more.focus();
                void application.crafting.recalculate(variant.automatic, variant.ignoreMissing);
            });
            menu.append(button);
            return { ...variant, button };
        });
    menu.addEventListener('toggle', () => {
        more.setAttribute('aria-expanded', String(menu.matches(':popover-open')));
    });
    more.addEventListener('click', () => {
        menu.togglePopover();
        if (!menu.matches(':popover-open')) return;
        tooltip.hide();
        const bounds = more.getBoundingClientRect();
        menu.style.left = `${Math.max(8, Math.min(bounds.right - menu.offsetWidth, innerWidth - menu.offsetWidth - 8))}px`;
        menu.style.top = `${Math.max(8, bounds.top - menu.offsetHeight - 4)}px`;
        menu.querySelector('button')?.focus();
    });
    menu.addEventListener('keydown', (event) => {
        const buttons = [...menu.querySelectorAll('button')];
        const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
        let next = index;
        if (event.key === 'ArrowDown') next = (index + 1) % buttons.length;
        else if (event.key === 'ArrowUp') next = (index + buttons.length - 1) % buttons.length;
        else if (event.key === 'Home') next = 0;
        else if (event.key === 'End') next = buttons.length - 1;
        else if (event.key === 'Escape') {
            menu.hidePopover();
            more.focus();
            return;
        } else if (event.key === 'Tab') {
            menu.hidePopover();
            return;
        } else return;
        event.preventDefault();
        buttons[next]?.focus();
    });
    const startControls = element('div');
    startControls.className = 'plan-start-controls';
    startControls.append(start, more);
    workspace.append(menu);
    const remove = element('button');
    remove.type = 'button';
    remove.addEventListener('click', () => application.crafting.remove());
    const actions = element('div');
    actions.className = 'plan-actions';
    actions.append(startControls, remove);
    planView.append(header, planBody, actions);
    root.append(planView);
    const cpuRows = new Map<
        string,
        {
            label: HTMLLabelElement;
            input: HTMLInputElement;
            name: HTMLElement;
            details: HTMLElement;
            tooltipContent: () => Node[];
        }
    >();
    let lastRows: TerminalState['crafting']['plan'] | undefined;
    let lastIcons: TerminalState['crafting']['icons'] | undefined;
    let lastPresentation = '';
    let lastLocale: Locale | undefined;
    let planRoute = '';

    return {
        open,
        order(selected: StoredResource, state: TerminalState['crafting'], locale: Locale) {
            if (!dialog.open) item = selected;
            craft.textContent = locale.common('craft');
            dialogTitle.textContent = locale.common('craft');
            close.setAttribute('aria-label', locale.common('close'));
            quantityText.textContent = locale.common('craftQuantity');
            invalidQuantity = locale.common('invalidCraftQuantity');
            automaticText.textContent = locale.common('autostart');
            lightText.textContent = locale.common('craftingLightMode');
            lightLabel.hidden = application.state.capabilities.craftingLightMode !== true;
            missingText.textContent = locale.common('ignoreMissing');
            missingLabel.hidden = application.state.capabilities.craftingIgnoreMissing !== true;
            calculate.textContent = locale.common('calculatePlan');
            calculate.disabled = !!state.mutation || !!state.uncertain || state.status === 'calculating';
            orderMessage.textContent = '';
            terminalMessage.textContent = '';
            if (state.uncertain) {
                orderMessage.textContent = locale.common('uncertainCreate');
                terminalMessage.textContent = orderMessage.textContent;
            } else if (state.error) {
                orderMessage.textContent = locale.common(state.error);
                terminalMessage.textContent = orderMessage.textContent;
            } else if (state.mutation || ['calculating', 'submitted'].includes(state.status)) {
                terminalMessage.textContent = locale.common(state.mutation ? 'calculating' : state.status);
            }
            terminalMessage.hidden = dialog.open || !terminalMessage.textContent;
            return orderControls;
        },

        render(terminal: TerminalState, locale: Locale, enabled: boolean) {
            const { route, crafting: state } = terminal;
            const nextRoute = route.view === 'plan' ? `${route.gridKey}/${route.planId}` : '';
            if (planRoute && planRoute !== nextRoute) {
                tooltip.hide();
                menu.hidePopover();
            }
            planRoute = nextRoute;
            if (dialog.open && submitted && ['calculating', 'submitted'].includes(state.status)) dialog.close();
            if (route.view !== 'items' || route.gridKey !== gridKey || terminal.itemStatus === 'error') {
                opener = null;
                if (dialog.open) dialog.close();
                product.replaceChildren();
            }
            const { common: t, number } = locale;
            dialog.classList.toggle('resource-icons-enabled', enabled);
            productIcons.update(
                dialog.open
                    ? [
                          {
                              element: product,
                              icon: terminal.items.find((resource) => resource.itemKey === item?.itemKey)?.icon
                          }
                      ]
                    : [],
                terminal.itemIcons
            );
            planView.hidden = route.view !== 'plan';
            tools.hidden = route.view !== 'plan';
            panel.hidden = route.view !== 'plan';
            if (route.view !== 'plan') {
                icons.update([], null);
                return;
            }
            title.textContent = t('craftingPlan');
            output.replaceChildren();
            output.tabIndex = state.metadata ? 0 : -1;
            if (state.metadata)
                output.append(renderMinecraftText(state.metadata.displayName), ` × ${number(state.metadata.quantity)}`);
            status.textContent = t(state.status);
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
                const terms = state.search.trim().toLocaleLowerCase(language).split(/\s+/);
                const rows = (state.plan?.plan || [])
                    .filter((row) => {
                        const text = `${plainMinecraftText(row.displayName)} ${registryId(row)}`.toLocaleLowerCase(
                            language
                        );
                        return terms.every((term) =>
                            term.startsWith('@')
                                ? (row.registryNamespace || '').toLocaleLowerCase(language).includes(term.slice(1))
                                : text.includes(term)
                        );
                    })
                    .sort((a, b) => {
                        const groupA = a.missing > 0 ? 0 : a.requested > 0 ? 1 : 2;
                        const groupB = b.missing > 0 ? 0 : b.requested > 0 ? 1 : 2;
                        if (groupA !== groupB) return groupA - groupB;
                        const names = plainMinecraftText(a.displayName).localeCompare(
                            plainMinecraftText(b.displayName),
                            language
                        );
                        const amount =
                            sortBy === 'quantity'
                                ? a.stored + a.requested + a.missing - (b.stored + b.requested + b.missing)
                                : a.steps - b.steps;
                        return (sortBy === 'name' ? names : amount) * (state.descending ? -1 : 1) || names;
                    });
                // Let the shared grid own its decorative cells across updates.
                for (const row of [...grid.children]) if (!row.classList.contains('empty-slot')) row.remove();
                let metricRows = 0;
                const targets = rows.map((row) => {
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
                        ['planSteps', terminal.capabilities.craftingPlanSteps === true ? row.steps : 0],
                        ['stored', row.stored]
                    ];
                    const values = counts.filter(([, value]) => value > 0);
                    metricRows = Math.max(metricRows, values.length);
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
                        ...values.map(
                            ([key, value]) => `${t(key)}: ${number(value)}${key === 'stored' ? storagePercent : ''}`
                        )
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
                    grid.insertBefore(li, grid.querySelector('.empty-slot'));
                    return { element: button, icon: row.icon };
                });
                grid.classList.toggle('plan-four-metrics', metricRows > 3);
                slots.update(rows.length);
                icons.update(targets, state.icons);
            }
            cpuTitle.textContent = t('craftingCpu');
            cpuList.setAttribute('aria-label', t('craftingCpu'));
            cpuList.hidden = state.status !== 'ready';
            cpuHint.textContent =
                state.status === 'ready' && !state.cpus.some((cpu) => cpu.eligible) ? t('noEligibleCpu') : '';
            const remaining = new Set(cpuRows.keys());
            for (const cpu of state.cpus) {
                let row = cpuRows.get(cpu.key);
                if (!row) {
                    const label = element('label');
                    label.className = 'plan-cpu';
                    const input = element('input');
                    input.type = 'radio';
                    input.name = 'plan-cpu';
                    input.value = cpu.key;
                    input.addEventListener('change', () => application.crafting.selectCpu(cpu.key));
                    const name = element('strong');
                    name.className = 'cpu-selector-name';
                    const details = element('span');
                    label.append(input, name, details);
                    const entry = { label, input, name, details, tooltipContent: (): Node[] => [] };
                    tooltip.bind(label, () => entry.tooltipContent(), input);
                    row = entry;
                    cpuRows.set(cpu.key, row);
                    cpuList.append(label);
                }
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
                remaining.delete(cpu.key);
            }
            for (const key of remaining) {
                cpuRows.get(key)!.label.remove();
                cpuRows.delete(key);
            }
            start.textContent = t('startCrafting');
            start.disabled = state.status !== 'ready' || !!state.mutation || !!state.uncertain || !state.selectedCpu;
            more.setAttribute('aria-label', t('recalculateOptions'));
            menu.setAttribute('aria-label', t('recalculateOptions'));
            more.disabled =
                !state.metadata?.itemKey ||
                !!state.mutation ||
                !!state.uncertain ||
                !['ready', 'error'].includes(state.status);
            if (more.disabled) menu.hidePopover();
            const ordered = [...variants].sort((a, b) => {
                const current = state.metadata;
                const aMatches = a.automatic === current?.automatic && a.ignoreMissing === current?.ignoreMissing;
                const bMatches = b.automatic === current?.automatic && b.ignoreMissing === current?.ignoreMissing;
                return Number(bMatches) - Number(aMatches);
            });
            for (const [index, variant] of ordered.entries()) {
                variant.button.textContent = t(variant.label);
                if (menu.children[index] !== variant.button)
                    menu.insertBefore(variant.button, menu.children[index] || null);
            }
            remove.textContent = t('cancelPlan');
            remove.hidden = ['submitted', 'deleted', 'unavailable', 'error'].includes(state.status);
            remove.disabled = !!state.mutation || !!state.uncertain;
        },
        dispose() {
            opener = null;
            dialog.close();
            dialog.remove();
            productIcons.dispose();
            icons.dispose();
            slots.dispose();
            tools.remove();
            panel.remove();
            planView.remove();
            menu.remove();
        }
    };
}
