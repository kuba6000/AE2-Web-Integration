import type { ApiError } from '../../app/api.js';
import type { createThemeContext } from '../../app/theme-context.js';
import type { TerminalState, createTerminal } from '../../app/terminal.js';
import type { Translator as Locale } from '../../app/i18n.js';
type Terminal = ReturnType<typeof createTerminal>;

import { createSettings } from '../../app/storage.js';
import { plainMinecraftText } from '../../app/minecraft-text.js';
import { renderMinecraftText } from './minecraft-text.js';
import { navigateToGrid, cpuHref, historyHref } from '../../app/router.js';
import { createCraftingView } from './crafting.js';
import { createCpuView } from './cpus.js';
import { createHistoryView } from './history.js';
import { createSettingsView } from './settings.js';
import { createAboutView } from './about.js';
import { slotQuantity } from './resource-quantity.js';
import { infoCircle } from './icons/hackernoon/info-circle.js';
import { terminalIcons as symbols, craftingHammer } from './icons/pixel/terminal.js';
import { userIcon } from './icons/hackernoon/user.js';
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

function iconButton(group: 'filter' | 'sort', value: keyof typeof symbols) {
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
            appearance: 'Appearance',
            light: 'Light',
            dark: 'Dark',
            system: 'System',
            defaultTheme: 'Default',
            themeOptions: 'Theme options',
            resourceIcons: 'Resource icons'
        },
        pl: {
            appearance: 'Wygląd',
            light: 'Jasny',
            dark: 'Ciemny',
            system: 'Systemowy',
            defaultTheme: 'Domyślny',
            themeOptions: 'Opcje motywu',
            resourceIcons: 'Ikony zasobów'
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
    let displayIcons = settings.get('resourceIcons', true) !== false;
    application.displayIcons(displayIcons);
    root.innerHTML = `<div class="window-frame control-panel"><header class="site-header"><div class="brand"><span class="brand-mark">${craftingHammer}</span><h1>AE2 <span>Web Integration</span></h1></div><div class="account-controls">
        <span class="account-user">${userIcon}<span id="username"></span></span><button id="logout" data-text="logout"></button></div></header>
        <dl class="network-summary"><dt><span data-text="network"></span>:</dt><dd id="selected-network"></dd></dl>
        <nav class="view-tabs"><a href="#/" data-view="home" data-text="home"></a><a id="terminal-link" data-view="items" data-text="terminal" hidden></a><a id="cpu-link" data-view="cpus" data-text="cpus" hidden></a><a id="history-link" data-view="history" data-text="history" hidden></a><a id="settings-link" data-view="settings" data-text="gridSettings" hidden></a><a href="#/server-settings" data-view="server-settings" data-text="serverSettings"></a><a href="#/web-settings" data-view="web-settings" data-text="webSettings"></a><a href="#/about" data-view="about" data-text="about"></a></nav></div>
        <div class="workspace" id="workspace">
        <div class="terminal-tools" id="terminal-tools" hidden>
        <div role="group" data-label="resources">${(['all', 'stored', 'craftable'] as const).map((value) => iconButton('filter', value)).join('')}</div>
        <div role="group" data-label="sort">${(['name', 'quantity', 'id'] as const).map((value) => iconButton('sort', value)).join('')}</div></div>
        <div class="window-frame" id="window">
        <div id="network-message" role="status"></div>
        <section id="home"><h2 data-text="home"></h2><p data-text="homeHelp"></p>
        <label class="home-network"><span data-text="network"></span><select id="network"></select></label><div id="networks"></div></section>
        <section id="server-settings" hidden><h2 data-text="serverSettings"></h2></section>
        <section id="web-settings" hidden><h2 data-text="webSettings"></h2><div class="web-preferences">
        <label><span data-text="language"></span><select id="language"><option value="en">English</option><option value="pl">Polski</option></select></label>
        <label class="checkbox"><input type="checkbox" id="auto-refresh"><span data-text="autoRefresh"></span></label>
        <label><span data-text="theme"></span><select disabled><option value="default" data-theme-text="defaultTheme"></option></select></label>
        <section><h3 data-theme-text="themeOptions"></h3>
        <label class="checkbox"><input type="checkbox" id="resource-icons"><span data-theme-text="resourceIcons"></span></label>
        <label><span data-theme-text="appearance"></span><select id="appearance"><option value="system" data-theme-text="system"></option><option value="light" data-theme-text="light"></option><option value="dark" data-theme-text="dark"></option></select></label>
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
        '#resource-icons': HTMLInputElement;
        '#language': HTMLElementTagNameMap['select'];
        '#appearance': HTMLElementTagNameMap['select'];
        '#logout': HTMLElementTagNameMap['button'];
        '#username': HTMLElementTagNameMap['span'];
        '#network': HTMLElementTagNameMap['select'];
        '#selected-network': HTMLElementTagNameMap['dd'];
        '#auto-refresh': HTMLElementTagNameMap['input'];
        '#terminal-link': HTMLElementTagNameMap['a'];
        '#cpu-link': HTMLElementTagNameMap['a'];
        '#history-link': HTMLElementTagNameMap['a'];
        '#settings-link': HTMLElementTagNameMap['a'];
        '#workspace': HTMLElementTagNameMap['div'];
        '#terminal-tools': HTMLElementTagNameMap['div'];
        '#window': HTMLElementTagNameMap['div'];
        '#network-message': HTMLElementTagNameMap['div'];
        '#home': HTMLElementTagNameMap['section'];
        '#web-settings': HTMLElementTagNameMap['section'];
        '#server-settings': HTMLElementTagNameMap['section'];
        '#networks': HTMLElementTagNameMap['div'];
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
        HTMLButtonElement & { dataset: { preference: 'filter' | 'sort'; value: keyof typeof symbols } }
    > = root.querySelectorAll('.tool-button');
    const craftingView = createCraftingView(find('#window'), application);
    const itemIcons = application.icons.observe(find('#item-scroll'), paintResourceIcon);
    const cpuView = createCpuView(find('#window'), application, { workspace: find('#workspace') });
    const historyView = createHistoryView(find('#window'));
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

    let language: TerminalState['preferences']['language'];

    let locale: Locale;
    let page = 0;

    let listInput: { items: TerminalState['items']; signature: string } | undefined;

    let state: TerminalState;

    let rows: ReturnType<typeof createItemRow>[] = [];

    let allFiltered: TerminalState['items'] = [];
    function hideTooltip() {
        itemTooltip = null;
        tooltip.hidden = true;
    }

    function positionTooltip(x: number, y: number) {
        const box = tooltip.getBoundingClientRect();
        tooltip.style.left = `${Math.max(8, Math.min(x + 14, innerWidth - box.width - 8))}px`;
        tooltip.style.top = `${Math.max(8, y + box.height + 24 > innerHeight ? y - box.height - 10 : y + 16)}px`;
    }

    function showTooltip(item: TerminalState['items'][number], x: number, y: number) {
        const name = element('strong');
        name.append(renderMinecraftText(item.itemname));
        tooltip.replaceChildren(
            name,
            element('code', item.itemid),
            element('span', `${locale.common('quantity')}: ${locale.number(item.quantity)}`),
            element('span', locale.common(item.craftable ? 'craftableYes' : 'craftableNo'))
        );
        tooltip.hidden = false;
        positionTooltip(x, y);
    }

    function showItemTooltip(row: ReturnType<typeof createItemRow>, x: number, y: number, pointer: boolean) {
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
        toolButtons.forEach((button) => {
            const label =
                button.dataset.preference === 'sort'
                    ? `${locale.common('sort')}: ${locale.common(button.dataset.value)}`
                    : locale.common(button.dataset.value);
            button.setAttribute('aria-label', label);
        });
        find('#search').placeholder = locale.common('searchHint');
        find('#search').setAttribute('aria-description', locale.common('searchHelp'));
        find('#previous-page').setAttribute('aria-label', locale.common('previousPage'));
        find('#next-page').setAttribute('aria-label', locale.common('nextPage'));
    }
    function renderNetworks() {
        const select = find('#network');
        const gridKey = state.route.gridKey || state.selectedGridKey;
        const current = new Map([...select.options].map((option) => [option.value, option]));
        const entries = [
            { key: '', label: locale.common('chooseNetwork') },
            ...state.grids.map((grid) => ({
                key: grid.key,
                label: `${grid.owner || locale.common('unknownOwner')} · ${grid.key}`
            }))
        ];
        if (gridKey && !state.grids.some((grid) => grid.key === gridKey)) {
            entries.push({ key: gridKey, label: gridKey });
        }
        for (const entry of entries) {
            const option = current.get(entry.key) || document.createElement('option');
            option.value = entry.key;
            option.textContent = entry.label;
            option.disabled = !entry.key;
            if (!option.parentNode) select.append(option);
            current.delete(entry.key);
        }
        for (const option of current.values()) option.remove();
        select.value = gridKey || '';
        const selectedNetwork = find('#selected-network');
        selectedNetwork.textContent = select.value
            ? select.selectedOptions[0].textContent
            : locale.common('noNetworkSelected');
        selectedNetwork.title = selectedNetwork.textContent;
        const networkMessage =
            state.gridStatus === 'loading'
                ? locale.common('loading')
                : state.gridError
                  ? locale.common(state.gridError)
                  : !state.grids.length
                    ? `${locale.common('noNetworks')}. ${locale.common('noNetworksHelp')}`
                    : '';
        find('#network-message').textContent = networkMessage;
        const networks = find('#networks');
        const focused = (
            networks.contains(document.activeElement) ? document.activeElement : null
        ) as HTMLAnchorElement | null;
        const links = new Map(([...networks.children] as HTMLAnchorElement[]).map((link) => [link.dataset.key, link]));
        state.grids.forEach((grid, index) => {
            const link = links.get(grid.key) || element('a', '', 'network');
            link.dataset.key = grid.key;
            link.href = `#/grids/${encodeURIComponent(grid.key)}/items`;
            link.replaceChildren(
                element('strong', locale.common('gridOwner', { owner: grid.owner || locale.common('unknownOwner') })),
                element('code', grid.key),
                element('span', locale.common('cpuCount', { count: grid.cpuCount }))
            );
            if (networks.children[index] !== link) networks.insertBefore(link, networks.children[index] || null);
            links.delete(grid.key);
        });
        for (const link of links.values()) link.remove();
        if (focused?.isConnected && document.activeElement !== focused) focused.focus({ preventScroll: true });
    }
    function renderItems() {
        const signature = [
            state.search,
            state.preferences.filter,
            state.preferences.sort,
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
                .map((item) => ({ item, name: plainMinecraftText(item.itemname) }))
                .filter(({ item, name }) => {
                    const text = `${name} ${item.itemid}`.toLocaleLowerCase(language);
                    const mod = item.itemid.split(':')[0].toLocaleLowerCase(language);
                    return terms.every((term) =>
                        term.startsWith('@') ? mod.includes(term.slice(1)) : text.includes(term)
                    );
                })
                .filter(
                    ({ item }) =>
                        state.preferences.filter === 'all' ||
                        (state.preferences.filter === 'stored' ? item.quantity > 0 : item.craftable)
                )
                .sort((a, b) =>
                    state.preferences.sort === 'quantity'
                        ? b.item.quantity - a.item.quantity || a.name.localeCompare(b.name, language)
                        : state.preferences.sort === 'id'
                          ? a.item.itemid.localeCompare(b.item.itemid, language)
                          : a.name.localeCompare(b.name, language)
                )
                .map(({ item }) => item);
            listInput = { items: state.items, signature };
            renderPage();
        }
        itemIcons.update(
            state.route.view === 'items' ? rows.map((row) => ({ element: row.button, icon: row.item.icon })) : [],
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

    function createItemRow(item: TerminalState['items'][number]) {
        const row = {
            item,
            li: element('li'),
            button: element('button', '', 'item'),
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
            row.name.replaceChildren(renderMinecraftText(item.itemname));
            row.quantity.textContent = slotQuantity(item.quantity, locale);
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
            state.route.view === 'items' ? rows.map((row) => ({ element: row.button, icon: row.item.icon })) : [],
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
        name.append(renderMinecraftText(item.itemname));
        details.replaceChildren(
            name,
            element('code', item.itemid),
            element('p', `${locale.common('quantity')}: ${locale.number(item.quantity)}`),
            element('p', locale.common(item.craftable ? 'craftableYes' : 'craftableNo'))
        );
        if (!item.itemKey) details.append(element('p', locale.common('identityUnavailable'), 'hint'));
        if (item.craftable && item.itemKey) {
            const order = craftingView.order(item, state.crafting, locale);
            if (order.parentNode !== find('#order')) find('#order').append(order);
        }
    }

    function render(next: TerminalState) {
        state = next;
        if (language !== state.preferences.language) {
            language = state.preferences.language;
            updateLabels();
        }
        document.documentElement.dataset.appearance = appearance;
        const languageSelect = find('#language');
        if (![...languageSelect.options].some((option) => option.value === language)) {
            const option = element('option', language);
            option.value = language;
            languageSelect.append(option);
        }
        languageSelect.value = language;
        find('#appearance').value = appearance;
        toolButtons.forEach((button) =>
            button.setAttribute(
                'aria-pressed',
                String(state.preferences[button.dataset.preference] === button.dataset.value)
            )
        );
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
        find('#home').hidden = state.route.view !== 'home';
        find('#web-settings').hidden = state.route.view !== 'web-settings';
        find('#server-settings').hidden = state.route.view !== 'server-settings';
        find('#terminal').hidden = state.route.view !== 'items';
        find('#terminal-tools').hidden = state.route.view !== 'items';
        find('#resource-panel').hidden = state.route.view !== 'items';
        find('#workspace').classList.toggle(
            'with-terminal',
            state.route.view === 'items' || (state.route.view === 'cpus' && state.route.cpuKey !== null)
        );
        find('#missing').hidden = state.route.view !== 'missing';
        renderNetworks();
        renderItems();
        renderDetails();
        craftingView.render(state.route, state.crafting, locale);
        cpuView.render(state.route, state.cpus, locale);
        historyView.render(state.route, state.history, locale);
        settingsView.render(state.route, state.settings, locale);
        aboutView.render(state.route, locale);
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
    const network = find('#network');
    network.addEventListener('change', () => navigateToGrid(network.value));
    const search = find('#search');
    search.addEventListener('input', () => {
        hideTooltip();
        application.search(search.value);
    });
    const showSearchHelp = () => {
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
    const iconToggle = find('#resource-icons');
    iconToggle.checked = displayIcons;
    root.classList.toggle('resource-icons-enabled', displayIcons);
    iconToggle.addEventListener('change', () => {
        displayIcons = iconToggle.checked;
        settings.set('resourceIcons', displayIcons);
        root.classList.toggle('resource-icons-enabled', displayIcons);
        application.displayIcons(displayIcons);
    });
    appearanceSelect.addEventListener('change', () => {
        if (!appearances.includes(appearanceSelect.value)) return;
        appearance = appearanceSelect.value;
        settings.set('appearance', appearance);
        document.documentElement.dataset.appearance = appearance;
    });
    toolButtons.forEach((button) => {
        const show = () => {
            itemTooltip = null;
            tooltip.textContent = button.getAttribute('aria-label');
            tooltip.hidden = false;
            const box = button.getBoundingClientRect();
            positionTooltip(box.right, box.top);
        };
        button.addEventListener('click', () => {
            application.preference(button.dataset.preference, button.dataset.value);
            hideTooltip();
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
    find('#logout').addEventListener('click', async () => {
        find('#logout').disabled = true;
        try {
            await logout();
        } catch (error) {
            find('#network-message').textContent = locale.common((error as ApiError).status);
            find('#logout').disabled = false;
        }
    });

    const keydown = (event: KeyboardEvent) => {
        if (event.key === 'Escape') hideTooltip();
    };
    window.addEventListener('scroll', hideTooltip, true);
    window.addEventListener('resize', hideTooltip);
    window.addEventListener('keydown', keydown);
    // Paint empty slots using the same column width as the real resource grid.
    const list = find('#items');
    const slotBackground = new ResizeObserver(([entry]) => {
        if (!entry || entry.contentRect.width === 0) return;
        const columns = getComputedStyle(list).gridTemplateColumns.split(' ').length;
        list.style.setProperty('--slot-width', `${entry.contentRect.width / columns}px`);
        const terminal = find('#terminal');
        terminal.style.setProperty(
            '--grid-inset',
            `${list.getBoundingClientRect().left - terminal.getBoundingClientRect().left}px`
        );
    });
    slotBackground.observe(list);
    const unsubscribe = application.subscribe(render);
    return () => {
        unsubscribe();
        slotBackground.disconnect();
        cpuView.dispose();
        itemIcons.dispose();
        hideTooltip();
        window.removeEventListener('scroll', hideTooltip, true);
        window.removeEventListener('resize', hideTooltip);
        window.removeEventListener('keydown', keydown);
        delete document.documentElement.dataset.appearance;
        root.replaceChildren();
    };
}
