import { createWebSettings, type TerminalDisplay } from './web-settings.js';
import { createNotices } from './notices.js';
import { createHeader } from './header.js';
import { element } from './dom.js';
import { translations } from './translations.js';
import { createMobilePanels, type MobilePane } from './mobile-panels.js';
import { filterIcon } from './icons/hackernoon/filter.js';
import { networkIcon } from './icons/hackernoon/chart-network.js';
import { cpuChipIcon } from './icons/pixel/cpu-controls.js';
import { registryId, selectResources } from '../../app/resource-helpers.js';
import type { ThemeContext } from '../../app/theme-api.js';
import type { ThemeState as TerminalState, ThemeApplication as Terminal } from '../../app/theme-api.js';
import type { Translator as Locale } from '../../app/i18n.js';

import { plainMinecraftText } from '../../app/minecraft-text.js';
import { renderMinecraftText } from './minecraft-text.js';
import { createCraftingView } from './crafting.js';
import { createHomeView } from './home.js';
import { createCpuView } from './cpus.js';
import { createHistoryView } from './history.js';
import { createSettingsView } from './settings.js';
import { createAboutView } from './about.js';
import { slotQuantity } from './resource-quantity.js';
import { createSlotGrid } from './slot-grid.js';
import { infoCircle } from './icons/hackernoon/info-circle.js';
import { terminalIcons as symbols, craftingHammer } from './icons/pixel/terminal.js';
import { createResourceIcon, paintResourceIcon } from './resource-icon.js';

const PAGE_SIZE = 100;

function iconButton(group: 'filter' | 'sort' | 'sortOrder' | 'showItems' | 'showFluids', value: keyof typeof symbols) {
    return `<button type="button" class="tool-button" data-preference="${group}" data-value="${value}">${symbols[value]}</button>`;
}

/* Theme renderer. Server operations and preference ownership are supplied by the application. */

export function mount(root: HTMLElement, application: Terminal, context: ThemeContext) {
    const { modVersion, updateNotice, settings, i18n, lifetime } = context;
    i18n.register(translations);
    let effectiveDisplay: TerminalDisplay = 'names';
    root.innerHTML = `
        <div class="workspace" id="workspace">
            <div class="terminal-tools" id="terminal-tools" hidden>
                <div role="group" data-label="resources">${iconButton('filter', 'all')}</div>
                <div role="group" data-theme-label="resourceKinds">
                    ${iconButton('showItems', 'itemsOn')}${iconButton('showFluids', 'fluidsOn')}
                </div>
                <div role="group" data-label="sort">
                    ${iconButton('sort', 'name')}${iconButton('sortOrder', 'ascending')}
                </div>
            </div>
            <div class="window-frame" id="window">
                <div id="network-message" role="status"></div>
                <section id="server-settings" hidden><h2 data-text="serverSettings"></h2></section>
                <section id="web-settings" hidden></section>
                <section id="terminal" hidden>
                    <div class="terminal-heading">
                        <h2 data-text="terminal"></h2>
                        <label class="search">
                            <span class="sr-only" data-text="search"></span><input id="search" type="search">
                        </label>
                    </div>
                    <div class="terminal-body">
                        <p id="item-message" role="status"></p>
                        <div id="item-scroll" role="region" data-label="resources" tabindex="0"><ul id="items"></ul></div>
                        <div id="pages">
                            <button id="previous-page">←</button><span id="page-count"></span><button id="next-page">→</button>
                        </div>
                    </div>
                </section>
                <p id="missing" data-text="invalidRoute" hidden></p>
            </div>
            <aside id="resource-panel" hidden>
                <h3>${infoCircle}<span data-text="details"></span></h3>
                <div id="details"></div><div id="order"></div>
            </aside>
        </div>`;
    /* IDs and element types belong to the static markup created above. */
    type ViewElements = {
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
    };

    const find = <Selector extends keyof ViewElements>(selector: Selector): ViewElements[Selector] =>
        root.querySelector(selector) as ViewElements[Selector];
    const toolButtons: NodeListOf<
        HTMLButtonElement & {
            dataset: {
                preference: 'filter' | 'sort' | 'sortOrder' | 'showItems' | 'showFluids';
                value: keyof typeof symbols;
            };
        }
    > = root.querySelectorAll('.tool-button');
    const header = createHeader(application, context, { bind: bindTooltip, hide: hideTooltip });
    root.prepend(header.element);
    const notices = createNotices(
        context,
        {
            enableIcons: () => webSettings.selectDisplay('both'),
            focusSearch: () => find('#search').focus(),
            focusRefresh: header.focusRefresh,
            refresh: renderDisplay
        },
        { bind: bindTooltip, hide: hideTooltip }
    );
    header.element.after(notices.element);
    const webSettings = createWebSettings(find('#web-settings'), find('#workspace'), application, context, () => {
        notices.acknowledgeIcons();
        renderDisplay();
    });
    const mobile = createMobilePanels(root, find('#workspace'), header.navigation, hideTooltip);
    lifetime.onCleanup(() => mobile.dispose());
    const homeView = createHomeView(find('#workspace'), application, mobile.beforeDialog);
    lifetime.onCleanup(() => homeView.dispose());
    const craftingView = createCraftingView(
        find('#window'),
        application,
        find('#workspace'),
        {
            bind: bindTooltip,
            hide: hideTooltip,
            metadata: (resource) => resourceMetadata(resource, 'span')
        },
        mobile.beforeDialog,
        lifetime
    );
    lifetime.onCleanup(() => craftingView.dispose());
    const itemIcons = application.icons.observe(find('#item-scroll'), paintResourceIcon);
    const slots = createSlotGrid(find('#items'), find('#item-scroll'), find('#terminal'));
    lifetime.onCleanup(() => slots.dispose());
    const cpuView = createCpuView(find('#window'), application, { workspace: find('#workspace'), settings, lifetime });
    lifetime.onCleanup(() => cpuView.dispose());
    const historyView = createHistoryView(find('#window'), application, bindTooltip);
    lifetime.onCleanup(() => historyView.dispose());
    const settingsView = createSettingsView(find('#window'), application);
    const aboutView = createAboutView(find('#window'), modVersion);
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
        find('#search').placeholder = locale.common('searchHint');
        find('#search').setAttribute('aria-description', locale.common('searchHelp'));
        find('#previous-page').setAttribute('aria-label', locale.common('previousPage'));
        find('#next-page').setAttribute('aria-label', locale.common('nextPage'));
    }
    function renderNetworks() {
        let networkMessage = '';
        if (state.gridStatus === 'loading') networkMessage = locale.common('loading');
        else if (state.gridError) networkMessage = locale.common(state.gridError);
        else if (!state.grids.length)
            networkMessage = `${locale.common('noNetworks')}. ${locale.common('noNetworksHelp')}`;
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
            allFiltered = selectResources(state.items, {
                search: state.search,
                language,
                filter: state.preferences.filter,
                showItems: state.preferences.showItems,
                showFluids: state.preferences.showFluids,
                sort: state.preferences.sort,
                sortOrder: state.preferences.sortOrder
            });
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
            if (state.itemStatus === 'loading') message = locale.common('loading');
            else if (state.itemError) message = locale.common(state.itemError);
            else if (state.itemStatus === 'ready' && !state.items.length) message = locale.common('empty');
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
    window.addEventListener('pointerdown', outsideResourceMenu, { signal: lifetime.signal });

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
            mobile.open(find('#resource-panel'), locale.common('details'), button);
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
        const nextDisplay = webSettings.render(state, locale);
        const changed = effectiveDisplay !== nextDisplay;
        effectiveDisplay = nextDisplay;
        find('#items').dataset.display = effectiveDisplay;
        find('#workspace').dataset.resourceDisplay = effectiveDisplay;
        find('#items').classList.toggle('resource-icons-enabled', effectiveDisplay !== 'names');
        find('#workspace').classList.toggle('cpu-icons-enabled', effectiveDisplay !== 'names');
        find('#workspace').classList.toggle('product-icons-enabled', effectiveDisplay !== 'names');
        notices.render(state, locale, effectiveDisplay === 'names');
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
        header.render(state, locale);
        renderDisplay();
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
        if (find('#search').value !== state.search) find('#search').value = state.search;
        find('#window').hidden = state.route.view === 'home';
        find('#workspace').classList.toggle('with-home', state.route.view === 'home');
        homeView.render(state, locale);
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
        historyView.render(state, locale);
        settingsView.render(state, locale);
        aboutView.render(state.route, locale);
        slots.update(rows.length);
        const panes: MobilePane[] = [];
        let mobileHeading: HTMLElement | null = null;
        if (state.route.view === 'items') {
            mobileHeading = root.querySelector('#terminal > .terminal-heading');
            panes.push({ content: find('#terminal-tools'), title: locale.t('mobileFilters'), icon: filterIcon });
            if (state.selected)
                panes.push({ content: find('#resource-panel'), title: locale.common('details'), icon: infoCircle });
        } else if (state.route.view === 'plan') {
            mobileHeading = root.querySelector('.crafting-plan > .terminal-heading');
            panes.push({
                content: root.querySelector('.plan-tools')!,
                title: locale.t('mobileFilters'),
                icon: filterIcon
            });
            panes.push({
                content: root.querySelector('#plan-panel')!,
                title: locale.common('cpus'),
                icon: cpuChipIcon
            });
        } else if (state.route.view === 'cpus' && state.route.cpuKey) {
            mobileHeading = root.querySelector('.cpu-terminal > .terminal-heading');
            panes.push({
                content: root.querySelector('.cpu-tools')!,
                title: locale.t('mobileFilters'),
                icon: filterIcon
            });
            panes.push({ content: root.querySelector('#cpu-panel')!, title: locale.common('cpus'), icon: cpuChipIcon });
        } else if (state.route.view === 'home') {
            mobileHeading = root.querySelector('#home > .terminal-heading');
            panes.push({
                content: root.querySelector('#home-network-panel')!,
                title: locale.common('network'),
                icon: networkIcon
            });
        }
        mobile.render(
            JSON.stringify(state.route),
            panes,
            {
                navigation: locale.t('navigation'),
                close: locale.t('closePanel')
            },
            mobileHeading
        );
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
        let pointerInside = false;
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
            if (event.pointerType === 'touch' || pointerInside) return;
            // Updating the icon can emit another enter without the pointer leaving the button.
            pointerInside = true;
            show();
        });
        button.addEventListener('pointerleave', () => {
            pointerInside = false;
            hideTooltip();
        });
        button.addEventListener('focus', show);
        button.addEventListener('blur', hideTooltip);
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

    const keydown = (event: KeyboardEvent) => {
        if (event.key === 'Escape') {
            hideTooltip();
            closeResourceMenu(true);
        }
    };
    window.addEventListener('scroll', hideTooltip, { capture: true, signal: lifetime.signal });
    window.addEventListener('resize', hideTooltip, { signal: lifetime.signal });
    window.addEventListener('keydown', keydown, { signal: lifetime.signal });
    lifetime.onCleanup(() => {
        resourceMenu.remove();
        hideTooltip();
    });
    application.subscribe(render);
    updateNotice.subscribe(() => render(state));
}
