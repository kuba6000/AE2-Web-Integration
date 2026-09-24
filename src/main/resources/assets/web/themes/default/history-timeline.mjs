/**
 * @typedef {import('../../app/api-types.mjs').HistoryItem} HistoryItem
 * @typedef {import('../../app/api-types.mjs').ProviderTiming} ProviderTiming
 */

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

/**
 * Detached history intervals; each API row remains separate even when names/registry IDs repeat.
 * @param {import('../../app/api-types.mjs').HistoryDetail} snapshot
 * @param {import('../../app/i18n.mjs').Translator} locale
 */
export function renderHistoryTimeline(snapshot, locale) {
    const { common: t, number, preciseTime } = locale;
    const container = element('div');
    container.className = 'history-timelines';
    const duration = snapshot.timeDone - snapshot.timeStarted;
    if (!snapshot.items.length && !snapshot.interfaceShare.length) return element('p', t('historyNoTimings'));
    /** @type {Array<[string, HistoryItem[], false] | [string, ProviderTiming[], true]>} */
    const groups = [
        ['resourceTimings', snapshot.items, false],
        ['providerTimings', snapshot.interfaceShare, true]
    ];
    for (const [title, rows, providers] of groups) {
        container.append(element('h3', t(title)));
        for (const row of rows) {
            const name = providers
                ? /** @type {ProviderTiming} */ (row).name
                : /** @type {HistoryItem} */ (row).itemname;
            const section = element('section');
            section.setAttribute('aria-label', name);
            section.append(element('h4', name));
            if (!providers) section.append(element('code', /** @type {HistoryItem} */ (row).itemid));
            else {
                const provider = /** @type {ProviderTiming} */ (row);
                section.append(element('p', t('processingTotal', { count: provider.timingsCombined / 1000 })));
                for (const position of provider.location)
                    section.append(element('p', t('position', { dimension: position.dimid, ...position })));
            }
            if (duration > 0 && row.timings.length) {
                const track = element('div');
                track.className = 'history-track';
                track.setAttribute('aria-hidden', 'true');
                for (const interval of row.timings) {
                    const left = Math.max(0, Math.min(1, (interval.started - snapshot.timeStarted) / duration));
                    const right = Math.max(left, Math.min(1, (interval.ended - snapshot.timeStarted) / duration));
                    const bar = element('span');
                    bar.style.left = `${left * 100}%`;
                    bar.style.width = `${(right - left) * 100}%`;
                    track.append(bar);
                }
                section.append(track);
            }
            const details = element('details');
            details.append(element('summary', t('timingIntervals', { count: row.timings.length })));
            const list = element('ol');
            for (const interval of row.timings) {
                const item = element('li');
                const start = element('time', preciseTime(interval.started));
                start.dateTime = new Date(interval.started).toISOString();
                const end = element('time', preciseTime(interval.ended));
                end.dateTime = new Date(interval.ended).toISOString();
                item.append(
                    start,
                    document.createTextNode(' — '),
                    end,
                    document.createTextNode(` · ${number(Math.max(0, interval.ended - interval.started) / 1000)} s`)
                );
                list.append(item);
            }
            if (row.timings.length) details.append(list);
            else details.append(element('p', t('noIntervals')));
            section.append(details);
            container.append(section);
        }
    }
    return container;
}
