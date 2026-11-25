import type { ThemeState, Translator } from '../../app/theme-api.js';
import { historyHref, trackingCatalogHref, trackingHref } from '../../app/router.js';
import { element } from './dom.js';
import { renderMinecraftText } from './minecraft-text.js';

export function createTrackingCatalogView(host: HTMLElement) {
    const panel = element('section', '', 'tracking-catalog');
    const title = element('h2');
    const intro = element('p', '', 'hint');
    const form = element('form', '', 'tracking-toolbar');
    const search = element('input');
    search.type = 'search';
    search.maxLength = 256;
    const submit = element('button');
    submit.type = 'submit';
    form.append(search, submit);
    const status = element('p', '', 'hint');
    status.role = 'status';
    const list = element('ul');
    const pages = element('div', '', 'tracking-toolbar');
    const next = element('a'),
        first = element('a'),
        history = element('a');
    pages.append(first, next);
    panel.append(title, intro, form, status, list, pages, history);
    host.append(panel);
    let route: ThemeState['route'] | undefined;
    let rows: ThemeState['trackingCatalog']['resources'] | undefined;
    form.onsubmit = (event) => {
        event.preventDefault();
        if (route?.gridKey) location.hash = trackingCatalogHref(route.gridKey, search.value.trim());
    };
    return (state: ThemeState, locale: Translator) => {
        const current = state.route;
        panel.hidden = current.view !== 'tracking' || current.itemKey !== null;
        if (current.view !== 'tracking' || current.itemKey !== null) {
            route = undefined;
            if (rows) list.replaceChildren();
            rows = undefined;
            return;
        }
        if (route !== current) search.value = current.search;
        route = current;
        title.textContent = locale.t('trackingCatalogTitle');
        intro.textContent = locale.t('trackingCatalogIntro');
        search.setAttribute('aria-label', locale.common('search'));
        submit.textContent = locale.common('search');
        const catalog = state.trackingCatalog;
        status.textContent = catalog.error
            ? locale.common(catalog.error)
            : catalog.status !== 'ready'
              ? locale.common('loading')
              : catalog.resources.length
                ? ''
                : locale.t('trackingCatalogEmpty');
        status.hidden = !status.textContent;
        if (rows !== catalog.resources) {
            rows = catalog.resources;
            list.replaceChildren(
                ...rows.map((resource) => {
                    const row = element('li');
                    const link = element('a');
                    link.href = trackingHref(current.gridKey, resource.itemKey, current);
                    link.append(renderMinecraftText(resource.displayName));
                    row.append(link);
                    if (resource.registryNamespace && resource.registryPath)
                        row.append(
                            ' ',
                            element('small', resource.registryNamespace + ':' + resource.registryPath, 'hint')
                        );
                    return row;
                })
            );
        }
        next.hidden = !catalog.nextAfter;
        next.textContent = locale.common('nextPage');
        next.href = trackingCatalogHref(current.gridKey, current.search, catalog.nextAfter);
        first.hidden = !current.after;
        first.textContent = locale.t('trackingCatalogFirst');
        first.href = trackingCatalogHref(current.gridKey, current.search);
        history.textContent = locale.common('history');
        history.href = historyHref(current.gridKey);
    };
}
