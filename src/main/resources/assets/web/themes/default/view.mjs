import { createTranslator } from '../../app/i18n.mjs';
import { navigateToGrid, cpuHref, historyHref } from '../../app/router.mjs';
import { createCraftingView } from './crafting.mjs';
import { createCpuView } from './cpus.mjs';
import { createHistoryView } from './history.mjs';
import { createSettingsView } from './settings.mjs';

const PAGE_SIZE = 100;

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
function iconButton(group, value) {
    return `<button type="button" class="tool-button" data-preference="${group}" data-value="${value}"><svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">${symbols[value]}</svg></button>`;
}

/** Theme renderer. Server operations and preference ownership are supplied by the application. */
export function mount(root, application, { base, logout }) {
    root.innerHTML = `<header class="site-header"><div class="brand"><span class="brand-mark" aria-hidden="true">ME</span><h1>AE2 <span>Web Integration</span></h1></div><div class="preferences">
        <label><span data-text="language"></span><select id="language"><option value="en">English</option><option value="pl">Polski</option></select></label>
        <label><span data-text="appearance"></span><select id="appearance"><option value="system" data-text="system"></option><option value="light" data-text="light"></option><option value="dark" data-text="dark"></option></select></label>
        <button id="logout" data-text="logout"></button></div></header>
        <section class="network-bar"><label><span data-text="network"></span><select id="network"></select></label>
        <button id="refresh" data-text="refresh"></button><label class="checkbox"><input type="checkbox" id="auto-refresh"><span data-text="autoRefresh"></span></label></section>
        <nav class="view-tabs"><a href="#/" data-view="home" data-text="home"></a><a id="terminal-link" data-view="items" data-text="terminal" hidden></a><a id="cpu-link" data-view="cpus" data-text="cpus" hidden></a><a id="history-link" data-view="history" data-text="history" hidden></a><a id="settings-link" data-view="settings" data-text="gridSettings" hidden></a></nav>
        <div class="workspace" id="workspace">
        <div class="terminal-tools" id="terminal-tools" hidden>
        <div role="group" data-label="resources">${['all', 'stored', 'craftable'].map((value) => iconButton('filter', value)).join('')}</div>
        <div role="group" data-label="sort">${['name', 'quantity', 'id'].map((value) => iconButton('sort', value)).join('')}</div></div>
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
    const find = (selector) => root.querySelector(selector);
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
    let hideTimer;
    let language;
    let locale;
    let page = 0;
    let listInput;
    let state;
    let rows = [];
    let allFiltered = [];
    function hideTooltip() {
        clearTimeout(hideTimer);
        tooltip.hidden = true;
    }
    function positionTooltip(x, y) {
        const box = tooltip.getBoundingClientRect();
        tooltip.style.left = `${Math.max(8, Math.min(x + 14, innerWidth - box.width - 8))}px`;
        tooltip.style.top = `${Math.max(8, y + box.height + 24 > innerHeight ? y - box.height - 10 : y + 16)}px`;
    }
    function showTooltip(item, x, y) {
        tooltip.replaceChildren(
            element('strong', item.itemname),
            element('code', item.itemid),
            element('span', `${locale.t('quantity')}: ${locale.number(item.quantity)}`),
            element('span', locale.t(item.craftable ? 'craftableYes' : 'craftableNo'))
        );
        tooltip.hidden = false;
        positionTooltip(x, y);
    }
    function updateLabels() {
        locale = createTranslator(language);
        document.documentElement.lang = language;
        root.querySelectorAll('[data-text]').forEach((node) => {
            node.textContent = locale.t(node.dataset.text);
        });
        root.querySelectorAll('[data-label]').forEach((node) =>
            node.setAttribute('aria-label', locale.t(node.dataset.label))
        );
        root.querySelectorAll('.tool-button').forEach((button) => {
            const label =
                button.dataset.preference === 'sort'
                    ? `${locale.t('sort')}: ${locale.t(button.dataset.value)}`
                    : locale.t(button.dataset.value);
            button.setAttribute('aria-label', label);
        });
        find('#search').placeholder = locale.t('searchHint');
        find('#previous-page').setAttribute('aria-label', locale.t('previousPage'));
        find('#next-page').setAttribute('aria-label', locale.t('nextPage'));
    }
    function renderNetworks() {
        const select = find('#network');
        const current = new Map([...select.options].map((option) => [option.value, option]));
        const entries = [
            { key: '', label: locale.t('chooseNetwork') },
            ...state.grids.map((grid) => ({
                key: grid.key,
                label: `${grid.owner || locale.t('unknownOwner')} · ${grid.key}`
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
                ? locale.t('loading')
                : state.gridError
                  ? locale.t(state.gridError)
                  : !state.grids.length
                    ? `${locale.t('noNetworks')}. ${locale.t('noNetworksHelp')}`
                    : '';
        find('#network-message').textContent = networkMessage;
        const networks = find('#networks');
        const focused = networks.contains(document.activeElement) ? document.activeElement : null;
        const links = new Map([...networks.children].map((link) => [link.dataset.key, link]));
        state.grids.forEach((grid, index) => {
            const link = links.get(grid.key) || element('a', '', 'network');
            link.dataset.key = grid.key;
            link.href = `#/grids/${encodeURIComponent(grid.key)}/items`;
            link.replaceChildren(
                element('strong', locale.t('gridOwner', { owner: grid.owner || locale.t('unknownOwner') })),
                element('code', grid.key),
                element('span', locale.t('cpuCount', { count: grid.cpuCount }))
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
                .filter((item) => `${item.itemname} ${item.itemid}`.toLocaleLowerCase(language).includes(search))
                .filter(
                    (item) =>
                        state.preferences.filter === 'all' ||
                        (state.preferences.filter === 'stored' ? item.quantity > 0 : item.craftable)
                )
                .sort((a, b) =>
                    state.preferences.sort === 'quantity'
                        ? b.quantity - a.quantity || a.itemname.localeCompare(b.itemname, language)
                        : state.preferences.sort === 'id'
                          ? a.itemid.localeCompare(b.itemid, language)
                          : a.itemname.localeCompare(b.itemname, language)
                );
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
                    ? locale.t('loading')
                    : state.itemError
                      ? locale.t(state.itemError)
                      : state.itemStatus === 'ready'
                        ? !state.items.length
                            ? locale.t('empty')
                            : !allFiltered.length
                              ? locale.t('noMatches')
                              : locale.t('resourceCount', { count: allFiltered.length })
                        : '';
            if (state.refreshing && state.itemStatus === 'ready') message += ` · ${locale.t('refreshing')}`;
        }
        find('#item-message').textContent = message;
        find('#clear').hidden = state.itemStatus !== 'ready' || !state.items.length || !!allFiltered.length;
    }
    function renderPage() {
        hideTooltip();
        const list = find('#items');
        const focused = rows.find((row) => row.button === document.activeElement)?.item.itemKey;
        page = Math.min(page, Math.max(0, Math.ceil(allFiltered.length / PAGE_SIZE) - 1));
        rows = [];
        const children = allFiltered.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE).map((item) => {
            const li = element('li');
            const button = element('button', '', 'item');
            button.type = 'button';
            button.classList.toggle('selected', item === state.selected);
            button.setAttribute('aria-pressed', String(item === state.selected));
            button.append(
                element('strong', item.itemname),
                element('span', locale.number(item.quantity), 'quantity'),
                element('small', item.craftable ? locale.t('craftableYes') : '')
            );
            button.addEventListener('click', () => {
                application.select(item);
                hideTooltip();
            });
            button.addEventListener('pointerenter', (event) => {
                if (event.pointerType === 'touch') return;
                hideTimer = setTimeout(() => showTooltip(item, event.clientX, event.clientY), 350);
            });
            button.addEventListener('pointermove', (event) => {
                if (!tooltip.hidden) positionTooltip(event.clientX, event.clientY);
            });
            button.addEventListener('pointerleave', hideTooltip);
            button.addEventListener('focus', () => {
                const box = button.getBoundingClientRect();
                showTooltip(item, box.left, box.bottom);
            });
            button.addEventListener('blur', hideTooltip);
            rows.push({ item, button });
            li.append(button);
            return li;
        });
        list.replaceChildren(...children);
        if (focused) rows.find((row) => row.item.itemKey === focused)?.button.focus({ preventScroll: true });
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
            details.replaceChildren(element('p', locale.t('selectItem')));
            return;
        }
        details.replaceChildren(
            element('h4', item.itemname),
            element('code', item.itemid),
            element('p', `${locale.t('quantity')}: ${locale.number(item.quantity)}`),
            element('p', locale.t(item.craftable ? 'craftableYes' : 'craftableNo'))
        );
        if (!item.itemKey) details.append(element('p', locale.t('identityUnavailable'), 'hint'));
        if (item.craftable && item.itemKey) {
            const order = craftingView.order(item, state.crafting, locale);
            if (order.parentNode !== find('#order')) find('#order').append(order);
        }
    }
    function render(next) {
        state = next;
        if (language !== state.preferences.language) {
            language = state.preferences.language;
            updateLabels();
        }
        document.documentElement.dataset.appearance = state.preferences.appearance;
        find('#language').value = language;
        find('#appearance').value = state.preferences.appearance;
        root.querySelectorAll('.tool-button').forEach((button) =>
            button.setAttribute(
                'aria-pressed',
                String(state.preferences[button.dataset.preference] === button.dataset.value)
            )
        );
        root.querySelectorAll('[data-view]').forEach((link) => {
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
        find('#terminal-link').href = `#/grids/${encodeURIComponent(state.route.gridKey)}/items`;
        find('#cpu-link').href = cpuHref(state.route.gridKey);
        find('#history-link').hidden = !state.route.gridKey;
        find('#history-link').href = historyHref(state.route.gridKey);
        find('#settings-link').hidden = !state.route.gridKey;
        find('#settings-link').href = `#/grids/${encodeURIComponent(state.route.gridKey)}/settings`;
        find('#updated').textContent = state.updatedAt
            ? locale.t('updated', { time: locale.time(state.updatedAt) })
            : '';
    }
    find('#network').addEventListener('change', (event) => navigateToGrid(event.target.value));
    find('#search').addEventListener('input', (event) => application.search(event.target.value));
    for (const name of ['language', 'appearance'])
        find(`#${name}`).addEventListener('change', (event) => application.preference(name, event.target.value));
    root.querySelectorAll('.tool-button').forEach((button) => {
        const show = () => {
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
    find('#auto-refresh').addEventListener('change', (event) =>
        application.preference('autoRefresh', event.target.checked)
    );
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
            find('#network-message').textContent = locale.t(error.status);
            find('#logout').disabled = false;
        }
    });
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
        root.replaceChildren();
    };
}
