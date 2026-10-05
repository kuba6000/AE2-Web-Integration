import type { HistoryEntry } from '../../app/api-types.js';
import type { TerminalState, createTerminal } from '../../app/terminal.js';
import type { Translator as Locale } from '../../app/i18n.js';

import { historyHref } from '../../app/router.js';
import { renderHistoryTimeline } from './history-timeline.js';
import { renderMinecraftText } from './minecraft-text.js';
import { plainMinecraftText } from '../../app/minecraft-text.js';
import { createResourceIcon, paintResourceIcon } from './resource-icon.js';

function element<Tag extends keyof HTMLElementTagNameMap>(tag: Tag, text = ''): HTMLElementTagNameMap[Tag] {
    const node = document.createElement(tag);
    node.textContent = text;
    return node;
}

export function createHistoryView(root: HTMLElement, application: ReturnType<typeof createTerminal>) {
    const view = element('section');
    view.hidden = true;
    view.className = 'history-view';
    const title = element('h2');
    const header = element('div');
    header.className = 'terminal-heading';
    header.append(title);
    const body = element('div');
    body.className = 'terminal-body';
    const status = element('p');
    status.className = 'history-status';
    status.role = 'status';
    const scroll = element('div');
    scroll.className = 'terminal-scroll';
    scroll.role = 'region';
    scroll.tabIndex = 0;
    const icons = application.icons.observe(scroll, paintResourceIcon);
    const list = element('ol');
    list.className = 'history-list';
    const detail = element('div');
    detail.className = 'history-detail';
    scroll.append(list, detail);
    body.append(status, scroll);
    view.append(header, body);
    root.append(view);
    const size = new ResizeObserver(() => {
        const bounds = scroll.getBoundingClientRect();
        if (bounds.width === 0) return;
        const content = list.hidden ? detail : list;
        view.style.setProperty(
            '--grid-inset',
            `${content.getBoundingClientRect().left - view.getBoundingClientRect().left}px`
        );
    });
    size.observe(scroll);
    type HistoryRow = ReturnType<typeof createRow>;

    function createRow() {
        const li = element('li');
        const product = element('div');
        product.className = 'history-product';
        const link = element('a');
        const name = element('span');
        name.className = 'history-name';
        const quantity = element('span');
        product.append(createResourceIcon(), name, quantity);
        const outcome = element('span');
        outcome.className = 'history-outcome';
        const timing = element('dl');
        timing.className = 'history-timing';
        const finished = element('div');
        const finishedLabel = element('dt');
        const time = element('time');
        const finishedValue = element('dd');
        finishedValue.append(time);
        finished.append(finishedLabel, finishedValue);
        const elapsed = element('div');
        const elapsedLabel = element('dt');
        const duration = element('dd');
        elapsed.append(elapsedLabel, duration);
        timing.append(finished, elapsed);
        link.append(product, outcome, timing);
        li.append(link);
        return { li, link, name, quantity, outcome, finishedLabel, time, elapsedLabel, duration };
    }

    let entries: Map<HistoryEntry['id'], HistoryRow> = new Map();

    let lastDetail: TerminalState['history']['detail'] | undefined;

    let lastLocale: Locale | undefined;
    let lastRoute = '';
    return {
        render(route: TerminalState['route'], state: TerminalState['history'], locale: Locale) {
            view.hidden = route.view !== 'history';
            if (route.view !== 'history' || route.entryId !== null) icons.update([], null);
            if (route.view !== 'history') {
                lastRoute = '';
                return;
            }
            const currentRoute = historyHref(route.gridKey, route.entryId);
            if (currentRoute !== lastRoute) {
                scroll.scrollTop = 0;
                lastRoute = currentRoute;
            }
            const { common: t, number, dateTime } = locale;
            title.textContent = t('history');
            scroll.setAttribute('aria-label', t('history'));
            status.textContent = state.error
                ? t(state.error)
                : state.status === 'loading'
                  ? t('loading')
                  : state.detail
                    ? t(state.detail.wasCancelled ? 'historyCancelled' : 'historyCompleted')
                    : state.entries.length
                      ? ''
                      : t('historyEmpty');
            list.hidden = route.entryId !== null;
            detail.hidden = route.entryId === null;
            status.hidden = !status.textContent;
            const focused = document.activeElement as HTMLElement | null;

            const current: Map<HistoryEntry['id'], HistoryRow> = new Map();
            state.entries.forEach((entry, index) => {
                const row = entries.get(entry.id) || createRow();
                row.link.href = historyHref(route.gridKey, entry.id);
                row.name.replaceChildren(renderMinecraftText(entry.finalOutput.itemname));
                row.link.setAttribute(
                    'aria-label',
                    `${plainMinecraftText(entry.finalOutput.itemname)} × ${number(entry.finalOutput.quantity)} · #${entry.id}`
                );
                row.link.title = `#${entry.id}`;
                row.quantity.textContent = `× ${number(entry.finalOutput.quantity)}`;
                row.outcome.textContent = t(entry.wasCancelled ? 'historyCancelled' : 'historyCompleted');
                row.outcome.dataset.outcome = entry.wasCancelled ? 'cancelled' : 'completed';
                row.finishedLabel.textContent = t('historyFinishedLabel');
                row.time.textContent = dateTime(entry.timeDone);
                row.time.dateTime = new Date(entry.timeDone).toISOString();
                row.elapsedLabel.textContent = t('historyDurationLabel');
                row.duration.textContent = locale.duration(entry.timeDone - entry.timeStarted);
                row.link.setAttribute(
                    'aria-description',
                    `${row.outcome.textContent}. ${row.finishedLabel.textContent}: ${row.time.textContent}. ${row.elapsedLabel.textContent}: ${row.duration.textContent}`
                );
                if (list.children[index] !== row.li) list.insertBefore(row.li, list.children[index] || null);
                current.set(entry.id, row);
            });
            for (const [id, row] of entries) if (!current.has(id)) row.li.remove();
            entries = current;
            if (route.entryId === null)
                icons.update(
                    state.entries.map((entry) => ({ element: current.get(entry.id)!.link, icon: entry.icon })),
                    state.icons
                );
            if (focused?.isConnected && list.contains(focused) && focused !== document.activeElement)
                focused.focus({ preventScroll: true });
            if (lastDetail === state.detail && lastLocale === locale) return;
            lastDetail = state.detail;
            lastLocale = locale;
            detail.replaceChildren();
            const snapshot = state.detail;
            if (!snapshot) return;
            const heading = element('h3');
            heading.append(
                renderMinecraftText(snapshot.finalOutput.itemname),
                ` × ${number(snapshot.finalOutput.quantity)}`
            );
            detail.append(
                heading,
                element('code', snapshot.finalOutput.itemid),
                element('p', t('cpuStarted', { time: dateTime(snapshot.timeStarted) })),
                element('p', t('historyEnded', { time: dateTime(snapshot.timeDone) })),
                element('p', t('cpuElapsed', { duration: locale.duration(snapshot.timeDone - snapshot.timeStarted) }))
            );
            const tableScroll = element('div');
            tableScroll.className = 'plan-table';
            const table = element('table');
            const header = element('thead');
            const headings = element('tr');
            header.append(headings);
            for (const key of [
                'resource',
                'cpuCraftedTotal',
                'cpuTimeSpent',
                'cpuRate',
                'cpuElapsedShare',
                'cpuProcessingShare'
            ]) {
                const th = element('th', t(key));
                th.scope = 'col';
                headings.append(th);
            }
            const body = element('tbody');
            for (const item of snapshot.items) {
                const row = element('tr');
                const name = element('td');
                name.append(renderMinecraftText(item.itemname), element('code', item.itemid));
                row.append(name);
                for (const value of [
                    number(item.craftedTotal),
                    locale.duration(item.timeSpentOn),
                    `${number(item.craftsPerSec)}/s`,
                    `${number(item.shareInCraftingTimeCombined * 100)}%`,
                    `${number(item.shareInCraftingTime * 100)}%`
                ])
                    row.append(element('td', value));
                body.append(row);
            }
            table.append(header, body);
            tableScroll.append(table);
            detail.append(tableScroll);
            detail.append(renderHistoryTimeline(snapshot, locale));
        },
        dispose() {
            size.disconnect();
            icons.dispose();
        }
    };
}
