import { registryId } from './resource-metadata.js';
import type { StoredResource } from '../../app/api-types.js';
import type { TerminalState, createTerminal } from '../../app/terminal.js';
import type { Translator as Locale } from '../../app/i18n.js';
type Terminal = ReturnType<typeof createTerminal>;

import { createSlotGrid } from './slot-grid.js';
import { createResourceIcon, paintResourceIcon } from './resource-icon.js';
import { slotQuantity } from './resource-quantity.js';
import { terminalIcons, craftingQueue } from './icons/pixel/terminal.js';
import type { PlanSort } from '../../app/crafting.js';
import { plainMinecraftText } from '../../app/minecraft-text.js';
import { renderMinecraftText } from './minecraft-text.js';
import { parseCraftingQuantity } from '../../app/crafting-quantity.js';

function element<Tag extends keyof HTMLElementTagNameMap>(tag: Tag, text = ''): HTMLElementTagNameMap[Tag] {
    const node = document.createElement(tag);
    node.textContent = text;
    return node;
}

export function createCraftingView(root: HTMLElement, application: Terminal, workspace: HTMLElement) {
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
    order.append(quantityLabel, adjustments, automaticLabel, lightLabel, calculate, orderMessage);
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
        opener = source;
        quantity.value = '1';
        quantity.setCustomValidity('');
        quantity.removeAttribute('aria-invalid');
        automatic.checked = false;
        lightMode.checked = false;
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
            application.state.capabilities.craftingLightMode === true && lightMode.checked
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
    grid.className = 'resource-grid cpu-items plan-items';
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
    tools.append(sortGroup);
    workspace.insertBefore(tools, root);
    const criteria: PlanSort[] = ['name', 'stored', 'requested', 'missing'];
    sort.addEventListener('click', () => {
        scroll.scrollTop = 0;
        application.crafting.sort(criteria[(criteria.indexOf(application.state.crafting.sort) + 1) % criteria.length]);
    });
    direction.addEventListener('click', () => {
        scroll.scrollTop = 0;
        application.crafting.reverse();
    });
    search.addEventListener('input', () => {
        scroll.scrollTop = 0;
        application.crafting.search(search.value);
    });
    const panel = element('aside');
    panel.id = 'plan-panel';
    const cpuTitle = element('h3');
    const output = element('p');
    const status = element('p');
    status.role = 'status';
    const reason = element('p');
    const bytes = element('p');
    const mergeHint = element('p');
    const cpuHint = element('p');
    const cpuList = element('div');
    cpuList.className = 'plan-cpu-list';
    cpuList.role = 'radiogroup';
    panel.append(output, status, reason, bytes, cpuTitle, mergeHint, cpuHint, cpuList);
    workspace.append(panel);
    const start = element('button');
    start.type = 'button';
    start.addEventListener('click', () => application.crafting.submit());
    const remove = element('button');
    remove.type = 'button';
    remove.addEventListener('click', () => application.crafting.remove());
    const actions = element('div');
    actions.className = 'plan-actions';
    actions.append(start, remove);
    planView.append(header, planBody, actions);
    root.append(planView);
    const cpuRows = new Map<
        string,
        { label: HTMLLabelElement; input: HTMLInputElement; name: HTMLElement; details: HTMLElement }
    >();
    let lastRows: TerminalState['crafting']['plan'] | undefined;
    let lastIcons: TerminalState['crafting']['icons'] | undefined;
    let lastPresentation = '';
    let lastLocale: Locale | undefined;

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
            calculate.textContent = locale.common('calculatePlan');
            calculate.disabled = !!state.mutation || !!state.uncertain || state.status === 'calculating';
            orderMessage.textContent = state.uncertain
                ? locale.common('uncertainCreate')
                : state.error
                  ? locale.common(state.error)
                  : '';
            terminalMessage.textContent = state.uncertain
                ? locale.common('uncertainCreate')
                : state.error
                  ? locale.common(state.error)
                  : state.mutation || ['calculating', 'submitted'].includes(state.status)
                    ? locale.common(state.mutation ? 'calculating' : state.status)
                    : '';
            terminalMessage.hidden = dialog.open || !terminalMessage.textContent;
            return orderControls;
        },

        render(terminal: TerminalState, locale: Locale, enabled: boolean) {
            const { route, crafting: state } = terminal;
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
            if (state.metadata)
                output.append(renderMinecraftText(state.metadata.displayName), ` × ${number(state.metadata.quantity)}`);
            status.textContent = state.uncertain
                ? t(state.uncertain === 'delete' ? 'uncertainDelete' : 'uncertainSubmit')
                : state.error
                  ? t(state.error)
                  : state.plan?.isDone && (state.plan.isSimulating || state.plan.plan?.some((row) => row.missing > 0))
                    ? t('simulation')
                    : t(state.status);
            reason.textContent = state.errorDetail || '';
            bytes.textContent = state.plan?.isDone ? t('planBytes', { count: state.plan.bytesTotal }) : '';
            searchName.textContent = t('searchPlanResources');
            search.placeholder = t('searchHint');
            search.setAttribute('aria-description', t('searchHelp'));
            if (search.value !== state.search) search.value = state.search;
            grid.setAttribute('aria-label', t('planResources'));
            scroll.setAttribute('aria-label', t('planResources'));
            const sortKey = { name: 'name', stored: 'consumed', requested: 'requested', missing: 'missingAmount' }[
                state.sort
            ];
            const sortIcon = {
                name: terminalIcons.name,
                stored: terminalIcons.stored,
                requested: craftingQueue,
                missing: terminalIcons.quantity
            }[state.sort];
            sort.innerHTML = sortIcon;
            sort.setAttribute('aria-label', `${t('sort')}: ${t(sortKey)}`);
            direction.innerHTML = terminalIcons[state.descending ? 'descending' : 'ascending'];
            direction.setAttribute(
                'aria-label',
                `${t('sortOrder')}: ${t(state.descending ? 'descending' : 'ascending')}`
            );
            for (const control of [sort, direction]) control.title = control.getAttribute('aria-label') || '';
            sortGroup.setAttribute('aria-label', t('sort'));
            const presentation = `${state.search}/${state.sort}/${state.descending}`;
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
                        const names = plainMinecraftText(a.displayName).localeCompare(
                            plainMinecraftText(b.displayName),
                            language
                        );
                        return (
                            (state.sort === 'name' ? names : a[state.sort] - b[state.sort]) *
                                (state.descending ? -1 : 1) || names
                        );
                    });
                // Let the shared grid own its decorative cells across updates.
                for (const row of [...grid.children]) if (!row.classList.contains('empty-slot')) row.remove();
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
                    const values = [
                        ['consumed', row.stored],
                        ['requested', row.requested],
                        ['missingAmount', row.missing]
                    ] as const;
                    for (const [key, value] of values) {
                        const amount = element('span');
                        amount.className = 'cpu-amount';
                        const quantity = element('span', slotQuantity(value, locale));
                        quantity.className = 'quantity';
                        amount.append(element('span', t(key)), quantity);
                        amounts.append(amount);
                    }
                    const description = [
                        plainMinecraftText(row.displayName),
                        ...values.map(([key, value]) => `${t(key)}: ${number(value)}`)
                    ];
                    button.setAttribute('aria-label', description.join(' · '));
                    button.title = [
                        ...description,
                        registryId(row),
                        `${t('steps')}: ${number(row.steps)}`,
                        `${t('storageUse')}: ${number(row.usedPercent * 100)}%`
                    ]
                        .filter(Boolean)
                        .join('\n');
                    button.append(createResourceIcon(), name, amounts);
                    li.append(button);
                    grid.insertBefore(li, grid.querySelector('.empty-slot'));
                    return { element: button, icon: row.icon };
                });
                slots.update(rows.length);
                icons.update(targets, state.icons);
            }
            cpuTitle.textContent = t('craftingCpu');
            cpuList.setAttribute('aria-label', t('craftingCpu'));
            cpuList.hidden = state.status !== 'ready';
            mergeHint.textContent =
                state.plan?.isDone && state.cpus.some((cpu) => cpu.isBusy && cpu.acceptsPlayerJobs) && !state.metadata
                    ? t('idleOnly')
                    : '';
            cpuHint.textContent =
                state.status === 'ready' && !state.cpus.some((cpu) => cpu.eligible) ? t('noEligibleCpu') : '';
            const remaining = new Set(cpuRows.keys());
            for (const cpu of state.cpus) {
                let row = cpuRows.get(cpu.key);
                if (!row) {
                    const label = element('label');
                    label.className = 'inset-frame plan-cpu';
                    const input = element('input');
                    input.type = 'radio';
                    input.name = 'plan-cpu';
                    input.value = cpu.key;
                    input.addEventListener('change', () => application.crafting.selectCpu(cpu.key));
                    const name = element('strong');
                    const details = element('span');
                    label.append(input, name, details);
                    row = { label, input, name, details };
                    cpuRows.set(cpu.key, row);
                    cpuList.append(label);
                }
                row.name.replaceChildren(renderMinecraftText(cpu.name || t('cpuUnnamed')));
                const description = `${number(cpu.availableStorage)} B · ${t('coprocessors', { count: cpu.coProcessors })} · ${t(cpu.isBusy ? 'cpuBusy' : 'cpuIdle')}`;
                row.details.textContent = description;
                row.label.title = cpu.key;
                row.input.setAttribute(
                    'aria-label',
                    `${plainMinecraftText(cpu.name || t('cpuUnnamed'))} · ${cpu.key} · ${description}`
                );
                row.input.disabled = !cpu.eligible || !!state.mutation || !!state.uncertain;
                row.input.checked = state.selectedCpu === cpu.key;
                remaining.delete(cpu.key);
            }
            for (const key of remaining) {
                cpuRows.get(key)!.label.remove();
                cpuRows.delete(key);
            }
            start.textContent = t('startCrafting');
            start.hidden = state.status !== 'ready';
            start.disabled = !!state.mutation || !!state.uncertain || !state.selectedCpu;
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
        }
    };
}
