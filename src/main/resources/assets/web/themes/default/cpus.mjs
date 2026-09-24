import { cpuHref } from '../../app/router.mjs';

function element(tag, text = '') {
    const node = document.createElement(tag);
    node.textContent = text;
    return node;
}

/** Presentation only: CPU requests, cancellation and refresh lifetime belong to the application. */
export function createCpuView(root, application) {
    const view = element('section');
    view.hidden = true;
    view.className = 'cpu-view';
    const title = element('h2');
    const status = element('p');
    status.role = 'status';
    const list = element('ul');
    list.className = 'cpu-list';
    const heading = element('h3');
    const output = element('p');
    const timing = element('p');
    const empty = element('p');
    const tableScroll = element('div');
    tableScroll.className = 'plan-table';
    const table = element('table');
    tableScroll.append(table);
    const cancel = element('button');
    cancel.type = 'button';
    cancel.addEventListener('click', () => application.cpus.cancel());
    const resources = element('a');
    view.append(title, status, list, heading, output, timing, empty, tableScroll, cancel, resources);
    root.append(view);
    let rows = new Map();
    let lastDetail;
    let lastLocale;
    return {
        render(route, state, locale) {
            view.hidden = route.view !== 'cpus';
            if (view.hidden) return;
            const { t, number } = locale;
            title.textContent = t('cpus');
            resources.textContent = t('backResources');
            resources.href = `#/grids/${encodeURIComponent(route.gridKey)}/items`;
            const detail = state.detail;
            status.textContent = state.uncertain
                ? t('cpuCancelUncertain')
                : state.cancelling
                  ? t('cpuCancelling')
                  : state.error
                    ? t(state.error)
                    : state.status === 'loading'
                      ? t('loading')
                      : detail
                        ? t(detail.isBusy ? 'cpuWorking' : 'cpuIdleMessage')
                        : state.cpus.length
                          ? t('selectCpuWork')
                          : t('noCpus');
            if (state.notice && state.notice !== state.error) status.textContent += ` ${t(state.notice)}`;
            if (state.uncertain && state.error) status.textContent += ` ${t(state.error)}`;
            const current = new Map();
            for (const cpu of state.cpus) {
                const entry = rows.get(cpu.key) || {
                    li: element('li'),
                    link: element('a'),
                    summary: element('p'),
                    output: element('p')
                };
                if (!entry.link.parentNode) entry.li.append(entry.link, entry.summary, entry.output);
                entry.link.href = cpuHref(route.gridKey, cpu.key);
                entry.link.textContent = `${cpu.name} · ${cpu.key}`;
                if (route.cpuKey === cpu.key) entry.link.setAttribute('aria-current', 'page');
                else entry.link.removeAttribute('aria-current');
                entry.summary.textContent = `${t(cpu.isBusy ? 'cpuBusy' : 'cpuIdle')} · ${t('cpuCapacity', { count: cpu.availableStorage })} · ${t('coprocessors', { count: cpu.coProcessors })} · ${cpu.usedStorage >= 0 ? t('cpuUsedStorage', { count: cpu.usedStorage }) : t('cpuStorageUnknown')}`;
                entry.output.textContent = cpu.isBusy
                    ? cpu.finalOutput
                        ? `${cpu.finalOutput.itemname} × ${number(cpu.finalOutput.quantity)}`
                        : t('cpuOutputUnknown')
                    : '';
                if (!entry.li.parentNode) list.append(entry.li);
                current.set(cpu.key, entry);
            }
            for (const [key, entry] of rows) if (!current.has(key)) entry.li.remove();
            rows = current;
            const selected = state.cpus.find((cpu) => cpu.key === route.cpuKey);
            heading.textContent = route.cpuKey ? `${t('cpuWork')} · ${selected?.name || route.cpuKey}` : '';
            output.textContent = detail?.isBusy
                ? detail.finalOutput
                    ? `${t('cpuOutput')}: ${detail.finalOutput.itemname} × ${number(detail.finalOutput.quantity)}`
                    : t('cpuOutputUnknown')
                : '';
            timing.textContent = detail?.isBusy
                ? detail.hasTrackingInfo
                    ? `${t('cpuStarted', { time: locale.dateTime(detail.timeStarted) })} · ${t('cpuElapsed', { count: detail.timeElapsed / 1000 })}`
                    : t('cpuTrackingUnavailable')
                : '';
            tableScroll.hidden = !detail?.isBusy || !detail.items?.length;
            empty.textContent = detail?.isBusy && !detail.items?.length ? t('cpuNoResources') : '';
            if (lastDetail !== detail || lastLocale !== locale) {
                lastDetail = detail;
                lastLocale = locale;
                const header = element('thead');
                const headings = element('tr');
                header.append(headings);
                const columns = [
                    'resource',
                    'cpuActive',
                    'cpuPending',
                    'cpuStored',
                    ...(detail?.hasTrackingInfo
                        ? ['cpuTimeSpent', 'cpuCraftedTotal', 'cpuRate', 'cpuElapsedShare', 'cpuProcessingShare']
                        : [])
                ];
                for (const key of columns) {
                    const th = element('th', t(key));
                    th.scope = 'col';
                    headings.append(th);
                }
                const body = element('tbody');
                for (const item of detail?.items || []) {
                    const row = element('tr');
                    const name = element('td', item.itemname);
                    name.append(element('code', item.itemid));
                    row.append(name);
                    const values = [number(item.active), number(item.pending), number(item.stored)];
                    if (detail.hasTrackingInfo)
                        values.push(
                            t('seconds', { count: item.timeSpentCrafting / 1000 }),
                            number(item.craftedTotal),
                            `${number(item.craftsPerSec)}/s`,
                            `${number(item.shareInCraftingTimeCombined * 100)}%`,
                            `${number(item.shareInCraftingTime * 100)}%`
                        );
                    for (const value of values) row.append(element('td', value));
                    body.append(row);
                }
                table.replaceChildren(header, body);
            }
            cancel.textContent = t('cancelCpuWork');
            cancel.hidden = !detail?.isBusy;
            cancel.disabled = state.cancelling || state.uncertain || state.status !== 'ready';
        }
    };
}
