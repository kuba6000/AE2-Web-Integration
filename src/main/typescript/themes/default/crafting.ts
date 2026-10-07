import { registryId } from './resource-metadata.js';
import type { StoredResource } from '../../app/api-types.js';
import type { TerminalState, createTerminal } from '../../app/terminal.js';
import type { Translator as Locale } from '../../app/i18n.js';
type Terminal = ReturnType<typeof createTerminal>;

import { cpuHref } from '../../app/router.js';
import { plainMinecraftText } from '../../app/minecraft-text.js';
import { renderMinecraftText } from './minecraft-text.js';

function element<Tag extends keyof HTMLElementTagNameMap>(tag: Tag, text = ''): HTMLElementTagNameMap[Tag] {
    const node = document.createElement(tag);
    node.textContent = text;
    return node;
}

export function createCraftingView(root: HTMLElement, application: Terminal) {
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
    const craft = element('button');
    craft.type = 'button';
    const order = element('form');
    const quantityLabel = element('label');
    const quantityText = element('span');
    const quantity = element('input');
    quantity.type = 'number';
    quantity.min = '1';
    quantity.max = String(Number.MAX_SAFE_INTEGER);
    quantity.step = '1';
    quantity.value = '1';
    quantity.required = true;
    quantityLabel.append(quantityText, quantity);
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
    order.append(quantityLabel, automaticLabel, lightLabel, calculate, orderMessage);
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
        automatic.checked = false;
        lightMode.checked = false;
        submitted = false;
        product.replaceChildren(renderMinecraftText(selected.displayName));
        if (!dialog.open) dialog.showModal();
        terminalMessage.hidden = true;
        quantity.focus();
        quantity.select();
    }
    craft.addEventListener('click', () => open(item, craft));
    close.addEventListener('click', () => dialog.close());
    dialog.addEventListener('close', () => {
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
        submitted = true;
        void application.crafting.create(
            item,
            Number(quantity.value),
            automatic.checked,
            application.state.capabilities.craftingLightMode === true && lightMode.checked
        );
    });

    const planView = element('section');
    planView.hidden = true;
    planView.className = 'crafting-plan';
    const title = element('h2');
    const output = element('p');
    const status = element('p');
    status.role = 'status';
    const reason = element('p');
    const bytes = element('p');
    const table = element('table');
    const tableScroll = element('div');
    tableScroll.className = 'plan-table';
    tableScroll.append(table);
    const head = element('thead');
    const body = element('tbody');
    table.append(head, body);
    const cpuLabel = element('label');
    const cpuText = element('span');
    const select = element('select');
    const mergeHint = element('p');
    const cpuHint = element('p');
    cpuLabel.append(cpuText, select);
    select.addEventListener('change', () => application.crafting.selectCpu(select.value));
    const start = element('button');
    start.type = 'button';
    start.addEventListener('click', () => application.crafting.submit());
    const remove = element('button');
    remove.type = 'button';
    remove.addEventListener('click', () => application.crafting.remove());
    const back = element('a');
    const inspectCpu = element('a');
    const actions = element('div');
    actions.className = 'plan-actions';
    actions.append(start, remove, inspectCpu, back);
    planView.append(title, output, status, reason, bytes, tableScroll, mergeHint, cpuHint, cpuLabel, actions);

    let lastPlan: TerminalState['crafting']['plan'] | undefined;

    let lastLocale: Locale | undefined;
    root.append(planView);
    return {
        open,
        order(selected: StoredResource, state: TerminalState['crafting'], locale: Locale) {
            if (!dialog.open) item = selected;
            craft.textContent = locale.common('craft');
            dialogTitle.textContent = locale.common('craft');
            close.setAttribute('aria-label', locale.common('close'));
            quantityText.textContent = locale.common('craftQuantity');
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

        render(terminal: TerminalState, locale: Locale) {
            const { route, crafting: state } = terminal;
            if (dialog.open && submitted && ['calculating', 'submitted'].includes(state.status)) dialog.close();
            if (route.view !== 'items' || route.gridKey !== gridKey || terminal.itemStatus === 'error') {
                opener = null;
                if (dialog.open) dialog.close();
                product.replaceChildren();
            }
            const { common: t, number } = locale;
            planView.hidden = route.view !== 'plan';
            if (route.view !== 'plan') return;
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
            back.textContent = t('backResources');
            back.href = `#/grids/${encodeURIComponent(route.gridKey)}/items`;
            inspectCpu.textContent = t('inspectCpu');
            inspectCpu.href = cpuHref(route.gridKey, state.selectedCpu);
            bytes.textContent = state.plan?.isDone ? t('planBytes', { count: state.plan.bytesTotal }) : '';
            tableScroll.hidden = !state.plan?.isDone;
            if (lastPlan !== state.plan || lastLocale !== locale) {
                lastPlan = state.plan;
                lastLocale = locale;
                const headings = element('tr');
                for (const key of ['resource', 'consumed', 'requested', 'missingAmount', 'steps', 'storageUse']) {
                    const th = element('th', t(key));
                    th.scope = 'col';
                    headings.append(th);
                }
                head.replaceChildren(headings);
                body.replaceChildren(
                    ...(state.plan?.plan || []).map((row) => {
                        const tr = element('tr');
                        const name = element('td');
                        name.append(
                            renderMinecraftText(row.displayName),
                            ...(registryId(row) ? [element('code', registryId(row))] : [])
                        );
                        tr.append(name);
                        for (const value of [row.stored, row.requested, row.missing, row.steps])
                            tr.append(element('td', number(value)));
                        tr.append(element('td', row.usedPercent > 0 ? `${number(row.usedPercent * 100)}%` : '—'));
                        return tr;
                    })
                );
            }
            cpuText.textContent = t('craftingCpu');
            cpuLabel.hidden = state.status !== 'ready';
            mergeHint.textContent = state.plan?.isDone && !state.metadata ? t('idleOnly') : '';
            cpuHint.textContent =
                state.status === 'ready' && !state.cpus.some((cpu) => cpu.eligible) ? t('noEligibleCpu') : '';
            const options = [
                { key: '', label: t('chooseCpu'), eligible: true },
                ...state.cpus.map((cpu) => ({
                    ...cpu,
                    label: `${plainMinecraftText(cpu.name)} · ${cpu.key} · ${number(cpu.availableStorage)} B · ${t('coprocessors', { count: cpu.coProcessors })} · ${t(cpu.isBusy ? 'cpuBusy' : 'cpuIdle')}`
                }))
            ];
            const current = new Map([...select.options].map((option) => [option.value, option]));
            for (const cpu of options) {
                const option = current.get(cpu.key) || element('option');
                option.value = cpu.key;
                option.textContent = cpu.label;
                option.disabled = !cpu.eligible;
                if (!option.parentNode) select.append(option);
                current.delete(cpu.key);
            }
            for (const option of current.values()) option.remove();
            select.value = state.selectedCpu;
            start.textContent = t('startCrafting');
            start.hidden = state.status !== 'ready';
            start.disabled = !!state.mutation || !!state.uncertain || !state.selectedCpu;
            remove.textContent = t('deleteCalculation');
            remove.hidden = ['submitted', 'deleted', 'unavailable', 'error'].includes(state.status);
            remove.disabled = !!state.mutation || !!state.uncertain;
        },
        dispose() {
            opener = null;
            dialog.close();
            dialog.remove();
        }
    };
}
