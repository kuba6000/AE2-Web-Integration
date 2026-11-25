import type { ThemeApplication, ThemeState, Translator } from '../../app/theme-api.js';
import type { HistoricalResource } from '../../app/api-types.js';
import type { IconTarget } from '../../app/icons.js';
import { registryId } from '../../app/resource-helpers.js';
import {
    catalogSortRequiresType,
    historyHref,
    trackingCatalogHref,
    trackingHref,
    type TrackingCatalogLocation
} from '../../app/router.js';
import { element } from './dom.js';
import { renderMinecraftText } from './minecraft-text.js';
import { createResourceIcon, paintResourceIcon } from './resource-icon.js';

type Catalog = ThemeState['trackingCatalog'];
type CatalogRoute = Extract<ThemeState['route'], { itemKey: null }>;
const sorts = ['INPUT', 'OUTPUT', 'INSERTS', 'EXTRACTS', 'STOCK', 'RECENT', 'NAME', 'NAME_DESC'] as const;
const types = ['', 'ITEM', 'FLUID', 'OTHER'] as const;
const periods = ['15', '60', '1440'] as const;

function selectField<T extends string>(values: readonly T[]) {
    const label = element('label');
    const caption = element('span');
    const select = element('select');
    const options = values.map((value) => {
        const option = element('option');
        option.value = value;
        select.append(option);
        return option;
    });
    label.append(caption, select);
    return {
        label,
        select,
        render(name: string, translate: (value: T) => string) {
            caption.textContent = name;
            options.forEach((option, i) => (option.textContent = translate(values[i])));
        }
    };
}

/** Only the fractional part is converted to Number; a large whole-unit rate stays exact. */
function perMinute(count: bigint, minutes: number, numbers: Intl.NumberFormat): string {
    const divisor = BigInt(minutes);
    const thousandths = (count * 1000n + divisor / 2n) / divisor;
    const fraction = Number(thousandths % 1000n) / 1000;
    const suffix = fraction
        ? numbers
              .formatToParts(fraction)
              .filter((part) => part.type === 'decimal' || part.type === 'fraction')
              .map((part) => part.value)
              .join('')
        : '';
    return numbers.format(thousandths / 1000n) + suffix;
}

function resourceRow(
    resource: HistoricalResource,
    catalog: Catalog,
    route: CatalogRoute,
    locale: Translator,
    numbers: Intl.NumberFormat,
    showIcons: boolean
) {
    const key = resource.itemKey;
    const row = element('li');
    const link = element('a');
    link.href = trackingHref(route.gridKey, key, route);
    const product = element('div', '', 'tracking-resource');
    if (showIcons && resource.icon) product.append(createResourceIcon());
    const identity = element('div', '', 'catalog-identity');
    const name = element('span', '', 'history-name');
    name.append(renderMinecraftText(resource.displayName));
    const id = registryId(resource);
    identity.append(name);
    if (id) identity.append(element('small', id));
    product.append(identity);
    const stats = element('dl', '', 'catalog-stats');
    const add = (label: string, value: string, detail?: string, active = false) => {
        const group = element('div');
        if (active) group.className = 'catalog-ranked';
        const metric = element('dd', value);
        if (detail) metric.title = detail;
        group.append(element('dt', label), metric);
        stats.append(group);
        return group;
    };
    const stock = catalog.stock[key];
    const quantity = stock ? numbers.format(BigInt(stock.quantity)) : '—';
    const sampled = stock ? locale.dateTime(stock.observedAt) : locale.t('trackingNoStockSample');
    const stockMetric = add(locale.t('trackingStock'), quantity, sampled, route.sort === 'STOCK');
    if (stock) {
        const time = element('time', locale.dateTime(stock.observedAt, Date.now()));
        time.dateTime = new Date(stock.observedAt).toISOString();
        time.title = sampled;
        const when = element('dd');
        when.append(time);
        stockMetric.append(when);
    }
    const totals = catalog.activity[key];
    for (const [field, sort] of [
        ['input', 'INPUT'],
        ['output', 'OUTPUT'],
        ['inputOperations', 'INSERTS'],
        ['outputOperations', 'EXTRACTS']
    ] as const) {
        const count = totals?.[field];
        // Rates are rounded for display; the API sorts exact totals and the tooltip retains them.
        const rate = count == null ? '—' : perMinute(BigInt(count), route.minutes, numbers);
        const detail =
            count == null
                ? locale.t('trackingNoFlowSample')
                : locale.t('trackingWindowTotal', { total: numbers.format(BigInt(count)) });
        add(locale.t('trackingMetric' + sort), rate, detail, sort === route.sort);
    }
    link.append(product, stats);
    if (route.sort === 'RECENT' && catalog.lastObservedAt[key] != null) {
        link.append(
            element(
                'small',
                locale.t('trackingLastSeen', { time: locale.dateTime(catalog.lastObservedAt[key]) }),
                'catalog-last-seen'
            )
        );
    }
    row.append(link);
    return { row, link };
}

export function createTrackingCatalogView(host: HTMLElement, application: ThemeApplication) {
    const panel = element('section', '', 'page-view tracking-catalog');
    const heading = element('div', '', 'terminal-heading');
    const title = element('h2');
    const history = element('a', '', 'tracking-back');
    heading.append(title, history);
    const body = element('div', '', 'terminal-body');
    const form = element('form', '', 'catalog-filters');
    const search = element('input');
    search.type = 'search';
    search.maxLength = 256;
    const submit = element('button');
    submit.type = 'submit';
    const searchGroup = element('div', '', 'catalog-search');
    searchGroup.append(search, submit);
    const type = selectField(types),
        period = selectField(periods),
        sort = selectField(sorts);
    form.append(searchGroup, type.label, period.label, sort.label);
    const status = element('p', '', 'history-status');
    status.role = 'status';
    const scroll = element('div', '', 'terminal-scroll');
    scroll.role = 'region';
    scroll.tabIndex = 0;
    const icons = application.icons.observe(scroll, paintResourceIcon);
    const list = element('ul', '', 'history-list catalog-list');
    const help = element('p', '', 'catalog-help');
    const pages = element('nav', '', 'settings-actions');
    const next = element('a'),
        first = element('a');
    pages.append(first, next);
    scroll.append(help, list, pages);
    body.append(form, status, scroll);
    panel.append(heading, body);
    host.append(panel);
    let route: CatalogRoute | undefined;
    let rows: Catalog['resources'] | undefined;
    let previousLocale: Translator | undefined;
    let withIcons = false;
    let targets: IconTarget[] = [];
    function navigate(changes: Partial<TrackingCatalogLocation>) {
        if (route)
            location.hash = trackingCatalogHref(route.gridKey, {
                ...route,
                search: search.value.trim(),
                after: null,
                until: null,
                ...changes
            });
    }
    form.onsubmit = (event) => {
        event.preventDefault();
        navigate({});
    };
    type.select.onchange = () => {
        const value = type.select.value as TrackingCatalogLocation['type'] | '';
        // Counts remain comparable when switching from one resource type to all types.
        navigate({
            type: value || null,
            ...(!value && route && catalogSortRequiresType(route.sort) ? { sort: 'INSERTS' } : {})
        });
    };
    sort.select.onchange = () => {
        const value = sort.select.value as TrackingCatalogLocation['sort'];
        navigate({ sort: value, ...(catalogSortRequiresType(value) && !route?.type ? { type: 'ITEM' } : {}) });
    };
    period.select.onchange = () =>
        navigate({ minutes: Number(period.select.value) as TrackingCatalogLocation['minutes'] });
    return {
        render(state: ThemeState, locale: Translator, showIcons: boolean) {
            const current = state.route;
            panel.hidden = current.view !== 'tracking' || current.itemKey !== null;
            if (current.view !== 'tracking' || current.itemKey !== null) {
                if (route) {
                    icons.update([], null);
                    targets = [];
                    list.replaceChildren();
                    rows = undefined;
                    route = undefined;
                }
                return;
            }
            const changed = route !== current;
            if (changed) {
                search.value = current.search;
                scroll.scrollTop = 0;
            }
            route = current;
            title.textContent = locale.t('trackingCatalogTitle');
            history.textContent = locale.common('history');
            history.href = historyHref(current.gridKey);
            search.setAttribute('aria-label', locale.common('search'));
            search.placeholder = locale.common('search');
            submit.textContent = locale.t('trackingSearchAction');
            type.render(locale.t('trackingType'), (value) =>
                value === ''
                    ? locale.common('all')
                    : locale.t({ ITEM: 'showItems', FLUID: 'showFluids', OTHER: 'trackingOther' }[value])
            );
            period.render(locale.t('trackingRange'), (value) => locale.duration(Number(value) * 60000));
            sort.render(locale.common('sort'), (value) => locale.t('trackingSort' + value));
            type.select.value = current.type || '';
            period.select.value = String(current.minutes);
            sort.select.value = current.sort;
            help.textContent = locale.t('trackingCatalogIntro');
            scroll.setAttribute('aria-label', title.textContent);
            const catalog = state.trackingCatalog;
            status.textContent = catalog.error
                ? locale.common(catalog.error)
                : catalog.status !== 'ready'
                  ? locale.common('loading')
                  : !catalog.resources.length
                    ? locale.t('trackingCatalogEmpty')
                    : '';
            status.hidden = !status.textContent;
            if (changed || rows !== catalog.resources || previousLocale !== locale || withIcons !== showIcons) {
                const numbers = new Intl.NumberFormat(state.preferences.language, { maximumFractionDigits: 3 });
                rows = catalog.resources;
                targets = [];
                list.replaceChildren(
                    ...rows.map((resource) => {
                        const { row, link } = resourceRow(resource, catalog, current, locale, numbers, showIcons);
                        if (showIcons && resource.icon) targets.push({ element: link, icon: resource.icon });
                        return row;
                    })
                );
                previousLocale = locale;
                withIcons = showIcons;
            }
            icons.update(targets, catalog.icons);
            next.hidden = !catalog.nextAfter;
            next.textContent = locale.common('nextPage');
            next.href = trackingCatalogHref(current.gridKey, {
                ...current,
                after: catalog.nextAfter,
                until: catalog.windowEnd
            });
            first.hidden = !current.after;
            first.textContent = locale.t('trackingCatalogFirst');
            first.href = trackingCatalogHref(current.gridKey, { ...current, after: null, until: null });
            pages.hidden = next.hidden && first.hidden;
        },
        dispose: () => icons.dispose()
    };
}
