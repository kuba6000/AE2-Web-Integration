import { registryId } from './resource-metadata.js';
import type { Translator } from '../../app/i18n.js';
import type { CraftingHistory, ResourceTiming, ProviderTiming } from '../../app/api-types.js';

import { plainMinecraftText } from '../../app/minecraft-text.js';
import { renderMinecraftText } from './minecraft-text.js';

function element<Tag extends keyof HTMLElementTagNameMap>(tag: Tag, text = ''): HTMLElementTagNameMap[Tag] {
    const node = document.createElement(tag);
    node.textContent = text;
    return node;
}

/* Detached history intervals; each API row remains separate even when names/registry IDs repeat. */

export function renderHistoryTimeline(snapshot: CraftingHistory, locale: Translator) {
    const { common: t, duration: formatDuration, preciseTime } = locale;
    const container = element('div');
    container.className = 'history-timelines';
    const duration = snapshot.timeDone - snapshot.timeStarted;
    if (!snapshot.items.length && !snapshot.interfaceShare.length) return element('p', t('historyNoTimings'));

    const groups: Array<[string, ResourceTiming[], false] | [string, ProviderTiming[], true]> = [
        ['resourceTimings', snapshot.items, false],
        ['providerTimings', snapshot.interfaceShare, true]
    ];
    for (const [title, rows, providers] of groups) {
        container.append(element('h3', t(title)));
        for (const row of rows) {
            const name = providers ? (row as ProviderTiming).name : (row as ResourceTiming).displayName;
            const section = element('section');
            section.setAttribute('aria-label', plainMinecraftText(name));
            const heading = element('h4');
            heading.append(renderMinecraftText(name));
            section.append(heading);
            if (!providers) {
                const id = registryId(row as ResourceTiming);
                if (id) section.append(element('code', id));
            } else {
                const provider = row as ProviderTiming;
                section.append(
                    element('p', t('processingTotal', { duration: formatDuration(provider.timingsCombined) }))
                );
                for (const position of provider.location)
                    section.append(element('p', t('position', { dimension: position.dimensionId, ...position })));
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
                    document.createTextNode(` · ${formatDuration(interval.ended - interval.started)}`)
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
