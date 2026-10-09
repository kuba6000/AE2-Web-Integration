import { registryId } from './resource-metadata.js';
import type { ApiError } from '../../app/api.js';
import type { createThemeContext } from '../../app/theme-context.js';
import type { TerminalState, createTerminal } from '../../app/terminal.js';
import type { Translator as Locale } from '../../app/i18n.js';
type Terminal = ReturnType<typeof createTerminal>;

import { createSettings } from '../../app/storage.js';
import { plainMinecraftText } from '../../app/minecraft-text.js';
import { renderMinecraftText } from './minecraft-text.js';
import { cpuHref, historyHref } from '../../app/router.js';
import { createCraftingView } from './crafting.js';
import { createHomeView } from './home.js';
import { createCpuView } from './cpus.js';
import { createHistoryView } from './history.js';
import { networkLabel } from './network.js';
import { createSettingsView } from './settings.js';
import { createAboutView } from './about.js';
import { slotQuantity } from './resource-quantity.js';
import { createSlotGrid } from './slot-grid.js';
import { infoCircle } from './icons/hackernoon/info-circle.js';
import { terminalIcons as symbols, craftingHammer } from './icons/pixel/terminal.js';
import { userIcon } from './icons/hackernoon/user.js';
import { refreshIcon } from './icons/hackernoon/refresh.js';
import { powerIcon } from './icons/pixel/account.js';
import { createResourceIcon, paintResourceIcon } from './resource-icon.js';

const PAGE_SIZE = 100;

function element<Tag extends keyof HTMLElementTagNameMap>(
    tag: Tag,
    text = '',
    className = ''
): HTMLElementTagNameMap[Tag] {
    const node = document.createElement(tag);
    node.textContent = text;
    if (className) node.className = className;
    return node;
}

function iconButton(group: 'filter' | 'sort' | 'sortOrder' | 'showItems' | 'showFluids', value: keyof typeof symbols) {
    return `<button type="button" class="tool-button" data-preference="${group}" data-value="${value}">${symbols[value]}</button>`;
}

/* Theme renderer. Server operations and preference ownership are supplied by the application. */

export function mount(
    root: HTMLElement,
    application: Terminal,
    {
        base,
        user,
        modVersion,
        logout,
        settings,
        i18n
    }: ReturnType<typeof createThemeContext> & {
        base: URL;
        user: { username: string; isAdmin: boolean };
        modVersion: string | null;
        logout: () => Promise<void>;
    }
) {
    i18n.register({
        en: {
            showItems: 'Items',
            showFluids: 'Fluids',
            on: 'On',
            off: 'Off',
            resourceKinds: 'Resource types',
            componentCount: 'Components',
            damage: 'Damage',
            sortOrder: 'Sort order',
            ascending: 'Ascending',
            descending: 'Descending',
            availability: 'Availability',
            nextState: 'Next: {state}',
            appearance: 'Appearance',
            light: 'Light',
            dark: 'Dark',
            system: 'System',
            defaultTheme: 'Default',
            generalOptions: 'General',
            terminalDisplay: 'Terminal display',
            detailedItems: 'Icons and names',
            compactItems: 'Icons only',
            namesOnly: 'Names only',
            iconsUnavailable: 'The server does not provide an icon pack.',
            iconsLoading: 'Checking server icon availability…',
            iconsError: 'Could not check server icon availability.',
            iconsOffer: 'The server provides item icons.',
            enableIcons: 'Enable icons',
            dismissIcons: 'Dismiss icon suggestion'
        },
        pl: {
            showItems: 'Przedmioty',
            showFluids: 'Płyny',
            on: 'Włączone',
            off: 'Wyłączone',
            resourceKinds: 'Rodzaje zasobów',
            componentCount: 'Komponenty',
            damage: 'Uszkodzenie',
            sortOrder: 'Kierunek sortowania',
            ascending: 'Rosnąco',
            descending: 'Malejąco',
            availability: 'Dostępność',
            nextState: 'Następnie: {state}',
            appearance: 'Wygląd',
            light: 'Jasny',
            dark: 'Ciemny',
            system: 'Systemowy',
            defaultTheme: 'Domyślny',
            generalOptions: 'Ogólne',
            terminalDisplay: 'Wyświetlanie terminala',
            detailedItems: 'Ikony i nazwy',
            compactItems: 'Same ikony',
            namesOnly: 'Same nazwy',
            iconsUnavailable: 'Serwer nie udostępnia paczki ikon.',
            iconsLoading: 'Sprawdzanie dostępności ikon na serwerze…',
            iconsError: 'Nie udało się sprawdzić dostępności ikon na serwerze.',
            iconsOffer: 'Serwer udostępnia ikony przedmiotów.',
            enableIcons: 'Włącz ikony',
            dismissIcons: 'Zamknij podpowiedź o ikonach'
        }
    });
    const appearances = ['light', 'dark', 'system'];
    const previousSettings = createSettings(base, 'ui');
    let savedAppearance = settings.get('appearance');
    if (savedAppearance === null) {
        // Adopt the previously shared preference; its meaning belongs only to this theme.
        savedAppearance = previousSettings.get('appearance');
        if (typeof savedAppearance === 'string' && appearances.includes(savedAppearance)) {
            if (settings.set('appearance', savedAppearance)) previousSettings.remove('appearance');
        }
    } else {
        previousSettings.remove('appearance');
    }
    let appearance =
        typeof savedAppearance === 'string' && appearances.includes(savedAppearance) ? savedAppearance : 'system';
    type TerminalDisplay = 'names' | 'both' | 'icons';
    const savedDisplay = settings.get('terminalDisplay');
    let terminalDisplay: TerminalDisplay =
        savedDisplay === 'names' || savedDisplay === 'both' || savedDisplay === 'icons'
            ? savedDisplay
            : settings.get('resourceIcons', true) === false
              ? 'names'
              : savedDisplay === 'compact'
                ? 'icons'
                : 'both';
    if (savedDisplay !== terminalDisplay || settings.get('resourceIcons') !== null) {
        if (settings.set('terminalDisplay', terminalDisplay)) settings.remove('resourceIcons');
    }
    application.displayIcons(terminalDisplay !== 'names');
    let effectiveDisplay: TerminalDisplay = 'names';
    let iconSuggestionDismissed = settings.get('iconSuggestionDismissed') === true;
    root.innerHTML = `<div class="window-frame control-panel"><header class="site-header"><div class="brand"><span class="brand-mark">${craftingHammer}</span><h1 aria-label="AE2 Web Integration"><span class="brand-full">AE2 <span>Web Integration</span></span><span class="brand-short" aria-hidden="true">AE2<span>WI</span></span></h1></div><span id="selected-network" tabindex="0" hidden></span><div class="account-controls">
        <span class="account-user">${userIcon}<span id="username"></span></span><button id="logout" class="account-action" data-label="logout">${powerIcon}</button><button id="refresh" class="account-action" data-label="refresh" aria-busy="false">${refreshIcon}</button><span id="activity" class="sr-only" role="status" aria-live="off" hidden><span id="activity-text"></span></span></div></header>
        <nav class="view-tabs"><a href="#/" data-view="home" data-text="home"></a><a id="settings-link" data-view="settings" data-text="network" hidden></a><a id="terminal-link" data-view="items" data-text="terminal" hidden></a><a id="cpu-link" data-view="cpus" data-text="cpus" hidden></a><a id="history-link" data-view="history" data-text="history" hidden></a><a href="#/server-settings" data-view="server-settings" data-text="serverSettings"></a><a href="#/web-settings" data-view="web-settings" data-text="webSettings"></a><a href="#/about" data-view="about" data-text="about"></a></nav><p id="session-message" role="alert" hidden></p></div>
        <div class="info-notices" id="info-notices" role="region" data-label="notices" tabindex="0" hidden><section class="window-frame info-notice" id="icon-notice" aria-labelledby="icon-notice-text" hidden>
        ${infoCircle}<p id="icon-notice-text" data-theme-text="iconsOffer"></p>
        <button type="button" id="enable-icons" data-theme-text="enableIcons"></button>
        <button type="button" id="dismiss-icons">×</button></section><section class="window-frame info-notice" id="tracking-notice" aria-labelledby="tracking-notice-text" hidden>${infoCircle}<p><span id="tracking-notice-text"></span> <a id="tracking-settings-link"></a></p></section></div>
        <div class="workspace" id="workspace">
        <div class="terminal-tools" id="terminal-tools" hidden>
        <div role="group" data-label="resources">${iconButton('filter', 'all')}</div>
        <div role="group" data-theme-label="resourceKinds">${iconButton('showItems', 'itemsOn')}${iconButton('showFluids', 'fluidsOn')}</div>
        <div role="group" data-label="sort">${iconButton('sort', 'name')}${iconButton('sortOrder', 'ascending')}</div></div>
        <div class="window-frame" id="window">
        <div id="network-message" role="status"></div>
        <section id="server-settings" hidden><h2 data-text="serverSettings"></h2></section>
        <section id="web-settings" hidden><h2 data-text="webSettings"></h2><div class="web-preferences">
        <section class="web-preferences-section" aria-labelledby="general-options-title"><h3 id="general-options-title" data-theme-text="generalOptions"></h3>
        <label><span data-text="language"></span><select id="language"><option value="en">English</option><option value="pl">Polski</option></select></label>
        <label class="web-preference-toggle"><span data-text="autoRefresh"></span><input type="checkbox" id="auto-refresh"></label></section>
        <section class="web-preferences-section" aria-labelledby="appearance-options-title"><h3 id="appearance-options-title" data-theme-text="appearance"></h3>
        <label><span data-text="theme"></span><select disabled><option value="default" data-theme-text="defaultTheme"></option></select></label>
        <label><span data-theme-text="appearance"></span><select id="appearance"><option value="system" data-theme-text="system"></option><option value="light" data-theme-text="light"></option><option value="dark" data-theme-text="dark"></option></select></label></section>
        <section class="web-preferences-section" aria-labelledby="terminal-options-title"><h3 id="terminal-options-title" data-text="terminal"></h3>
        <div class="web-preference-field"><label><span data-theme-text="terminalDisplay"></span><select id="terminal-display" aria-describedby="icon-availability"><option value="names" data-theme-text="namesOnly"></option><option value="both" data-theme-text="detailedItems"></option><option value="icons" data-theme-text="compactItems"></option></select></label>
        <p class="hint" id="icon-availability" role="status"></p></div>
        </section></div></section>
        <section id="terminal" hidden><div class="terminal-heading"><h2 data-text="terminal"></h2>
        <label class="search"><span class="sr-only" data-text="search"></span><input id="search" type="search"></label></div>
        <div class="terminal-body"><p id="item-message" role="status"></p>
        <div id="item-scroll" role="region" data-label="resources" tabindex="0"><ul id="items"></ul></div>
        <div id="pages"><button id="previous-page">←</button><span id="page-count"></span><button id="next-page">→</button></div></div></section>
        <p id="missing" data-text="invalidRoute" hidden></p></div>
        <aside id="resource-panel" hidden><h3>${infoCircle}<span data-text="details"></span></h3><div id="details"></div><div id="order"></div></aside></div>
        <footer><span id="updated" aria-live="off"></span><a id="legacy" data-text="previous"></a></footer>`;
    /* IDs and element types belong to the static markup created above. */
    type ViewElements = {
        '#terminal-display': HTMLSelectElement;
        '#icon-availability': HTMLParagraphElement;
        '#icon-notice': HTMLElement;
        '#info-notices': HTMLElement;
        '#tracking-notice': HTMLElement;
        '#tracking-notice-text': HTMLElement;
        '#tracking-settings-link': HTMLAnchorElement;
        '#enable-icons': HTMLButtonElement;
        '#dismiss-icons': HTMLButtonElement;
        '#language': HTMLElementTagNameMap['select'];
        '#appearance': HTMLElementTagNameMap['select'];
        '#logout': HTMLElementTagNameMap['button'];
        '#session-message': HTMLElementTagNameMap['p'];
        '#username': HTMLElementTagNameMap['span'];
        '#selected-network': HTMLElementTagNameMap['span'];
        '#refresh': HTMLButtonElement;
        '#activity': HTMLSpanElement;
        '#activity-text': HTMLSpanElement;
        '#auto-refresh': HTMLElementTagNameMap['input'];
        '#terminal-link': HTMLElementTagNameMap['a'];
        '#cpu-link': HTMLElementTagNameMap['a'];
        '#history-link': HTMLElementTagNameMap['a'];
        '#settings-link': HTMLElementTagNameMap['a'];
        '#workspace': HTMLElementTagNameMap['div'];
        '#terminal-tools': HTMLElementTagNameMap['div'];
        '#window': HTMLElementTagNameMap['div'];
        '#network-message': HTMLElementTagNameMap['div'];
        '#web-settings': HTMLElementTagNameMap['section'];
        '#server-settings': HTMLElementTagNameMap['section'];
        '#terminal': HTMLElementTagNameMap['section'];
        '#search': HTMLElementTagNameMap['input'];
        '#item-message': HTMLElementTagNameMap['p'];
        '#item-scroll': HTMLElementTagNameMap['div'];
        '#items': HTMLElementTagNameMap['ul'];
        '#pages': HTMLElementTagNameMap['div'];
        '#previous-page': HTMLElementTagNameMap['button'];
        '#page-count': HTMLElementTagNameMap['span'];
        '#next-page': HTMLElementTagNameMap['button'];
        '#missing': HTMLElementTagNameMap['p'];
        '#resource-panel': HTMLElementTagNameMap['aside'];
        '#details': HTMLElementTagNameMap['div'];
        '#order': HTMLElementTagNameMap['div'];
        '#updated': HTMLElementTagNameMap['span'];
        '#legacy': HTMLElementTagNameMap['a'];
    };

    const find = <Selector extends keyof ViewElements>(selector: Selector): ViewElements[Selector] =>
        root.querySelector(selector) as ViewElements[Selector];
    find('#username').textContent = user.username;

    const toolButtons: NodeListOf<
        HTMLButtonElement & {
            dataset: {
                preference: 'filter' | 'sort' | 'sortOrder' | 'showItems' | 'showFluids';
                value: keyof typeof symbols;
            };
        }
    > = root.querySelectorAll('.tool-button');
    const homeView = createHomeView(find('#workspace'), application);
    const craftingView = createCraftingView(find('#window'), application, find('#workspace'), {
        bind: bindTooltip,
        hide: hideTooltip,
        metadata: (resource) => resourceMetadata(resource, 'span')
    });
    const itemIcons = application.icons.observe(find('#item-scroll'), paintResourceIcon);
    const slots = createSlotGrid(find('#items'), find('#item-scroll'), find('#terminal'));
    const cpuView = createCpuView(find('#window'), application, { workspace: find('#workspace'), settings });
    const historyView = createHistoryView(find('#window'), application, bindTooltip);
    const settingsView = createSettingsView(find('#window'), application);
    const aboutView = createAboutView(find('#window'), modVersion);
    find('#legacy').href = base.href;
    const tooltip = element('div', '', 'tooltip');
    tooltip.id = 'resource-tooltip';
    tooltip.role = 'tooltip';
    tooltip.hidden = true;
    root.append(tooltip);

    let itemTooltip:
        { row: ReturnType<typeof createItemRow>; x: number; y: number; pointer: boolean } | null | undefined;
    let updatingRows = false;
    let toolTooltip: HTMLButtonElement | null = null;
    let attachedTooltip: {
        target: HTMLElement;
        focusTarget: HTMLElement;
        content: () => Node[];
        pointer: boolean;
        x: number;
        y: number;
    } | null = null;

    let language: TerminalState['preferences']['language'];

    let locale: Locale;
    let page = 0;

    let listInput: { items: TerminalState['items']; signature: string } | undefined;

    let state: TerminalState;

    let rows: ReturnType<typeof createItemRow>[] = [];

    let allFiltered: TerminalState['items'] = [];
    function hideTooltip() {
        attachedTooltip?.focusTarget.removeAttribute('aria-describedby');
        attachedTooltip = null;
        toolTooltip = null;
        itemTooltip = null;
        tooltip.hidden = true;
    }

    function positionTooltip(x: number, y: number) {
        const box = tooltip.getBoundingClientRect();
        tooltip.style.left = `${Math.max(8, Math.min(x + 14, innerWidth - box.width - 8))}px`;
        tooltip.style.top = `${Math.max(8, y + box.height + 24 > innerHeight ? y - box.height - 10 : y + 16)}px`;
    }

    function resourceMetadata(
        item: Pick<TerminalState['items'][number], 'registryNamespace' | 'registryPath' | 'damage' | 'componentCount'>,
        tag: 'span' | 'p'
    ) {
        const id = registryId(item);
        const lines: HTMLElement[] = id ? [element('code', id)] : [];
        if (item.damage !== 0) {
            lines.push(element(tag, `${locale.t('damage')}: ${locale.number(item.damage)}`));
        }
        if (item.componentCount !== 0) {
            lines.push(
                element(
                    tag,
                    `${locale.t('componentCount')}: ${locale.number(item.componentCount)}`,
                    'resource-components'
                )
            );
        }
        return lines;
    }

    function showTooltip(item: TerminalState['items'][number], x: number, y: number) {
        const name = element('strong');
        name.append(renderMinecraftText(item.displayName));
        tooltip.replaceChildren(
            name,
            ...resourceMetadata(item, 'span'),
            element('span', `${locale.common('stored')}: ${locale.number(item.quantity)}`),
            element('span', locale.common(item.craftable ? 'craftableYes' : 'craftableNo'))
        );
        tooltip.hidden = false;
        positionTooltip(x, y);
    }

    function showItemTooltip(row: ReturnType<typeof createItemRow>, x: number, y: number, pointer: boolean) {
        hideTooltip();
        toolTooltip = null;
        itemTooltip = { row, x, y, pointer };
        showTooltip(row.item, x, y);
    }
    function refreshItemTooltip() {
        if (!itemTooltip) return;
        const { row, x, y, pointer } = itemTooltip;
        if (
            !row.button.isConnected ||
            (pointer ? !row.button.contains(document.elementFromPoint(x, y)) : document.activeElement !== row.button)
        ) {
            hideTooltip();
            return;
        }
        const box = row.button.getBoundingClientRect();
        showTooltip(row.item, pointer ? x : box.left, pointer ? y : box.bottom);
    }
    function refreshAttachedTooltip() {
        if (!attachedTooltip) return;
        const { target, focusTarget, content, pointer, x, y } = attachedTooltip;
        if (
            !target.isConnected ||
            !target.getClientRects().length ||
            (pointer ? !target.contains(document.elementFromPoint(x, y)) : document.activeElement !== focusTarget)
        ) {
            hideTooltip();
            return;
        }
        tooltip.replaceChildren(...content());
        tooltip.hidden = false;
        const box = target.getBoundingClientRect();
        positionTooltip(pointer ? x : box.left, pointer ? y : box.bottom);
    }
    function bindTooltip(target: HTMLElement, content: () => Node[], focusTarget = target) {
        let pointerInside = false;
        const show = (pointer: boolean, x = 0, y = 0) => {
            hideTooltip();
            attachedTooltip = { target, focusTarget, content, pointer, x, y };
            focusTarget.setAttribute('aria-describedby', tooltip.id);
            refreshAttachedTooltip();
        };
        const hide = () => {
            if (attachedTooltip?.target === target) hideTooltip();
        };
        target.addEventListener('pointerenter', (event) => {
            if (event.pointerType === 'touch' || pointerInside) return;
            // Replacing a hovered descendant can emit another enter without a leave.
            pointerInside = true;
            show(true, event.clientX, event.clientY);
        });
        target.addEventListener('pointermove', (event) => {
            if (attachedTooltip?.target === target && attachedTooltip.pointer) {
                attachedTooltip.x = event.clientX;
                attachedTooltip.y = event.clientY;
                positionTooltip(event.clientX, event.clientY);
            }
        });
        target.addEventListener('pointerleave', () => {
            pointerInside = false;
            hide();
        });
        focusTarget.addEventListener('focus', () => show(false));
        focusTarget.addEventListener('blur', hide);
    }
    function updateLabels() {
        locale = i18n.forLanguage(language);
        document.documentElement.lang = language;
        (
            root.querySelectorAll('[data-theme-text]') as NodeListOf<HTMLElement & { dataset: { themeText: string } }>
        ).forEach((node) => {
            node.textContent = locale.t(node.dataset.themeText);
        });
        (root.querySelectorAll('[data-text]') as NodeListOf<HTMLElement & { dataset: { text: string } }>).forEach(
            (node) => {
                node.textContent = locale.common(node.dataset.text);
            }
        );
        (root.querySelectorAll('[data-label]') as NodeListOf<HTMLElement & { dataset: { label: string } }>).forEach(
            (node) => node.setAttribute('aria-label', locale.common(node.dataset.label))
        );
        (root.querySelectorAll('[data-theme-label]') as NodeListOf<HTMLElement>).forEach((node) =>
            node.setAttribute('aria-label', locale.t(node.dataset.themeLabel!))
        );
        find('#dismiss-icons').setAttribute('aria-label', locale.t('dismissIcons'));
        find('#search').placeholder = locale.common('searchHint');
        find('#search').setAttribute('aria-description', locale.common('searchHelp'));
        find('#previous-page').setAttribute('aria-label', locale.common('previousPage'));
        find('#next-page').setAttribute('aria-label', locale.common('nextPage'));
    }
    bindTooltip(find('#selected-network'), () => {
        const grid = state.grids.find((grid) => grid.key === state.route.gridKey);
        const lines = [element('strong', locale.common('currentNetwork'))];
        if (grid?.name) lines.push(element('span', grid.name));
        lines.push(element('code', state.route.gridKey || ''));
        return lines;
    });
    function renderNetworks() {
        const gridKey = state.route.gridKey;
        const grid = state.grids.find((grid) => grid.key === gridKey);
        const selectedNetwork = find('#selected-network');
        selectedNetwork.hidden = !gridKey;
        const label = grid ? networkLabel(grid) : gridKey || '';
        if (selectedNetwork.textContent !== label) selectedNetwork.textContent = label;
        selectedNetwork.setAttribute('aria-label', `${locale.common('currentNetwork')}: ${label}`);
        const networkMessage =
            state.gridStatus === 'loading'
                ? locale.common('loading')
                : state.gridError
                  ? locale.common(state.gridError)
                  : !state.grids.length
                    ? `${locale.common('noNetworks')}. ${locale.common('noNetworksHelp')}`
                    : '';
        find('#network-message').textContent = networkMessage;
    }
    function renderItems() {
        const signature = [
            state.search,
            state.preferences.filter,
            state.preferences.sort,
            state.preferences.sortOrder,
            state.preferences.showItems,
            state.preferences.showFluids,
            language,
            state.route.gridKey
        ].join('\0');
        const changed = !listInput || listInput.items !== state.items || listInput.signature !== signature;
        if (changed) {
            if (listInput?.signature !== signature) {
                page = 0;
                find('#item-scroll').scrollTop = 0;
            }
            const terms = state.search.trim().toLocaleLowerCase(language).split(/\s+/);
            allFiltered = state.items
                .map((item) => ({ item, name: plainMinecraftText(item.displayName) }))
                .filter(({ item, name }) => {
                    const text = `${name} ${registryId(item)}`.toLocaleLowerCase(language);
                    const mod = (item.registryNamespace ?? '').toLocaleLowerCase(language);
                    return terms.every((term) =>
                        term.startsWith('@') ? mod.includes(term.slice(1)) : text.includes(term)
                    );
                })
                .filter(({ item }) =>
                    item.resourceType === 'ITEM'
                        ? state.preferences.showItems
                        : item.resourceType === 'FLUID'
                          ? state.preferences.showFluids
                          : true
                )
                .filter(
                    ({ item }) =>
                        state.preferences.filter === 'all' ||
                        (state.preferences.filter === 'stored' ? item.quantity > 0 : item.craftable)
                )
                .sort((a, b) => {
                    const primary =
                        state.preferences.sort === 'quantity'
                            ? a.item.quantity - b.item.quantity
                            : state.preferences.sort === 'id'
                              ? registryId(a.item).localeCompare(registryId(b.item), language)
                              : a.name.localeCompare(b.name, language);
                    return (
                        primary * (state.preferences.sortOrder === 'ascending' ? 1 : -1) ||
                        a.name.localeCompare(b.name, language) ||
                        registryId(a.item).localeCompare(registryId(b.item), language) ||
                        (a.item.itemKey || '').localeCompare(b.item.itemKey || '')
                    );
                })
                .map(({ item }) => item);
            listInput = { items: state.items, signature };
            renderPage();
        }
        itemIcons.update(
            state.route.view === 'items' && effectiveDisplay !== 'names'
                ? rows.map((row) => ({ element: row.button, icon: row.item.icon }))
                : [],
            state.itemIcons
        );
        for (const row of rows) {
            const selected = row.item === state.selected;
            row.button.classList.toggle('selected', selected);
            row.button.setAttribute('aria-pressed', String(selected));
        }
        let message = '';
        if (state.gridStatus === 'ready') {
            message =
                state.itemStatus === 'loading'
                    ? locale.common('loading')
                    : state.itemError
                      ? locale.common(state.itemError)
                      : state.itemStatus === 'ready' && !state.items.length
                        ? locale.common('empty')
                        : '';
        }
        find('#item-message').textContent = message;
    }

    const resourceMenu = element('div', '', 'window-frame resource-menu');
    resourceMenu.role = 'menu';
    resourceMenu.hidden = true;
    const menuCraft = element('button');
    menuCraft.type = 'button';
    menuCraft.role = 'menuitem';
    resourceMenu.append(menuCraft);
    root.append(resourceMenu);
    let menuItem: TerminalState['selected'] = null;
    let menuSource: HTMLElement | null = null;
    let menuGrid: string | null = null;
    function closeResourceMenu(restore: boolean) {
        if (resourceMenu.hidden) return;
        resourceMenu.hidden = true;
        if (restore && menuSource?.isConnected) menuSource.focus({ preventScroll: true });
        menuItem = null;
    }
    function showResourceMenu(item: TerminalState['items'][number], source: HTMLElement, x: number, y: number) {
        application.select(item);
        hideTooltip();
        menuItem = item;
        menuSource = source;
        menuGrid = state.route.gridKey;
        menuCraft.textContent = locale.common('craft');
        menuCraft.disabled = !item.craftable || !item.itemKey;
        resourceMenu.hidden = false;
        resourceMenu.style.left = `${Math.max(8, Math.min(x, innerWidth - resourceMenu.offsetWidth - 8))}px`;
        resourceMenu.style.top = `${Math.max(8, Math.min(y, innerHeight - resourceMenu.offsetHeight - 8))}px`;
        menuCraft.focus();
    }
    menuCraft.addEventListener('click', () => {
        const item = menuItem;
        const source = menuSource;
        closeResourceMenu(false);
        if (item && source) craftingView.open(item, source);
    });
    resourceMenu.addEventListener('keydown', (event) => {
        if (event.key === 'Tab') closeResourceMenu(false);
    });
    const outsideResourceMenu = (event: PointerEvent) => {
        if (!resourceMenu.contains(event.target as Node)) closeResourceMenu(false);
    };
    window.addEventListener('pointerdown', outsideResourceMenu);

    function createItemRow(item: TerminalState['items'][number]) {
        const row = {
            item,
            li: element('li'),
            button: element('button', '', 'item slot-frame'),
            name: element('strong'),
            quantity: element('span', '', 'quantity'),
            craftable: element('span', '', 'craftable-marker')
        };
        const { button } = row;
        button.type = 'button';
        row.craftable.role = 'img';
        row.craftable.innerHTML = craftingHammer;
        const amount = element('span', '', 'item-amount');
        amount.append(row.quantity, row.craftable);
        button.append(createResourceIcon(), row.name, amount);
        row.li.append(button);
        button.addEventListener('click', () => {
            application.select(row.item);
            hideTooltip();
        });
        button.addEventListener('mousedown', (event) => {
            if (event.button === 1 && row.item.craftable && row.item.itemKey) event.preventDefault();
        });
        button.addEventListener('auxclick', (event) => {
            if (event.button !== 1 || !row.item.craftable || !row.item.itemKey) return;
            event.preventDefault();
            application.select(row.item);
            hideTooltip();
            craftingView.open(row.item, button);
        });
        button.addEventListener('contextmenu', (event) => {
            event.preventDefault();
            showResourceMenu(row.item, button, event.clientX, event.clientY);
        });
        button.addEventListener('keydown', (event) => {
            if (event.key !== 'ContextMenu' && !(event.key === 'F10' && event.shiftKey)) return;
            event.preventDefault();
            const bounds = button.getBoundingClientRect();
            showResourceMenu(row.item, button, bounds.left, bounds.bottom);
        });
        button.addEventListener('pointerenter', (event) => {
            if (event.pointerType !== 'touch') showItemTooltip(row, event.clientX, event.clientY, true);
        });
        button.addEventListener('pointermove', (event) => {
            if (itemTooltip?.row === row && itemTooltip.pointer) {
                itemTooltip.x = event.clientX;
                itemTooltip.y = event.clientY;
                positionTooltip(event.clientX, event.clientY);
            }
        });
        button.addEventListener('pointerleave', hideTooltip);
        button.addEventListener('focus', () => {
            if (updatingRows) return;
            const box = button.getBoundingClientRect();
            showItemTooltip(row, box.left, box.bottom, false);
        });
        button.addEventListener('blur', () => {
            if (!updatingRows) hideTooltip();
        });
        return row;
    }
    function renderPage() {
        const list = find('#items');
        const focused = rows.find((row) => row.button === document.activeElement);
        const previous = new Map(rows.filter((row) => row.item.itemKey).map((row) => [row.item.itemKey, row]));
        page = Math.min(page, Math.max(0, Math.ceil(allFiltered.length / PAGE_SIZE) - 1));
        // Reordering can blur a focused button; restoring focus must preserve tooltip dismissal.
        updatingRows = true;
        const next = allFiltered.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE).map((item, index) => {
            const row = previous.get(item.itemKey) || createItemRow(item);
            row.item = item;
            row.name.replaceChildren(renderMinecraftText(item.displayName));
            row.quantity.textContent = slotQuantity(item.quantity, locale);
            if (effectiveDisplay === 'icons') {
                row.button.setAttribute(
                    'aria-label',
                    `${plainMinecraftText(item.displayName)}, ${locale.common('stored')}: ${locale.number(item.quantity)}, ${locale.common(item.craftable ? 'craftableYes' : 'craftableNo')}`
                );
            } else {
                row.button.removeAttribute('aria-label');
            }
            row.craftable.hidden = !item.craftable;
            row.craftable.setAttribute('aria-label', locale.common('craftableYes'));
            row.button.classList.toggle('selected', item === state.selected);
            row.button.setAttribute('aria-pressed', String(item === state.selected));
            if (list.children[index] !== row.li) list.insertBefore(row.li, list.children[index] || null);
            return row;
        });
        const retained = new Set(next);
        for (const row of rows) if (!retained.has(row)) row.li.remove();
        rows = next;
        itemIcons.update(
            state.route.view === 'items' && effectiveDisplay !== 'names'
                ? rows.map((row) => ({ element: row.button, icon: row.item.icon }))
                : [],
            state.itemIcons
        );
        if (focused?.button.isConnected && document.activeElement !== focused.button) {
            focused.button.focus({ preventScroll: true });
        }
        updatingRows = false;
        refreshItemTooltip();
        find('#pages').hidden = allFiltered.length <= PAGE_SIZE;
        find('#previous-page').disabled = page === 0;
        find('#next-page').disabled = (page + 1) * PAGE_SIZE >= allFiltered.length;
        find('#page-count').textContent = `${page + 1} / ${Math.max(1, Math.ceil(allFiltered.length / PAGE_SIZE))}`;
        slots.update(rows.length);
    }
    function renderDetails() {
        const details = find('#details');
        const item = state.selected;
        find('#order').hidden = !item?.craftable || !item.itemKey;
        if (!item) {
            details.replaceChildren(element('p', locale.common('selectItem')));
            return;
        }
        const name = element('h4');
        name.append(renderMinecraftText(item.displayName));
        details.replaceChildren(
            name,
            ...resourceMetadata(item, 'p'),
            element('p', `${locale.common('stored')}: ${locale.number(item.quantity)}`),
            element('p', locale.common(item.craftable ? 'craftableYes' : 'craftableNo'))
        );
        if (!item.itemKey) details.append(element('p', locale.common('identityUnavailable'), 'hint'));
        if (item.craftable && item.itemKey) {
            const order = craftingView.order(item, state.crafting, locale);
            if (order.parentNode !== find('#order')) find('#order').append(order);
        }
    }

    function renderDisplay() {
        const { available, status } = state.iconPack;
        const nextDisplay = available === true ? terminalDisplay : 'names';
        const changed = effectiveDisplay !== nextDisplay;
        effectiveDisplay = nextDisplay;
        const select = find('#terminal-display');
        select.value = effectiveDisplay;
        for (const option of select.options) option.disabled = option.value !== 'names' && available !== true;
        find('#icon-availability').textContent =
            status === 'error'
                ? locale.t('iconsError')
                : available === false
                  ? locale.t('iconsUnavailable')
                  : status === 'loading'
                    ? locale.t('iconsLoading')
                    : '';
        find('#items').dataset.display = effectiveDisplay;
        find('#items').classList.toggle('resource-icons-enabled', effectiveDisplay !== 'names');
        find('#workspace').classList.toggle('cpu-icons-enabled', effectiveDisplay !== 'names');
        find('#workspace').classList.toggle('product-icons-enabled', effectiveDisplay !== 'names');
        find('#icon-notice').hidden =
            state.route.view !== 'items' ||
            effectiveDisplay !== 'names' ||
            available !== true ||
            iconSuggestionDismissed;
        const routeGrid =
            state.gridStatus === 'ready' ? state.grids.find((grid) => grid.key === state.route.gridKey) : undefined;
        const trackingUnavailable =
            routeGrid?.isTrackingEnabled === false &&
            ((state.route.view === 'items' && state.itemStatus === 'ready') ||
                (state.route.view === 'cpus' &&
                    (state.cpus.status === 'ready' ||
                        (state.cpus.status === 'loading' && state.cpus.cpus.length > 0))));
        find('#tracking-notice').hidden = !trackingUnavailable;
        find('#tracking-notice-text').textContent = locale.common('trackingDisabledTip');
        find('#tracking-settings-link').textContent = locale.common('enableTracking');
        find('#tracking-settings-link').href = routeGrid
            ? `#/grids/${encodeURIComponent(routeGrid.key)}/settings`
            : '#/';
        find('#info-notices').hidden = find('#icon-notice').hidden && find('#tracking-notice').hidden;
        if (changed) {
            renderPage();
            hideTooltip();
        }
    }

    function render(next: TerminalState) {
        state = next;
        if (language !== state.preferences.language) {
            language = state.preferences.language;
            updateLabels();
        }
        renderDisplay();
        document.documentElement.dataset.appearance = appearance;
        const languageSelect = find('#language');
        if (![...languageSelect.options].some((option) => option.value === language)) {
            const option = element('option', language);
            option.value = language;
            languageSelect.append(option);
        }
        languageSelect.value = language;
        find('#appearance').value = appearance;
        toolButtons.forEach((button) => {
            if (button.dataset.preference === 'filter') {
                const current = state.preferences.filter;
                const next = current === 'all' ? 'stored' : current === 'stored' ? 'craftable' : 'all';
                if (button.dataset.value !== current) button.innerHTML = symbols[current];
                button.dataset.value = current;
                button.setAttribute('aria-label', `${locale.t('availability')}: ${locale.common(current)}`);
                button.setAttribute('aria-description', locale.t('nextState', { state: locale.common(next) }));
            } else if (button.dataset.preference === 'sort') {
                const current = state.preferences.sort;
                const next = current === 'name' ? 'quantity' : current === 'quantity' ? 'id' : 'name';
                if (button.dataset.value !== current) button.innerHTML = symbols[current];
                button.dataset.value = current;
                button.setAttribute('aria-label', `${locale.common('sort')}: ${locale.common(current)}`);
                button.setAttribute('aria-description', locale.t('nextState', { state: locale.common(next) }));
            } else if (button.dataset.preference === 'sortOrder') {
                const current = state.preferences.sortOrder;
                const next = current === 'ascending' ? 'descending' : 'ascending';
                if (button.dataset.value !== current) button.innerHTML = symbols[current];
                button.dataset.value = current;
                button.setAttribute('aria-label', `${locale.t('sortOrder')}: ${locale.t(current)}`);
                button.setAttribute('aria-description', locale.t('nextState', { state: locale.t(next) }));
            } else {
                const enabled = state.preferences[button.dataset.preference];
                const value =
                    button.dataset.preference === 'showItems'
                        ? enabled
                            ? 'itemsOn'
                            : 'itemsOff'
                        : enabled
                          ? 'fluidsOn'
                          : 'fluidsOff';
                if (button.dataset.value !== value) button.innerHTML = symbols[value];
                button.dataset.value = value;
                button.setAttribute(
                    'aria-label',
                    `${locale.t(button.dataset.preference)}: ${locale.t(enabled ? 'on' : 'off')}`
                );
                button.setAttribute(
                    'aria-description',
                    locale.t('nextState', { state: locale.t(enabled ? 'off' : 'on') })
                );
                button.setAttribute('aria-pressed', String(enabled));
            }
        });
        if (toolTooltip) showToolTooltip(toolTooltip);
        (root.querySelectorAll('[data-view]') as NodeListOf<HTMLAnchorElement>).forEach((link) => {
            if (
                link.dataset.view === state.route.view ||
                (link.dataset.view === 'items' && state.route.view === 'plan')
            )
                link.setAttribute('aria-current', 'page');
            else link.removeAttribute('aria-current');
        });
        find('#auto-refresh').checked = state.preferences.autoRefresh;
        if (find('#search').value !== state.search) find('#search').value = state.search;
        find('#window').hidden = state.route.view === 'home';
        find('#workspace').classList.toggle('with-home', state.route.view === 'home');
        homeView.render(state, locale);
        find('#web-settings').hidden = state.route.view !== 'web-settings';
        find('#server-settings').hidden = state.route.view !== 'server-settings';
        find('#terminal').hidden = state.route.view !== 'items';
        find('#terminal-tools').hidden = state.route.view !== 'items';
        find('#resource-panel').hidden = state.route.view !== 'items';
        find('#workspace').classList.toggle(
            'with-terminal',
            state.route.view === 'items' ||
                state.route.view === 'plan' ||
                (state.route.view === 'cpus' && state.route.cpuKey !== null)
        );
        find('#missing').hidden = state.route.view !== 'missing';
        renderNetworks();
        renderItems();
        renderDetails();
        craftingView.render(state, locale, effectiveDisplay !== 'names');
        refreshAttachedTooltip();
        if (state.route.view !== 'items' || state.itemStatus === 'error' || menuGrid !== state.route.gridKey)
            closeResourceMenu(false);
        cpuView.render(state.route, state.cpus, locale);
        const cpuMutations =
            state.route.view === 'cpus' ? Object.values(state.cpus.outcomes).filter((outcome) => outcome.mutation) : [];
        const craftingVisible = state.route.view === 'items' || state.route.view === 'plan';
        const actionPending =
            cpuMutations.length > 0 ||
            (craftingVisible && !!state.crafting.mutation) ||
            (state.route.view === 'settings' && state.settings.saving);
        const viewLoading =
            (state.route.view === 'cpus' && (state.cpus.reading || state.cpus.status === 'loading')) ||
            (craftingVisible && ['loading', 'calculating'].includes(state.crafting.status)) ||
            (state.route.view === 'history' && (state.history.reading || state.history.status === 'loading')) ||
            (state.route.view === 'settings' && state.settings.status === 'loading') ||
            (state.route.view === 'home' && state.home.networks.some((network) => network.status === 'loading'));
        const active =
            state.refreshingView || state.refreshing || state.gridStatus === 'loading' || viewLoading || actionPending;
        find('#refresh').setAttribute('aria-busy', String(active));
        find('#activity').hidden = !active;
        find('#activity').setAttribute('aria-label', locale.common('activity'));
        find('#activity').setAttribute('aria-live', actionPending ? 'polite' : 'off');
        find('#activity-text').textContent = active
            ? locale.common(
                  cpuMutations.some((outcome) => outcome.mutation === 'cancel')
                      ? 'cpuCancelling'
                      : cpuMutations.length
                        ? 'cpuUpdating'
                        : state.route.view === 'cpus'
                          ? 'cpuRefreshing'
                          : 'loading'
              )
            : '';
        historyView.render(state, locale);
        settingsView.render(state, locale);
        aboutView.render(state.route, locale);
        slots.update(rows.length);
        const gridKey = state.route.gridKey || state.selectedGridKey;
        find('#cpu-link').hidden = !gridKey;
        find('#terminal-link').hidden = !gridKey;
        find('#terminal-link').href = `#/grids/${encodeURIComponent(String(gridKey))}/items`;
        find('#cpu-link').href = cpuHref(gridKey);
        find('#history-link').hidden = !gridKey;
        find('#history-link').href = historyHref(gridKey);
        find('#settings-link').hidden = !gridKey;
        find('#settings-link').href = `#/grids/${encodeURIComponent(String(gridKey))}/settings`;
        find('#updated').textContent = state.updatedAt
            ? locale.common('updated', { time: locale.time(state.updatedAt) })
            : '';
    }
    const search = find('#search');
    search.addEventListener('input', () => {
        hideTooltip();
        application.search(search.value);
    });
    const showSearchHelp = () => {
        hideTooltip();
        toolTooltip = null;
        itemTooltip = null;
        tooltip.replaceChildren(
            ...locale
                .common('searchHelp')
                .split('\n')
                .map((line) => element('span', line))
        );
        tooltip.hidden = false;
        const box = search.getBoundingClientRect();
        positionTooltip(box.left, box.bottom);
    };
    search.addEventListener('pointerenter', showSearchHelp);
    search.addEventListener('focus', showSearchHelp);
    search.addEventListener('pointerleave', hideTooltip);
    search.addEventListener('blur', hideTooltip);
    const languageSelect = find('#language');
    languageSelect.addEventListener('change', () => application.preference('language', languageSelect.value));
    const appearanceSelect = find('#appearance');
    const displaySelect = find('#terminal-display');
    function acknowledgeIconSuggestion() {
        iconSuggestionDismissed = true;
        settings.set('iconSuggestionDismissed', true);
    }
    function selectDisplay(value: TerminalDisplay) {
        terminalDisplay = value;
        settings.set('terminalDisplay', terminalDisplay);
        acknowledgeIconSuggestion();
        application.displayIcons(terminalDisplay !== 'names');
        renderDisplay();
    }
    displaySelect.addEventListener('change', () => {
        if (!['names', 'both', 'icons'].includes(displaySelect.value)) return;
        if (displaySelect.value !== 'names' && state.iconPack.available !== true) return;
        selectDisplay(displaySelect.value as TerminalDisplay);
    });
    find('#enable-icons').addEventListener('click', () => {
        selectDisplay('both');
        find('#search').focus();
    });
    find('#dismiss-icons').addEventListener('click', () => {
        acknowledgeIconSuggestion();
        renderDisplay();
        find('#search').focus();
    });
    appearanceSelect.addEventListener('change', () => {
        if (!appearances.includes(appearanceSelect.value)) return;
        appearance = appearanceSelect.value;
        settings.set('appearance', appearance);
        document.documentElement.dataset.appearance = appearance;
    });
    function showToolTooltip(button: HTMLButtonElement) {
        hideTooltip();
        itemTooltip = null;
        toolTooltip = button;
        tooltip.textContent = [button.getAttribute('aria-label'), button.getAttribute('aria-description')]
            .filter(Boolean)
            .join(' · ');
        tooltip.hidden = false;
        const box = button.getBoundingClientRect();
        positionTooltip(box.right, box.top);
    }
    toolButtons.forEach((button) => {
        const show = () => showToolTooltip(button);
        button.addEventListener('click', () => {
            if (button.dataset.preference === 'filter') {
                const current = state.preferences.filter;
                application.preference(
                    'filter',
                    current === 'all' ? 'stored' : current === 'stored' ? 'craftable' : 'all'
                );
            } else if (button.dataset.preference === 'sort') {
                const current = state.preferences.sort;
                application.preference(
                    'sort',
                    current === 'name' ? 'quantity' : current === 'quantity' ? 'id' : 'name'
                );
            } else if (button.dataset.preference === 'sortOrder') {
                application.preference(
                    'sortOrder',
                    state.preferences.sortOrder === 'ascending' ? 'descending' : 'ascending'
                );
            } else application.preference(button.dataset.preference, !state.preferences[button.dataset.preference]);
            show();
        });
        button.addEventListener('pointerenter', (event) => {
            if (event.pointerType !== 'touch') show();
        });
        button.addEventListener('pointerleave', hideTooltip);
        button.addEventListener('focus', show);
        button.addEventListener('blur', hideTooltip);
    });
    const autoRefresh = find('#auto-refresh');
    autoRefresh.addEventListener('change', () => application.preference('autoRefresh', autoRefresh.checked));
    find('#previous-page').addEventListener('click', () => {
        page--;
        renderPage();
        find('#item-scroll').scrollTop = 0;
    });
    find('#next-page').addEventListener('click', () => {
        page++;
        renderPage();
        find('#item-scroll').scrollTop = 0;
    });
    for (const button of [find('#logout'), find('#refresh')])
        bindTooltip(button, () => [element('span', button.getAttribute('aria-label') || '')]);
    find('#refresh').addEventListener('click', () => {
        void application.refresh({ reloadDetail: true });
    });
    find('#logout').addEventListener('click', async () => {
        find('#logout').disabled = true;
        find('#session-message').hidden = true;
        try {
            await logout();
        } catch (error) {
            find('#session-message').textContent = locale.common((error as ApiError).status);
            find('#session-message').hidden = false;
            find('#logout').disabled = false;
        }
    });

    const keydown = (event: KeyboardEvent) => {
        if (event.key === 'Escape') {
            hideTooltip();
            closeResourceMenu(true);
        }
    };
    window.addEventListener('scroll', hideTooltip, true);
    window.addEventListener('resize', hideTooltip);
    window.addEventListener('keydown', keydown);
    const unsubscribe = application.subscribe(render);
    return () => {
        unsubscribe();
        slots.dispose();
        cpuView.dispose();
        homeView.dispose();
        craftingView.dispose();
        resourceMenu.remove();
        window.removeEventListener('pointerdown', outsideResourceMenu);
        historyView.dispose();
        itemIcons.dispose();
        hideTooltip();
        window.removeEventListener('scroll', hideTooltip, true);
        window.removeEventListener('resize', hideTooltip);
        window.removeEventListener('keydown', keydown);
        delete document.documentElement.dataset.appearance;
        root.replaceChildren();
    };
}
