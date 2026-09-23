import { createTranslator } from '../../app/i18n.mjs';
import { navigateToGrid } from '../../app/router.mjs';

const PAGE_SIZE = 100;

function element(tag, text = '', className = '') {
    const node = document.createElement(tag);
    node.textContent = text;
    if (className) node.className = className;
    return node;
}

/** Temporary renderer. All server operations and preference ownership are supplied by the application. */
export function mount(root, application, { base, logout }) {
    root.innerHTML = `<header><h1>AE2 Web Integration</h1><div class="preferences">
        <label><span data-text="language"></span><select id="language"><option value="en">English</option><option value="pl">Polski</option></select></label>
        <label><span data-text="appearance"></span><select id="appearance"><option value="system" data-text="system"></option><option value="light" data-text="light"></option><option value="dark" data-text="dark"></option></select></label>
        <button id="logout" data-text="logout"></button></div></header>
        <nav><a href="#/" data-text="home"></a><a id="legacy" data-text="previous"></a></nav>
        <p class="hint" data-text="previousHelp"></p>
        <section class="network-bar"><label><span data-text="network"></span><select id="network"></select></label>
        <button id="refresh" data-text="refresh"></button><label class="checkbox"><input type="checkbox" id="auto-refresh"><span data-text="autoRefresh"></span></label></section>
        <div id="network-message" role="status"></div>
        <section id="home"><h2 data-text="home"></h2><p data-text="homeHelp"></p><div id="networks"></div></section>
        <section id="terminal" hidden><h2 data-text="terminal"></h2><div class="filters">
        <label class="search"><span data-text="search"></span><input id="search" type="search"></label>
        <label><span data-text="resources"></span><select id="filter"><option value="all" data-text="all"></option><option value="stored" data-text="stored"></option><option value="craftable" data-text="craftable"></option></select></label>
        <label><span data-text="sort"></span><select id="sort"><option value="name" data-text="name"></option><option value="quantity" data-text="quantity"></option><option value="id" data-text="id"></option></select></label></div>
        <p id="item-message" role="status"></p><div class="terminal-layout"><div><ul id="items"></ul>
        <div id="pages"><button id="previous-page">←</button><span id="page-count"></span><button id="next-page">→</button></div>
        <button id="clear" data-text="resetSearch" hidden></button></div><aside><h3 data-text="details"></h3><div id="details"></div></aside></div></section>
        <p id="missing" data-text="invalidRoute" hidden></p><footer id="updated" aria-live="off"></footer>`;
    const find = selector => root.querySelector(selector);
    find('#legacy').href = base.href;
    const tooltip = element('div', '', 'tooltip');
    tooltip.id = 'resource-tooltip'; tooltip.role = 'tooltip'; tooltip.hidden = true;
    root.append(tooltip);
    let hideTimer;
    let language;
    let locale;
    let page = 0;
    let listInput;
    let state;
    let rows = [];
    let allFiltered = [];
    function hideTooltip() { clearTimeout(hideTimer); tooltip.hidden = true; }
    function positionTooltip(x, y) {
        const box = tooltip.getBoundingClientRect();
        tooltip.style.left = `${Math.max(8, Math.min(x + 14, innerWidth - box.width - 8))}px`;
        tooltip.style.top = `${Math.max(8, y + box.height + 24 > innerHeight ? y - box.height - 10 : y + 16)}px`;
    }
    function showTooltip(item, x, y) {
        tooltip.replaceChildren(element('strong', item.itemname), element('code', item.itemid),
            element('span', `${locale.t('quantity')}: ${locale.number(item.quantity)}`),
            element('span', locale.t(item.craftable ? 'craftableYes' : 'craftableNo')));
        tooltip.hidden = false; positionTooltip(x, y);
    }
    function updateLabels() {
        locale = createTranslator(language);
        document.documentElement.lang = language;
        root.querySelectorAll('[data-text]').forEach(node => { node.textContent = locale.t(node.dataset.text); });
        find('#search').placeholder = locale.t('searchHint');
        find('#previous-page').setAttribute('aria-label', locale.t('previousPage'));
        find('#next-page').setAttribute('aria-label', locale.t('nextPage'));
    }
    function renderNetworks() {
        const select = find('#network');
        const current = new Map([...select.options].map(option => [option.value, option]));
        const entries = [{ key: '', label: locale.t('chooseNetwork') }, ...state.grids.map(grid => ({
            key: grid.key, label: `${grid.owner || locale.t('unknownOwner')} · ${grid.key}`
        }))];
        if (state.route.gridKey && !state.grids.some(grid => grid.key === state.route.gridKey)) {
            entries.push({ key: state.route.gridKey, label: state.route.gridKey });
        }
        for (const entry of entries) {
            const option = current.get(entry.key) || document.createElement('option');
            option.value = entry.key; option.textContent = entry.label;
            if (!option.parentNode) select.append(option);
            current.delete(entry.key);
        }
        for (const option of current.values()) option.remove();
        select.value = state.route.gridKey || '';
        const networkMessage = state.gridStatus === 'loading' ? locale.t('loading') : state.gridError ? locale.t(state.gridError)
            : !state.grids.length ? `${locale.t('noNetworks')}. ${locale.t('noNetworksHelp')}` : '';
        find('#network-message').textContent = networkMessage;
        const networks = find('#networks');
        const focused = networks.contains(document.activeElement) ? document.activeElement : null;
        const links = new Map([...networks.children].map(link => [link.dataset.key, link]));
        state.grids.forEach((grid, index) => {
            const link = links.get(grid.key) || element('a', '', 'network');
            link.dataset.key = grid.key;
            link.href = `#/grids/${encodeURIComponent(grid.key)}/items`;
            link.replaceChildren(element('strong', locale.t('gridOwner', { owner: grid.owner || locale.t('unknownOwner') })),
                element('code', grid.key), element('span', locale.t('cpuCount', { count: grid.cpuCount })));
            if (networks.children[index] !== link) networks.insertBefore(link, networks.children[index] || null);
            links.delete(grid.key);
        });
        for (const link of links.values()) link.remove();
        if (focused?.isConnected && document.activeElement !== focused) focused.focus({ preventScroll: true });
    }
    function renderItems() {
        const signature = [state.search, state.preferences.filter, state.preferences.sort, language, state.route.gridKey].join('\0');
        const changed = !listInput || listInput.items !== state.items || listInput.signature !== signature;
        if (changed) {
            if (listInput?.signature !== signature) page = 0;
            const search = state.search.trim().toLocaleLowerCase(language);
            allFiltered = state.items.filter(item => `${item.itemname} ${item.itemid}`.toLocaleLowerCase(language).includes(search))
                .filter(item => state.preferences.filter === 'all' || (state.preferences.filter === 'stored' ? item.quantity > 0 : item.craftable))
                .sort((a, b) => state.preferences.sort === 'quantity' ? b.quantity - a.quantity || a.itemname.localeCompare(b.itemname, language)
                    : (state.preferences.sort === 'id' ? a.itemid.localeCompare(b.itemid, language) : a.itemname.localeCompare(b.itemname, language)));
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
            message = state.itemStatus === 'loading' ? locale.t('loading') : state.itemError ? locale.t(state.itemError)
                : state.itemStatus === 'ready' ? !state.items.length ? locale.t('empty') : !allFiltered.length ? locale.t('noMatches')
                    : locale.t('resourceCount', { count: allFiltered.length }) : '';
            if (state.refreshing && state.itemStatus === 'ready') message += ` · ${locale.t('refreshing')}`;
        }
        find('#item-message').textContent = message;
        find('#clear').hidden = state.itemStatus !== 'ready' || !state.items.length || !!allFiltered.length;
    }
    function renderPage() {
        hideTooltip();
        const list = find('#items');
        const focused = rows.find(row => row.button === document.activeElement)?.item.itemKey;
        page = Math.min(page, Math.max(0, Math.ceil(allFiltered.length / PAGE_SIZE) - 1));
        rows = [];
        const children = allFiltered.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE).map(item => {
            const li = element('li'); const button = element('button', '', 'item'); button.type = 'button';
            button.classList.toggle('selected', item === state.selected);
            button.setAttribute('aria-pressed', String(item === state.selected));
            button.append(element('strong', item.itemname), element('span', locale.number(item.quantity), 'quantity'),
                element('small', item.craftable ? locale.t('craftableYes') : ''));
            button.addEventListener('click', () => { application.select(item); hideTooltip(); });
            button.addEventListener('pointerenter', event => {
                if (event.pointerType === 'touch') return;
                hideTimer = setTimeout(() => showTooltip(item, event.clientX, event.clientY), 350);
            });
            button.addEventListener('pointermove', event => { if (!tooltip.hidden) positionTooltip(event.clientX, event.clientY); });
            button.addEventListener('pointerleave', hideTooltip);
            button.addEventListener('focus', () => {const box = button.getBoundingClientRect(); showTooltip(item, box.left, box.bottom);});
            button.addEventListener('blur', hideTooltip);
            rows.push({ item, button }); li.append(button); return li;
        });
        list.replaceChildren(...children);
        if (focused) rows.find(row => row.item.itemKey === focused)?.button.focus({ preventScroll: true });
        find('#pages').hidden = allFiltered.length <= PAGE_SIZE;
        find('#previous-page').disabled = page === 0;
        find('#next-page').disabled = (page + 1) * PAGE_SIZE >= allFiltered.length;
        find('#page-count').textContent = `${page + 1} / ${Math.max(1, Math.ceil(allFiltered.length / PAGE_SIZE))}`;
    }
    function renderDetails() {
        const details = find('#details');
        const item = state.selected;
        if (!item) {details.replaceChildren(element('p', locale.t('selectItem'))); return;}
        details.replaceChildren(element('h4', item.itemname), element('code', item.itemid),
            element('p', `${locale.t('quantity')}: ${locale.number(item.quantity)}`),
            element('p', locale.t(item.craftable ? 'craftableYes' : 'craftableNo')));
        if (!item.itemKey) details.append(element('p', locale.t('identityUnavailable'), 'hint'));
    }
    function render(next) {
        state = next;
        if (language !== state.preferences.language) {language = state.preferences.language; updateLabels();}
        document.documentElement.dataset.appearance = state.preferences.appearance;
        find('#language').value = language;
        find('#appearance').value = state.preferences.appearance;
        find('#filter').value = state.preferences.filter;
        find('#sort').value = state.preferences.sort;
        find('#auto-refresh').checked = state.preferences.autoRefresh;
        if (find('#search').value !== state.search) find('#search').value = state.search;
        find('#home').hidden = state.route.view !== 'home';
        find('#terminal').hidden = state.route.view !== 'items';
        find('#missing').hidden = state.route.view !== 'missing';
        renderNetworks(); renderItems(); renderDetails();
        find('#updated').textContent = state.updatedAt ? locale.t('updated', { time: locale.time(state.updatedAt) }) : '';
    }
    find('#network').addEventListener('change', event => navigateToGrid(event.target.value));
    find('#search').addEventListener('input', event => application.search(event.target.value));
    for (const name of ['language', 'appearance', 'filter', 'sort']) find(`#${name}`).addEventListener('change', event => application.preference(name, event.target.value));
    find('#auto-refresh').addEventListener('change', event => application.preference('autoRefresh', event.target.checked));
    find('#refresh').addEventListener('click', () => application.refresh());
    find('#clear').addEventListener('click', () => {application.preference('filter', 'all'); application.search(''); find('#search').focus();});
    find('#previous-page').addEventListener('click', () => {page--; renderPage();});
    find('#next-page').addEventListener('click', () => {page++; renderPage();});
    find('#logout').addEventListener('click', async () => {
        find('#logout').disabled = true;
        try {await logout();} catch (error) {find('#network-message').textContent = locale.t(error.status); find('#logout').disabled = false;}
    });
    const keydown = event => {if (event.key === 'Escape') hideTooltip();};
    window.addEventListener('scroll', hideTooltip, true);
    window.addEventListener('resize', hideTooltip);
    window.addEventListener('keydown', keydown);
    const unsubscribe = application.subscribe(render);
    return () => {
        unsubscribe(); hideTooltip();
        window.removeEventListener('scroll', hideTooltip, true);
        window.removeEventListener('resize', hideTooltip);
        window.removeEventListener('keydown', keydown);
        root.replaceChildren();
    };
}
