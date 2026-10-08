import { registryId } from './resource-metadata.js';
import type { Translator } from '../../app/i18n.js';
import type { CraftingHistory, ResourceTiming, ProviderTiming, Timing } from '../../app/api-types.js';
import { plainMinecraftText } from '../../app/minecraft-text.js';
import { renderMinecraftText } from './minecraft-text.js';
import { createResourceIcon } from './resource-icon.js';
import type { IconTarget } from '../../app/icons.js';

export type HistoryTooltipBinder = (target: HTMLElement, content: () => Node[], focusTarget?: HTMLElement) => void;

function element<Tag extends keyof HTMLElementTagNameMap>(tag: Tag, text = ''): HTMLElementTagNameMap[Tag] {
    const node = document.createElement(tag);
    node.textContent = text;
    return node;
}

// Resolution bounds chart geometry independently of the number of recorded intervals.
function activityTrack(timings: Timing[], started: number, duration: number, label: string) {
    const track = element('div');
    track.className = 'history-track';
    track.role = 'img';
    track.setAttribute('aria-label', label);
    track.setAttribute('aria-busy', 'false');
    if (duration <= 0 || !timings.length) return track;
    track.setAttribute('aria-busy', 'true');
    const bins = 256;
    const changes = new Float64Array(bins + 1);
    let index = 0;
    const process = () => {
        if (!track.isConnected) return;
        const end = Math.min(index + 4096, timings.length);
        for (; index < end; index++) {
            const interval = timings[index];
            if (interval.ended <= interval.started) continue;
            const left = Math.max(0, Math.min(bins - 1, Math.floor(((interval.started - started) / duration) * bins)));
            const right = Math.max(left + 1, Math.min(bins, Math.ceil(((interval.ended - started) / duration) * bins)));
            if (interval.ended < started || interval.started > started + duration) continue;
            changes[left]++;
            changes[right]--;
        }
        if (index < timings.length) {
            setTimeout(process, 0);
            return;
        }
        let active = 0;
        let from = -1;
        let path = '';
        for (let bin = 0; bin <= bins; bin++) {
            active += changes[bin];
            if (active > 0 && from < 0) from = bin;
            if (active === 0 && from >= 0) {
                path += `M${from} 0h${bin - from}v1H${from}Z`;
                from = -1;
            }
        }
        const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        svg.setAttribute('viewBox', `0 0 ${bins} 1`);
        svg.setAttribute('preserveAspectRatio', 'none');
        const shape = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        shape.setAttribute('d', path);
        svg.append(shape);
        track.append(svg);
        track.setAttribute('aria-busy', 'false');
    };
    setTimeout(process, 0);
    return track;
}

function pager(
    total: number,
    pageSize: number,
    label: string,
    locale: Translator,
    render: (page: number) => void,
    initialPage = 0
) {
    let page = 0;
    const last = Math.max(0, Math.ceil(total / pageSize) - 1);
    const nav = element('nav');
    nav.className = 'history-pagination';
    nav.setAttribute('aria-label', label);
    const previous = element('button', locale.common('previousPage'));
    const next = element('button', locale.common('nextPage'));
    const final = element('button', locale.common('lastPage'));
    for (const button of [previous, next, final]) button.type = 'button';
    const count = element('span');
    count.role = 'status';
    const update = (value: number) => {
        page = value;
        count.textContent = locale.common('historyPage', { page: page + 1, pages: last + 1, count: total });
        previous.disabled = page === 0;
        next.disabled = final.disabled = page === last;
        render(page);
    };
    previous.onclick = () => update(page - 1);
    next.onclick = () => update(page + 1);
    final.onclick = () => update(last);
    nav.append(previous, count, next, final);
    nav.hidden = total <= pageSize;
    update(initialPage);
    return nav;
}

export function renderHistoryTimeline(
    snapshot: CraftingHistory,
    locale: Translator,
    visibleIcons: (targets: IconTarget[]) => void,
    bindTooltip: HistoryTooltipBinder
) {
    const { common: t, duration: formatDuration, preciseTime, number } = locale;
    const container = element('div');
    container.className = 'history-analysis';
    const elapsed = snapshot.timeDone - snapshot.timeStarted;
    if (!snapshot.items.length && !snapshot.interfaceShare.length) return element('p', t('historyNoTimings'));
    let providers = false;
    const tabs = element('div');
    tabs.className = 'history-analysis-tabs';
    const resourcesButton = element('button', `${t('resources')} (${number(snapshot.items.length)})`);
    resourcesButton.setAttribute('aria-label', t('resources'));
    const providersButton = element('button', `${t('historyProviders')} (${number(snapshot.interfaceShare.length)})`);
    providersButton.setAttribute('aria-label', t('historyProviders'));
    resourcesButton.type = providersButton.type = 'button';
    tabs.append(resourcesButton, providersButton);
    const toolbar = element('div');
    toolbar.className = 'history-analysis-toolbar';
    const searchLabel = element('label', t('historySearch'));
    const search = element('input');
    search.type = 'search';
    searchLabel.append(search);
    const sortLabel = element('label', t('sort'));
    const sort = element('select');
    for (const [value, key] of [
        ['time', 'cpuTimeSpent'],
        ['quantity', 'quantity'],
        ['name', 'name']
    ]) {
        const option = element('option', t(key));
        option.value = value;
        sort.append(option);
    }
    sortLabel.append(sort);
    toolbar.append(searchLabel, sortLabel);
    const legend = element('details');
    legend.className = 'history-analysis-help';
    legend.append(
        element('summary', t('historyReadingTimeline')),
        element('p', t('historyActivityHelp')),
        element('p', t('historyProviderTimeHelp'))
    );
    const columns = element('div');
    columns.className = 'history-analysis-columns';
    const nameColumn = element('span');
    const quantityColumn = element('span');
    columns.append(nameColumn, quantityColumn, element('span', t('cpuTimeSpent')));
    const axis = element('div');
    axis.className = 'history-timeline-axis';
    axis.setAttribute('aria-label', t('historyElapsedAxis'));
    for (const fraction of [0, 0.5, 1]) axis.append(element('span', formatDuration(Math.max(0, elapsed) * fraction)));
    const list = element('ol');
    list.className = 'history-analysis-list';
    const empty = element('p', t('historyNoMatches'));
    const pagination = element('div');
    let iconTargets: IconTarget[] = [];
    const providerGroups = new Map(snapshot.interfaceShare.map((provider) => [provider.name, provider]));
    container.append(tabs, toolbar, legend, columns, axis, list, empty, pagination);

    const isFinalProduct = (row: ResourceTiming | ProviderTiming) =>
        'displayName' in row && !!row.itemKey && row.itemKey === snapshot.finalOutput.itemKey;

    function createRow(row: ResourceTiming | ProviderTiming) {
        const resource = 'displayName' in row ? row : null;
        const provider = 'name' in row ? row : null;
        const name = resource ? resource.displayName : provider!.name;
        const section = element('section');
        section.className = 'history-analysis-row';
        section.setAttribute('aria-label', plainMinecraftText(name));
        const details = element('details');
        const summary = element('summary');
        summary.className = 'history-analysis-summary';
        const heading = element('h4');
        heading.append(renderMinecraftText(name));
        if (isFinalProduct(row)) {
            section.classList.add('history-final-product');
            const marker = element('span', '★');
            marker.className = 'history-final-marker';
            marker.role = 'img';
            marker.setAttribute('aria-label', t('historyFinalProduct'));
            heading.prepend(marker);
            bindTooltip(marker, () => [element('span', t('historyFinalProduct'))], summary);
        }
        const identity = element('div');
        identity.className = 'history-analysis-name';
        if (resource) {
            identity.append(createResourceIcon());
            iconTargets.push({ element: identity, icon: resource.icon });
        }
        identity.append(heading);
        const quantity = element('span', number(resource ? resource.craftedTotal : row.timings.length));
        quantity.className = 'history-analysis-quantity';
        const processing = element('span', formatDuration(resource ? resource.timeSpentOn : provider!.timingsCombined));
        processing.className = 'history-analysis-processing';
        summary.append(
            identity,
            quantity,
            processing,
            activityTrack(row.timings, snapshot.timeStarted, elapsed, t('historyActivityOverview'))
        );
        const content = element('div');
        content.className = 'history-analysis-details';
        details.append(summary, content);
        details.ontoggle = () => {
            content.replaceChildren();
            if (!details.open) return;
            const metrics = element('dl');
            metrics.className = 'history-analysis-metrics';
            const metric = (label: string, value: string) => {
                const pair = element('div');
                pair.append(element('dt', label), element('dd', value));
                metrics.append(pair);
            };
            if (resource) {
                const id = registryId(resource);
                if (id) content.append(element('code', id));
                const rate =
                    resource.craftsPerSec > 0 && resource.craftsPerSec < 0.001
                        ? `<${number(0.001)}`
                        : number(resource.craftsPerSec);
                metric(t('cpuRate'), `${rate}/s`);
                metric(t('cpuElapsedShare'), `${number(resource.shareInCraftingTimeCombined * 100)}%`);
                metric(t('cpuProcessingShare'), `${number(resource.shareInCraftingTime * 100)}%`);
                if (resource.providers?.length) {
                    const names = element('ul');
                    names.className = 'history-resource-providers';
                    names.setAttribute('aria-label', t('historyCraftedBy'));
                    const namesPager = pager(
                        resource.providers.length,
                        25,
                        t('historyProviderPages'),
                        locale,
                        (page) => {
                            names.replaceChildren();
                            for (const name of resource.providers.slice(page * 25, (page + 1) * 25)) {
                                const item = element('li');
                                const group = providerGroups.get(name);
                                if (group) {
                                    const link = element('button');
                                    link.type = 'button';
                                    link.className = 'history-provider-link';
                                    link.append(renderMinecraftText(name));
                                    link.onclick = () => {
                                        providers = true;
                                        search.value = '';
                                        render(group);
                                    };
                                    item.append(link);
                                } else item.append(renderMinecraftText(name));
                                names.append(item);
                            }
                        }
                    );
                    content.append(element('h5', t('historyCraftedBy')), names, namesPager);
                }
            }
            metric(t('historyIntervalCount'), number(row.timings.length));
            content.append(metrics);
            const intervals = element('ol');
            intervals.className = 'history-exact-intervals';
            intervals.setAttribute('aria-label', t('historyExactIntervals'));
            const intervalsPager = pager(row.timings.length, 50, t('historyIntervalPages'), locale, (page) => {
                intervals.replaceChildren();
                for (let i = page * 50; i < Math.min((page + 1) * 50, row.timings.length); i++) {
                    const interval = row.timings[i];
                    const item = element('li');
                    item.value = i + 1;
                    const start = element('time', preciseTime(interval.started));
                    start.dateTime = new Date(interval.started).toISOString();
                    const end = element('time', preciseTime(interval.ended));
                    end.dateTime = new Date(interval.ended).toISOString();
                    item.append(start, ' — ', end, ` · ${formatDuration(interval.ended - interval.started)}`);
                    intervals.append(item);
                }
            });
            content.append(element('h5', t('historyExactIntervals')), intervals, intervalsPager);
            if (!row.timings.length) content.append(element('p', t('noIntervals')));
            if (provider) {
                const locations = element('ul');
                locations.className = 'history-provider-locations';
                const locationPager = pager(provider.location.length, 25, t('historyLocationPages'), locale, (page) => {
                    locations.replaceChildren();
                    for (let i = page * 25; i < Math.min((page + 1) * 25, provider.location.length); i++) {
                        const position = provider.location[i];
                        locations.append(
                            element('li', t('position', { dimension: position.dimensionId, ...position }))
                        );
                    }
                });
                content.append(element('h5', t('historyLocations')), locations, locationPager);
            }
        };
        section.append(details);
        const item = element('li');
        item.append(section);
        return { item, section, details, summary };
    }

    function render(targetProvider?: ProviderTiming) {
        resourcesButton.setAttribute('aria-pressed', String(!providers));
        providersButton.setAttribute('aria-pressed', String(providers));
        const modeName = t(providers ? 'historyProviders' : 'resources');
        list.setAttribute('aria-label', modeName);
        nameColumn.textContent = modeName;
        quantityColumn.textContent = t(providers ? 'historyIntervalCount' : 'cpuCraftedTotal');
        sort.options[1].textContent = t(providers ? 'historyIntervalCount' : 'quantity');
        const query = search.value.trim().toLocaleLowerCase();
        const rows: Array<ResourceTiming | ProviderTiming> = providers ? snapshot.interfaceShare : snapshot.items;
        const displayName = (row: ResourceTiming | ProviderTiming) =>
            plainMinecraftText('displayName' in row ? row.displayName : row.name);
        const matching = rows.filter(
            (row) =>
                !query ||
                `${displayName(row)} ${'displayName' in row ? registryId(row) || '' : ''}`
                    .toLocaleLowerCase()
                    .includes(query)
        );
        matching.sort((a, b) => {
            const priority = Number(isFinalProduct(b)) - Number(isFinalProduct(a));
            if (priority) return priority;
            if (sort.value === 'name') return displayName(a).localeCompare(displayName(b));
            const amount = (row: ResourceTiming | ProviderTiming) =>
                sort.value === 'quantity'
                    ? 'craftedTotal' in row
                        ? row.craftedTotal
                        : row.timings.length
                    : 'timeSpentOn' in row
                      ? row.timeSpentOn
                      : row.timingsCombined;
            return amount(b) - amount(a);
        });
        empty.hidden = matching.length > 0;
        const targetIndex = targetProvider ? matching.indexOf(targetProvider) : -1;
        let currentPage = Math.max(0, Math.floor(targetIndex / 25));
        pagination.replaceChildren(
            pager(
                matching.length,
                25,
                t('historyPages'),
                locale,
                (page) => {
                    iconTargets = [];
                    list.replaceChildren();
                    let target: ReturnType<typeof createRow> | null = null;
                    for (let i = page * 25; i < Math.min((page + 1) * 25, matching.length); i++) {
                        const row = createRow(matching[i]);
                        list.append(row.item);
                        if (matching[i] === targetProvider) target = row;
                    }
                    visibleIcons(iconTargets);
                    if (target) {
                        const heading = target.summary;
                        target.details.addEventListener(
                            'toggle',
                            () => {
                                if (heading.isConnected) heading.scrollIntoView({ block: 'start' });
                            },
                            { once: true }
                        );
                        target.details.open = true;
                        target.section.classList.add('history-provider-target');
                        target.summary.focus({ preventScroll: true });
                        targetProvider = undefined;
                    } else if (page !== currentPage) columns.scrollIntoView({ block: 'start' });
                    currentPage = page;
                },
                currentPage
            )
        );
    }
    resourcesButton.onclick = () => {
        providers = false;
        search.value = '';
        render();
    };
    providersButton.onclick = () => {
        providers = true;
        search.value = '';
        render();
    };
    search.oninput = () => render();
    sort.onchange = () => render();
    render();
    return container;
}
