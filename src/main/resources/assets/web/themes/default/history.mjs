import { historyHref } from '../../app/router.mjs';
import { renderHistoryTimeline } from './history-timeline.mjs';

function element(tag, text = '') {
    const node = document.createElement(tag);
    node.textContent = text;
    return node;
}

export function createHistoryView(root) {
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
    let entries = new Map();
    let lastDetail;
    let lastLocale;
    return {
        render(route, state, locale) {
            view.hidden = route.view !== 'history';
            if (view.hidden) return;
            const { t, number, dateTime } = locale;
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
            const focused = document.activeElement;
            const current = new Map();
            state.entries.forEach((entry, index) => {
                const row = entries.get(entry.id) || { li: element('li'), link: element('a'), time: element('p') };
                if (!row.link.parentNode) row.li.append(row.link, row.time);
                row.link.href = historyHref(route.gridKey, entry.id);
                row.link.textContent = `${entry.finalOutput.itemname} × ${number(entry.finalOutput.quantity)} · #${entry.id}`;
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
            detail.append(
                element('h3', `${snapshot.finalOutput.itemname} × ${number(snapshot.finalOutput.quantity)}`),
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
                const name = element('td', item.itemname);
                name.append(element('code', item.itemid));
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
