function element(tag, text = '') {
    const node = document.createElement(tag);
    node.textContent = text;
    return node;
}

export function createCraftingView(root, application) {
    const order = element('form');
    const quantityLabel = element('label');
    const quantityText = element('span');
    const quantity = element('input');
    quantity.type = 'number'; quantity.min = '1'; quantity.max = String(Number.MAX_SAFE_INTEGER); quantity.step = '1'; quantity.value = '1';
    quantity.required = true;
    quantityLabel.append(quantityText, quantity);
    const calculate = element('button'); calculate.type = 'submit';
    const orderMessage = element('p'); orderMessage.role = 'status';
    order.append(quantityLabel, calculate, orderMessage);
    let item;
    order.addEventListener('submit', event => { event.preventDefault(); application.crafting.create(item, Number(quantity.value)); });

    const planView = element('section'); planView.hidden = true; planView.className = 'crafting-plan';
    const title = element('h2');
    const output = element('p');
    const status = element('p'); status.role = 'status';
    const reason = element('p');
    const bytes = element('p');
    const table = element('table');
    const tableScroll = element('div'); tableScroll.className = 'plan-table'; tableScroll.append(table);
    const head = element('thead'); const body = element('tbody'); table.append(head, body);
    const cpuLabel = element('label'); const cpuText = element('span'); const select = element('select');
    const mergeHint = element('p');
    const cpuHint = element('p');
    cpuLabel.append(cpuText, select);
    select.addEventListener('change', () => application.crafting.selectCpu(select.value));
    const start = element('button'); start.type = 'button'; start.addEventListener('click', () => application.crafting.submit());
    const remove = element('button'); remove.type = 'button'; remove.addEventListener('click', () => application.crafting.remove());
    const back = element('a');
    const actions = element('div'); actions.className = 'plan-actions'; actions.append(start, remove, back);
    planView.append(title, output, status, reason, bytes, tableScroll, mergeHint, cpuHint, cpuLabel, actions);
    let lastPlan;
    let lastLocale;
    root.append(planView);
    return {
        order(selected, state, locale) {
            if (item?.itemKey !== selected?.itemKey) quantity.value = '1';
            item = selected;
            quantityText.textContent = locale.t('craftQuantity'); calculate.textContent = locale.t('calculatePlan');
            calculate.disabled = !!state.mutation || !!state.uncertain;
            orderMessage.textContent = state.uncertain ? locale.t('uncertainCreate') : state.error ? locale.t(state.error) : '';
            return order;
        },
        render(route, state, locale) {
            const {t, number} = locale;
            planView.hidden = route.view !== 'plan';
            if (planView.hidden) return;
            title.textContent = t('craftingPlan');
            output.textContent = state.metadata ? `${state.metadata.itemname} × ${number(state.metadata.quantity)}` : '';
            status.textContent = state.uncertain ? t(state.uncertain === 'delete' ? 'uncertainDelete' : 'uncertainSubmit') : state.error ? t(state.error) : state.plan?.isDone && (state.plan.isSimulating || state.plan.plan?.some(row => row.missing > 0))
                ? t('simulation') : t(state.status);
            reason.textContent = state.errorDetail || '';
            back.textContent = t('backResources'); back.href = `#/grids/${encodeURIComponent(route.gridKey)}/items`;
            bytes.textContent = state.plan?.isDone ? t('planBytes', {count: state.plan.bytesTotal}) : '';
            tableScroll.hidden = !state.plan?.isDone;
            if (lastPlan !== state.plan || lastLocale !== locale) {
                lastPlan = state.plan; lastLocale = locale;
                const headings = element('tr');
                for (const key of ['resource', 'consumed', 'requested', 'missingAmount', 'steps', 'storageUse']) { const th = element('th', t(key)); th.scope = 'col'; headings.append(th); }
                head.replaceChildren(headings);
                body.replaceChildren(...(state.plan?.plan || []).map(row => {
                    const tr = element('tr');
                    const name = element('td', row.itemname); name.append(element('code', row.itemid)); tr.append(name);
                    for (const value of [row.stored, row.requested, row.missing, row.steps]) tr.append(element('td', number(value)));
                    tr.append(element('td', row.usedPercent > 0 ? `${number(row.usedPercent * 100)}%` : '—'));
                    return tr;
                }));
            }
            cpuText.textContent = t('craftingCpu');
            cpuLabel.hidden = state.status !== 'ready';
            mergeHint.textContent = state.plan?.isDone && !state.metadata ? t('idleOnly') : '';
            cpuHint.textContent = state.status === 'ready' && !state.cpus.some(cpu => cpu.eligible) ? t('noEligibleCpu') : '';
            const options = [{key: '', label: t('chooseCpu'), eligible: true}, ...state.cpus.map(cpu => ({...cpu,
                label: `${cpu.name} · ${cpu.key} · ${number(cpu.availableStorage)} B · ${t('coprocessors', {count: cpu.coProcessors})} · ${t(cpu.isBusy ? 'cpuBusy' : 'cpuIdle')}`}))];
            const current = new Map([...select.options].map(option => [option.value, option]));
            for (const cpu of options) {
                const option = current.get(cpu.key) || element('option');
                option.value = cpu.key; option.textContent = cpu.label; option.disabled = !cpu.eligible;
                if (!option.parentNode) select.append(option);
                current.delete(cpu.key);
            }
            for (const option of current.values()) option.remove();
            select.value = state.selectedCpu;
            start.textContent = t('startCrafting'); start.hidden = state.status !== 'ready';
            start.disabled = !!state.mutation || !!state.uncertain || !state.selectedCpu;
            remove.textContent = t('deleteCalculation'); remove.hidden = ['submitted', 'deleted', 'unavailable', 'error'].includes(state.status);
            remove.disabled = !!state.mutation || !!state.uncertain;
        }
    };
}
