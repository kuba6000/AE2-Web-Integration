/**
 * @typedef {ReturnType<typeof import('../../app/terminal.mjs').createTerminal>} Terminal
 * @typedef {Terminal['state']} TerminalState
 * @typedef {import('../../app/i18n.mjs').Translator} Locale
 */

import { createSettings } from '../../app/storage.mjs';
import { plainMinecraftText } from '../../app/minecraft-text.mjs';
import { renderMinecraftText } from './minecraft-text.mjs';
import { navigateToGrid, cpuHref, historyHref } from '../../app/router.mjs';
import { createCraftingView } from './crafting.mjs';
import { createCpuView } from './cpus.mjs';
import { createHistoryView } from './history.mjs';
import { createSettingsView } from './settings.mjs';

const PAGE_SIZE = 100;

/**
 * @template {keyof HTMLElementTagNameMap} Tag
 * @param {Tag} tag
 * @param {string} [text]
 * @param {string} [className]
 * @returns {HTMLElementTagNameMap[Tag]}
 */
function element(tag, text = '', className = '') {
    const node = document.createElement(tag);
    node.textContent = text;
    if (className) node.className = className;
    return node;
}

// Original UI symbols, drawn on a 24px grid; no game textures or external icon assets.
const symbols = {
    all: '<rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/>',
    stored: '<path d="M3 8h18v13H3zM2 3h20v5H2zM9 12h6"/>',
    craftable: '<path d="m4 3 17 17-3 3L1 6zM14 3l7 7M18 2l4 4-9 9-4-4z"/>',
    name: '<path d="M3 18 8 4l5 14M5 13h6M16 6h6l-6 12h6"/>',
    quantity: '<path d="M3 5h16M3 12h11M3 19h6M19 10v11m-3-3 3 3 3-3"/>',
    id: '<path d="M9 3 6 21M18 3l-3 18M3 9h18M2 15h18"/>'
};
/**
 * @param {'filter' | 'sort'} group
 * @param {keyof typeof symbols} value
 */
function iconButton(group, value) {
    return `<button type="button" class="tool-button" data-preference="${group}" data-value="${value}"><svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">${symbols[value]}</svg></button>`;
}

/**
 * Theme renderer. Server operations and preference ownership are supplied by the application.
 * @param {HTMLElement} root
 * @param {Terminal} application
 * @param {ReturnType<typeof import('../../app/theme-context.mjs').createThemeContext> & {base: URL, logout: () => Promise<void>}} options
 */
export function mount(root, application, { base, logout, settings, i18n }) {
    i18n.register({
        en: { appearance: 'Appearance', light: 'Light', dark: 'Dark', system: 'System' },
        pl: { appearance: 'Wygląd', light: 'Jasny', dark: 'Ciemny', system: 'Systemowy' }
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
    root.innerHTML = `<div class="window-frame control-panel"><header class="site-header"><div class="brand"><span class="brand-mark" aria-hidden="true">ME</span><h1>AE2 <span>Web Integration</span></h1></div><div class="preferences">
        <label><span data-text="language"></span><select id="language"><option value="en">English</option><option value="pl">Polski</option></select></label>
        <label><span data-theme-text="appearance"></span><select id="appearance"><option value="system" data-theme-text="system"></option><option value="light" data-theme-text="light"></option><option value="dark" data-theme-text="dark"></option></select></label>
        <button id="logout" data-text="logout"></button></div></header>
        <section class="network-bar"><label><span data-text="network"></span><select id="network"></select></label>
        <button id="refresh" data-text="refresh"></button><label class="checkbox"><input type="checkbox" id="auto-refresh"><span data-text="autoRefresh"></span></label></section>
        <nav class="view-tabs"><a href="#/" data-view="home" data-text="home"></a><a id="terminal-link" data-view="items" data-text="terminal" hidden></a><a id="cpu-link" data-view="cpus" data-text="cpus" hidden></a><a id="history-link" data-view="history" data-text="history" hidden></a><a id="settings-link" data-view="settings" data-text="gridSettings" hidden></a></nav></div>
        <div class="workspace" id="workspace">
        <div class="terminal-tools" id="terminal-tools" hidden>
        <div role="group" data-label="resources">${/** @type {const} */ (['all', 'stored', 'craftable']).map((value) => iconButton('filter', value)).join('')}</div>
        <div role="group" data-label="sort">${/** @type {const} */ (['name', 'quantity', 'id']).map((value) => iconButton('sort', value)).join('')}</div></div>
        <div class="window-frame" id="window">
        <div id="network-message" role="status"></div>
        <section id="home"><h2 data-text="home"></h2><p data-text="homeHelp"></p><div id="networks"></div></section>
        <section id="terminal" hidden><div class="terminal-heading"><h2 data-text="terminal"></h2>
        <label class="search"><span class="sr-only" data-text="search"></span><input id="search" type="search"></label></div>
        <div class="terminal-body"><p id="item-message" role="status"></p>
        <div id="item-scroll" role="region" data-label="resources" tabindex="0"><ul id="items"></ul></div>
        <div id="pages"><button id="previous-page">←</button><span id="page-count"></span><button id="next-page">→</button></div>
        <button id="clear" data-text="resetSearch" hidden></button></div></section>
        <p id="missing" data-text="invalidRoute" hidden></p></div>
        <aside id="resource-panel" hidden><h3 data-text="details"></h3><div id="details"></div><div id="order"></div></aside></div>
        <footer><span id="updated" aria-live="off"></span><a id="legacy" data-text="previous"></a></footer>`;
    /**
     * IDs and element types belong to the static markup created above.
     * @typedef {{
     *   '#language': HTMLElementTagNameMap['select'],
     *   '#appearance': HTMLElementTagNameMap['select'],
     *   '#logout': HTMLElementTagNameMap['button'],
     *   '#network': HTMLElementTagNameMap['select'],
     *   '#refresh': HTMLElementTagNameMap['button'],
     *   '#auto-refresh': HTMLElementTagNameMap['input'],
     *   '#terminal-link': HTMLElementTagNameMap['a'],
     *   '#cpu-link': HTMLElementTagNameMap['a'],
     *   '#history-link': HTMLElementTagNameMap['a'],
     *   '#settings-link': HTMLElementTagNameMap['a'],
     *   '#workspace': HTMLElementTagNameMap['div'],
     *   '#terminal-tools': HTMLElementTagNameMap['div'],
     *   '#window': HTMLElementTagNameMap['div'],
     *   '#network-message': HTMLElementTagNameMap['div'],
     *   '#home': HTMLElementTagNameMap['section'],
     *   '#networks': HTMLElementTagNameMap['div'],
     *   '#terminal': HTMLElementTagNameMap['section'],
     *   '#search': HTMLElementTagNameMap['input'],
     *   '#item-message': HTMLElementTagNameMap['p'],
     *   '#item-scroll': HTMLElementTagNameMap['div'],
     *   '#items': HTMLElementTagNameMap['ul'],
     *   '#pages': HTMLElementTagNameMap['div'],
     *   '#previous-page': HTMLElementTagNameMap['button'],
     *   '#page-count': HTMLElementTagNameMap['span'],
     *   '#next-page': HTMLElementTagNameMap['button'],
     *   '#clear': HTMLElementTagNameMap['button'],
     *   '#missing': HTMLElementTagNameMap['p'],
     *   '#resource-panel': HTMLElementTagNameMap['aside'],
     *   '#details': HTMLElementTagNameMap['div'],
     *   '#order': HTMLElementTagNameMap['div'],
     *   '#updated': HTMLElementTagNameMap['span'],
     *   '#legacy': HTMLElementTagNameMap['a'],
     * }} ViewElements
     */
    /**
     * @template {keyof ViewElements} Selector
     * @param {Selector} selector
     * @returns {ViewElements[Selector]}
     */
    const find = (selector) => /** @type {ViewElements[Selector]} */ (root.querySelector(selector));
    /** @type {NodeListOf<HTMLButtonElement & {dataset: {preference: 'filter' | 'sort', value: keyof typeof symbols}}>} */
    const toolButtons = root.querySelectorAll('.tool-button');
    const craftingView = createCraftingView(find('#window'), application);
    const cpuView = createCpuView(find('#window'), application);
    const historyView = createHistoryView(find('#window'));
    const settingsView = createSettingsView(find('#window'), application);
    find('#legacy').href = base.href;
    const tooltip = element('div', '', 'tooltip');
    tooltip.id = 'resource-tooltip';
    tooltip.role = 'tooltip';
    tooltip.hidden = true;
    root.append(tooltip);
    /** @type {{row: ReturnType<typeof createItemRow>, x: number, y: number, pointer: boolean} | null | undefined} */
    let itemTooltip;
    let updatingRows = false;
    /** @type {TerminalState['preferences']['language']} */
    let language;
    /** @type {Locale} */
    let locale;
    let page = 0;
    /** @type {{items: TerminalState['items'], signature: string} | undefined} */
    let listInput;
    /** @type {TerminalState} */
    let state;
    /** @type {ReturnType<typeof createItemRow>[]} */
    let rows = [];
    /** @type {TerminalState['items']} */
    let allFiltered = [];
    function hideTooltip() {
        itemTooltip = null;
        tooltip.hidden = true;
    }
    /**
     * @param {number} x
     * @param {number} y
     */
    function positionTooltip(x, y) {
        const box = tooltip.getBoundingClientRect();
        tooltip.style.left = `${Math.max(8, Math.min(x + 14, innerWidth - box.width - 8))}px`;
        tooltip.style.top = `${Math.max(8, y + box.height + 24 > innerHeight ? y - box.height - 10 : y + 16)}px`;
    }
    /**
     * @param {TerminalState['items'][number]} item
     * @param {number} x
     * @param {number} y
     */
    function showTooltip(item, x, y) {
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
    /**
     * @param {ReturnType<typeof createItemRow>} row
     * @param {number} x
     * @param {number} y
     * @param {boolean} pointer
     */
    function showItemTooltip(row, x, y, pointer) {
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
        /** @type {NodeListOf<HTMLElement & {dataset: {themeText: string}}>} */ (
            root.querySelectorAll('[data-theme-text]')
        ).forEach((node) => {
            node.textContent = locale.t(node.dataset.themeText);
        });
        /** @type {NodeListOf<HTMLElement & {dataset: {text: string}}>} */ (
            root.querySelectorAll('[data-text]')
        ).forEach((node) => {
            node.textContent = locale.common(node.dataset.text);
        });
        /** @type {NodeListOf<HTMLElement & {dataset: {label: string}}>} */ (
            root.querySelectorAll('[data-label]')
        ).forEach((node) => node.setAttribute('aria-label', locale.common(node.dataset.label)));
        toolButtons.forEach((button) => {
            const label =
                button.dataset.preference === 'sort'
                    ? `${locale.common('sort')}: ${locale.common(button.dataset.value)}`
                    : locale.common(button.dataset.value);
            button.setAttribute('aria-label', label);
        });
        find('#search').placeholder = locale.common('searchHint');
        find('#previous-page').setAttribute('aria-label', locale.common('previousPage'));
        find('#next-page').setAttribute('aria-label', locale.common('nextPage'));
    }
    function renderNetworks() {
        const select = find('#network');
        const current = new Map([...select.options].map((option) => [option.value, option]));
        const entries = [
            { key: '', label: locale.common('chooseNetwork') },
            ...state.grids.map((grid) => ({
                key: grid.key,
                label: `${grid.owner || locale.common('unknownOwner')} · ${grid.key}`
            }))
        ];
        if (state.route.gridKey && !state.grids.some((grid) => grid.key === state.route.gridKey)) {
            entries.push({ key: state.route.gridKey, label: state.route.gridKey });
        }
        for (const entry of entries) {
            const option = current.get(entry.key) || document.createElement('option');
            option.value = entry.key;
            option.textContent = entry.label;
            if (!option.parentNode) select.append(option);
            current.delete(entry.key);
        }
        for (const option of current.values()) option.remove();
        select.value = state.route.gridKey || '';
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
        const focused = /** @type {HTMLAnchorElement | null} */ (
            networks.contains(document.activeElement) ? document.activeElement : null
        );
        const links = new Map(
            /** @type {HTMLAnchorElement[]} */ ([...networks.children]).map((link) => [link.dataset.key, link])
        );
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
            const search = state.search.trim().toLocaleLowerCase(language);
            allFiltered = state.items
                .map((item) => ({ item, name: plainMinecraftText(item.itemname) }))
                .filter(({ item, name }) => `${name} ${item.itemid}`.toLocaleLowerCase(language).includes(search))
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
                      : state.itemStatus === 'ready'
                        ? !state.items.length
                            ? locale.common('empty')
                            : !allFiltered.length
                              ? locale.common('noMatches')
                              : locale.common('resourceCount', { count: allFiltered.length })
                        : '';
            if (state.refreshing && state.itemStatus === 'ready') message += ` · ${locale.common('refreshing')}`;
        }
        find('#item-message').textContent = message;
        find('#clear').hidden = state.itemStatus !== 'ready' || !state.items.length || !!allFiltered.length;
    }
    /**
     * @param {TerminalState['items'][number]} item
     */
    function createItemRow(item) {
        const row = {
            item,
            li: element('li'),
            button: element('button', '', 'item'),
            name: element('strong'),
            quantity: element('span', '', 'quantity'),
            craftable: element('small')
        };
        const { button } = row;
        button.type = 'button';
        button.append(row.name, row.quantity, row.craftable);
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
            row.quantity.textContent = locale.number(item.quantity);
            row.craftable.textContent = item.craftable ? locale.common('craftableYes') : '';
            row.button.classList.toggle('selected', item === state.selected);
            row.button.setAttribute('aria-pressed', String(item === state.selected));
            if (list.children[index] !== row.li) list.insertBefore(row.li, list.children[index] || null);
            return row;
        });
        const retained = new Set(next);
        for (const row of rows) if (!retained.has(row)) row.li.remove();
        rows = next;
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
    /**
     * @param {TerminalState} next
     */
    function render(next) {
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
        /** @type {NodeListOf<HTMLAnchorElement>} */ (root.querySelectorAll('[data-view]')).forEach((link) => {
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
        find('#terminal').hidden = state.route.view !== 'items';
        find('#terminal-tools').hidden = state.route.view !== 'items';
        find('#resource-panel').hidden = state.route.view !== 'items';
        find('#workspace').classList.toggle('with-terminal', state.route.view === 'items');
        find('#missing').hidden = state.route.view !== 'missing';
        renderNetworks();
        renderItems();
        renderDetails();
        craftingView.render(state.route, state.crafting, locale);
        cpuView.render(state.route, state.cpus, locale);
        historyView.render(state.route, state.history, locale);
        settingsView.render(state.route, state.settings, locale);
        find('#cpu-link').hidden = !state.route.gridKey;
        find('#terminal-link').hidden = !state.route.gridKey;
        find('#terminal-link').href = `#/grids/${encodeURIComponent(String(state.route.gridKey))}/items`;
        find('#cpu-link').href = cpuHref(state.route.gridKey);
        find('#history-link').hidden = !state.route.gridKey;
        find('#history-link').href = historyHref(state.route.gridKey);
        find('#settings-link').hidden = !state.route.gridKey;
        find('#settings-link').href = `#/grids/${encodeURIComponent(String(state.route.gridKey))}/settings`;
        find('#updated').textContent = state.updatedAt
            ? locale.common('updated', { time: locale.time(state.updatedAt) })
            : '';
    }
    const network = find('#network');
    network.addEventListener('change', () => navigateToGrid(network.value));
    const search = find('#search');
    search.addEventListener('input', () => application.search(search.value));
    const languageSelect = find('#language');
    languageSelect.addEventListener('change', () => application.preference('language', languageSelect.value));
    const appearanceSelect = find('#appearance');
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
    find('#refresh').addEventListener('click', () => application.refresh({ reloadDetail: true }));
    find('#clear').addEventListener('click', () => {
        application.preference('filter', 'all');
        application.search('');
        find('#search').focus();
    });
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
            find('#network-message').textContent = locale.common(
                /** @type {import('../../app/api.mjs').ApiError} */ (error).status
            );
            find('#logout').disabled = false;
        }
    });
    /** @param {KeyboardEvent} event */
    const keydown = (event) => {
        if (event.key === 'Escape') hideTooltip();
    };
    window.addEventListener('scroll', hideTooltip, true);
    window.addEventListener('resize', hideTooltip);
    window.addEventListener('keydown', keydown);
    const unsubscribe = application.subscribe(render);
    return () => {
        unsubscribe();
        hideTooltip();
        window.removeEventListener('scroll', hideTooltip, true);
        window.removeEventListener('resize', hideTooltip);
        window.removeEventListener('keydown', keydown);
        delete document.documentElement.dataset.appearance;
        root.replaceChildren();
    };
}
