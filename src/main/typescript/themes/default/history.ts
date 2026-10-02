import type { HistoryEntry } from '../../app/api-types.js';
import type { TerminalState } from '../../app/terminal.js';
import type { Translator as Locale } from '../../app/i18n.js';

import { historyHref } from '../../app/router.js';
import { renderHistoryTimeline } from './history-timeline.js';
import { renderMinecraftText } from './minecraft-text.js';

function element<Tag extends keyof HTMLElementTagNameMap>(tag: Tag, text = ''): HTMLElementTagNameMap[Tag] {
    const node = document.createElement(tag);
    node.textContent = text;
    return node;
}

export function createHistoryView(root: HTMLElement) {
    const view = element('section');
    view.hidden = true;
    view.className = 'history-view';
    const title = element('h2');
    const status = element('p');
    status.role = 'status';
    const list = element('ol');
    list.className = 'history-list';
    const detail = element('div');
    const back = element('a');
    view.append(title, status, list, detail, back);
    root.append(view);
    type HistoryRow = { li: HTMLLIElement; link: HTMLAnchorElement; time: HTMLParagraphElement };

    let entries: Map<HistoryEntry['id'], HistoryRow> = new Map();

    let lastDetail: TerminalState['history']['detail'] | undefined;

    let lastLocale: Locale | undefined;
    return {
        render(route: TerminalState['route'], state: TerminalState['history'], locale: Locale) {
            view.hidden = route.view !== 'history';
            if (route.view !== 'history') return;
            const { common: t, number, dateTime } = locale;
            title.textContent = t('history');
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
            back.textContent = t(route.entryId !== null ? 'historyBack' : 'backResources');
            back.href =
                route.entryId !== null
                    ? historyHref(route.gridKey)
                    : `#/grids/${encodeURIComponent(route.gridKey)}/items`;
            const focused = document.activeElement as HTMLElement | null;

            const current: Map<HistoryEntry['id'], HistoryRow> = new Map();
            state.entries.forEach((entry, index) => {
                const row = entries.get(entry.id) || { li: element('li'), link: element('a'), time: element('p') };
                if (!row.link.parentNode) row.li.append(row.link, row.time);
                row.link.href = historyHref(route.gridKey, entry.id);
                row.link.replaceChildren(
                    renderMinecraftText(entry.finalOutput.itemname),
                    ` × ${number(entry.finalOutput.quantity)} · #${entry.id}`
                );
                row.time.textContent = `${t(entry.wasCancelled ? 'historyCancelled' : 'historyCompleted')} · ${dateTime(entry.timeDone)}`;
                if (list.children[index] !== row.li) list.insertBefore(row.li, list.children[index] || null);
                current.set(entry.id, row);
            });
            for (const [id, row] of entries) if (!current.has(id)) row.li.remove();
            entries = current;
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
                element('p', t('cpuElapsed', { count: Math.max(0, snapshot.timeDone - snapshot.timeStarted) / 1000 }))
            );
            const scroll = element('div');
            scroll.className = 'plan-table';
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
                    t('seconds', { count: item.timeSpentOn / 1000 }),
                    `${number(item.craftsPerSec)}/s`,
                    `${number(item.shareInCraftingTimeCombined * 100)}%`,
                    `${number(item.shareInCraftingTime * 100)}%`
                ])
                    row.append(element('td', value));
                body.append(row);
            }
            table.append(header, body);
            scroll.append(table);
            detail.append(scroll);
            detail.append(renderHistoryTimeline(snapshot, locale));
        }
    };
}
