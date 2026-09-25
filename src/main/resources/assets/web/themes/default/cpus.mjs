/**
 * @typedef {ReturnType<typeof import('../../app/terminal.mjs').createTerminal>} Terminal
 * @typedef {Terminal['state']} TerminalState
 * @typedef {import('../../app/i18n.mjs').Translator} Locale
 */

import { cpuHref } from '../../app/router.mjs';
import { renderMinecraftText } from './minecraft-text.mjs';
import { plainMinecraftText } from '../../app/minecraft-text.mjs';

/**
 * @template {keyof HTMLElementTagNameMap} Tag
 * @param {Tag} tag
 * @param {string} [text]
 * @returns {HTMLElementTagNameMap[Tag]}
 */
function element(tag, text = '') {
    const node = document.createElement(tag);
    node.textContent = text;
    return node;
}

/** Presentation only: CPU requests, cancellation and refresh lifetime belong to the application. */
/**
 * @param {HTMLElement} root
 * @param {Terminal} application
 */
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
    const actions = element('div');
    const resources = element('a');
    view.append(title, status, list, heading, output, timing, empty, tableScroll, actions, resources);
    root.append(view);
    /** @typedef {{li: HTMLLIElement, link: HTMLAnchorElement, summary: HTMLParagraphElement, output: HTMLParagraphElement, actions: HTMLDivElement, notice: HTMLParagraphElement}} CpuRow */
    /** @type {Map<string, CpuRow>} */
    let rows = new Map();
    /** @type {TerminalState['cpus']['detail'] | undefined} */
    let lastDetail;
    /** @type {Locale | undefined} */
    let lastLocale;
    /** @param {import('../../app/cpus.mjs').CpuOutcome | undefined} outcome @param {Locale} locale */
    const outcomeText = (outcome, locale) =>
        outcome?.uncertain
            ? locale.common('cpuMutationUncertain')
            : outcome?.mutation
              ? locale.common(outcome.mutation === 'cancel' ? 'cpuCancelling' : 'cpuUpdating')
              : outcome?.notice
                ? locale.common(outcome.notice)
                : '';
    /** @param {HTMLDivElement} root @param {string} key @param {TerminalState['cpus']['detail'] | TerminalState['cpus']['cpus'][number] | null} cpu @param {TerminalState['cpus']} state @param {Locale} locale @param {string} [label] */
    function renderActions(root, key, cpu, state, locale, label = '') {
        root.className = 'cpu-actions';
        if (!root.firstChild) {
            const pause = element('button');
            pause.type = 'button';
            const cancel = element('button');
            cancel.type = 'button';
            root.append(pause, cancel);
        }
        const [pause, cancel] = /** @type {HTMLButtonElement[]} */ ([...root.children]);
        const outcome = state.outcomes[key];
        const disabled = !!outcome?.mutation || !!outcome?.uncertain || state.status !== 'ready';
        pause.hidden = !cpu?.isBusy || !cpu.supportsPause;
        pause.textContent = locale.common(cpu?.isPaused ? 'resumeCpuWork' : 'pauseCpuWork');
        pause.disabled = disabled;
        pause.onclick = () => application.cpus.pause(key, !cpu?.isPaused);
        cancel.hidden = !cpu?.isBusy;
        cancel.textContent = locale.common('cancelCpuWork');
        cancel.disabled = disabled;
        cancel.onclick = () => {
            if (window.confirm(locale.common('confirmCancelCpu', { cpu: label || key }))) application.cpus.cancel(key);
        };
        for (const button of [pause, cancel]) {
            if (label) button.setAttribute('aria-label', `${button.textContent} · ${label}`);
            else button.removeAttribute('aria-label');
        }
    }
    return {
        /**
         * @param {TerminalState['route']} route
         * @param {TerminalState['cpus']} state
         * @param {Locale} locale
         */
        render(route, state, locale) {
            view.hidden = route.view !== 'cpus';
            if (route.view !== 'cpus') return;
            const { common: t, number } = locale;
            title.textContent = t('cpus');
            resources.textContent = t('backResources');
            resources.href = `#/grids/${encodeURIComponent(route.gridKey)}/items`;
            const detail = state.detail;
            const selectedOutcome = route.cpuKey ? state.outcomes[route.cpuKey] : undefined;
            status.textContent =
                selectedOutcome?.uncertain || selectedOutcome?.mutation
                    ? outcomeText(selectedOutcome, locale)
                    : state.error
                      ? t(state.error)
                      : state.status === 'loading'
                        ? t('loading')
                        : detail
                          ? t(detail.isBusy ? (detail.isPaused ? 'cpuPaused' : 'cpuWorking') : 'cpuIdleMessage')
                          : state.cpus.length
                            ? t('selectCpuWork')
                            : t('noCpus');
            if (selectedOutcome?.notice && selectedOutcome.notice !== state.error)
                status.textContent += ` ${t(selectedOutcome.notice)}`;
            if (selectedOutcome?.uncertain && state.error) status.textContent += ` ${t(state.error)}`;
            /** @type {Map<string, CpuRow>} */
            const current = new Map();
            for (const cpu of state.cpus) {
                const entry = rows.get(cpu.key) || {
                    li: element('li'),
                    link: element('a'),
                    summary: element('p'),
                    output: element('p'),
                    actions: element('div'),
                    notice: element('p')
                };
                if (!entry.link.parentNode) {
                    entry.li.append(entry.link, entry.summary, entry.output, entry.actions, entry.notice);
                    entry.notice.role = 'status';
                }
                entry.link.href = cpuHref(route.gridKey, cpu.key);
                entry.link.replaceChildren(renderMinecraftText(cpu.name), ` · ${cpu.key}`);
                if (route.cpuKey === cpu.key) entry.link.setAttribute('aria-current', 'page');
                else entry.link.removeAttribute('aria-current');
                entry.summary.textContent = `${t(cpu.isBusy ? (cpu.isPaused ? 'cpuPaused' : 'cpuBusy') : 'cpuIdle')} · ${t('cpuCapacity', { count: cpu.availableStorage })} · ${t('coprocessors', { count: cpu.coProcessors })} · ${cpu.usedStorage >= 0 ? t('cpuUsedStorage', { count: cpu.usedStorage }) : t('cpuStorageUnknown')}`;
                renderActions(
                    entry.actions,
                    cpu.key,
                    cpu,
                    state,
                    locale,
                    `${plainMinecraftText(cpu.name)} · ${cpu.key}`
                );
                entry.notice.textContent = route.cpuKey === cpu.key ? '' : outcomeText(state.outcomes[cpu.key], locale);
                entry.output.replaceChildren();
                if (cpu.isBusy) {
                    if (cpu.finalOutput)
                        entry.output.append(
                            renderMinecraftText(cpu.finalOutput.itemname),
                            ` × ${number(cpu.finalOutput.quantity)}`
                        );
                    else entry.output.append(t('cpuOutputUnknown'));
                }
                if (!entry.li.parentNode) list.append(entry.li);
                current.set(cpu.key, entry);
            }
            for (const [key, entry] of rows) if (!current.has(key)) entry.li.remove();
            rows = current;
            const selected = state.cpus.find((cpu) => cpu.key === route.cpuKey);
            heading.replaceChildren();
            if (route.cpuKey) heading.append(`${t('cpuWork')} · `, renderMinecraftText(selected?.name || route.cpuKey));
            output.replaceChildren();
            if (detail?.isBusy) {
                if (detail.finalOutput)
                    output.append(
                        `${t('cpuOutput')}: `,
                        renderMinecraftText(detail.finalOutput.itemname),
                        ` × ${number(detail.finalOutput.quantity)}`
                    );
                else output.append(t('cpuOutputUnknown'));
            }
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
                    const name = element('td');
                    name.append(renderMinecraftText(item.itemname), element('code', item.itemid));
                    row.append(name);
                    const values = [number(item.active), number(item.pending), number(item.stored)];
                    if (detail?.hasTrackingInfo)
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
            renderActions(actions, route.cpuKey || '', detail, state, locale);
        }
    };
}
