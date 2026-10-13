import type { ThemeApplication, ThemeState, ThemeLifetime } from '../../app/theme-api.js';
import type { Translator } from '../../app/i18n.js';
import { registryId, selectResources } from '../../app/resource-helpers.js';
import { plainMinecraftText } from '../../app/minecraft-text.js';
import { renderMinecraftText } from './minecraft-text.js';
import { element } from './dom.js';
import { createResourceIcon, paintResourceIcon } from './resource-icon.js';
import { createSlotGrid } from './slot-grid.js';
import { slotQuantity } from './resource-quantity.js';
import { infoCircle } from './icons/hackernoon/info-circle.js';
import { terminalIcons as symbols, craftingHammer } from './icons/pixel/terminal.js';
import type { TerminalDisplay } from './web-settings.js';
import type { Tooltips } from './tooltips.js';
import type { createCraftingView } from './crafting.js';
import type { createMobilePanels } from './mobile-panels.js';

const PAGE_SIZE = 100;

function iconButton(group: 'filter' | 'sort' | 'sortOrder' | 'showItems' | 'showFluids', value: keyof typeof symbols) {
    return `<button type="button" class="tool-button" data-preference="${group}" data-value="${value}">${symbols[value]}</button>`;
}

export function resourceMetadata(
    item: Pick<ThemeState['items'][number], 'registryNamespace' | 'registryPath' | 'damage' | 'componentCount'>,
    tag: 'span' | 'p',
    locale: Translator
) {
    const id = registryId(item);
    const lines: HTMLElement[] = id ? [element('code', id)] : [];
    if (item.damage !== 0) {
        lines.push(element(tag, `${locale.t('damage')}: ${locale.number(item.damage)}`));
    }
    if (item.componentCount !== 0) {
        lines.push(
            element(tag, `${locale.t('componentCount')}: ${locale.number(item.componentCount)}`, 'resource-components')
        );
    }
    return lines;
}

export function createResourceTerminal(
    host: HTMLElement,
    application: ThemeApplication,
    {
        craftingView,
        tooltips,
        mobile,
        lifetime
    }: {
        craftingView: Pick<ReturnType<typeof createCraftingView>, 'open' | 'order'>;
        tooltips: Tooltips;
        mobile: Pick<ReturnType<typeof createMobilePanels>, 'open'>;
        lifetime: ThemeLifetime;
    }
) {
    const root = document.createElement('div');
    root.innerHTML = `
        <div class="terminal-tools" id="terminal-tools" hidden>
            <div role="group" data-label="resources">${iconButton('filter', 'all')}</div>
            <div role="group" data-theme-label="resourceKinds">
                ${iconButton('showItems', 'itemsOn')}${iconButton('showFluids', 'fluidsOn')}
            </div>
            <div role="group" data-label="sort">
                ${iconButton('sort', 'name')}${iconButton('sortOrder', 'ascending')}
            </div>
        </div>
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
        <aside id="resource-panel" hidden>
            <h3>${infoCircle}<span data-text="details"></span></h3>
            <div id="details"></div><div id="order"></div>
        </aside>
`;
    const controls = {
        terminalTools: root.querySelector<HTMLDivElement>('#terminal-tools')!,
        terminal: root.querySelector<HTMLElement>('#terminal')!,
        search: root.querySelector<HTMLInputElement>('#search')!,
        itemMessage: root.querySelector<HTMLParagraphElement>('#item-message')!,
        itemScroll: root.querySelector<HTMLDivElement>('#item-scroll')!,
        items: root.querySelector<HTMLUListElement>('#items')!,
        pages: root.querySelector<HTMLDivElement>('#pages')!,
        previousPage: root.querySelector<HTMLButtonElement>('#previous-page')!,
        pageCount: root.querySelector<HTMLSpanElement>('#page-count')!,
        nextPage: root.querySelector<HTMLButtonElement>('#next-page')!,
        resourcePanel: root.querySelector<HTMLElement>('#resource-panel')!,
        details: root.querySelector<HTMLDivElement>('#details')!,
        order: root.querySelector<HTMLDivElement>('#order')!
    };
    const toolButtons: NodeListOf<
        HTMLButtonElement & {
            dataset: {
                preference: 'filter' | 'sort' | 'sortOrder' | 'showItems' | 'showFluids';
                value: keyof typeof symbols;
            };
        }
    > = root.querySelectorAll('.tool-button');
    const itemIcons = application.icons.observe(controls.itemScroll, paintResourceIcon);
    const slots = createSlotGrid(controls.items, controls.itemScroll, controls.terminal);
    lifetime.onCleanup(() => slots.dispose());
    let itemTooltip:
        { row: ReturnType<typeof createItemRow>; x: number; y: number; pointer: boolean } | null | undefined;
    let updatingRows = false;
    let toolTooltip: HTMLButtonElement | null = null;

    let language: string;
    let locale: Translator;
    let effectiveDisplay: TerminalDisplay = 'names';
    let page = 0;
    let listInput: { items: ThemeState['items']; signature: string } | undefined;
    let state: ThemeState;
    let rows: ReturnType<typeof createItemRow>[] = [];
    let allFiltered: ThemeState['items'] = [];
    function hideTooltip() {
        tooltips.hide();
        itemTooltip = null;
        toolTooltip = null;
    }
    function showTooltip(target: HTMLElement, item: ThemeState['items'][number], x: number, y: number) {
        const name = element('strong');
        name.append(renderMinecraftText(item.displayName));
        tooltips.show(
            target,
            [
                name,
                ...resourceMetadata(item, 'span', locale),
                element('span', `${locale.common('stored')}: ${locale.number(item.quantity)}`),
                element('span', locale.common(item.craftable ? 'craftableYes' : 'craftableNo'))
            ],
            x,
            y
        );
    }

    function showItemTooltip(row: ReturnType<typeof createItemRow>, x: number, y: number, pointer: boolean) {
        hideTooltip();
        toolTooltip = null;
        itemTooltip = { row, x, y, pointer };
        showTooltip(row.button, row.item, x, y);
    }
    function refreshItemTooltip() {
        if (!itemTooltip || !tooltips.isActive(itemTooltip.row.button)) return;
        const { row, x, y, pointer } = itemTooltip;
        if (
            !row.button.isConnected ||
            (pointer ? !row.button.contains(document.elementFromPoint(x, y)) : document.activeElement !== row.button)
        ) {
            hideTooltip();
            return;
        }
        const box = row.button.getBoundingClientRect();
        showTooltip(row.button, row.item, pointer ? x : box.left, pointer ? y : box.bottom);
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
                controls.itemScroll.scrollTop = 0;
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
        controls.itemMessage.textContent = message;
        controls.itemMessage.classList.toggle('api-error', !!state.itemError && !!message);
    }

    const resourceMenu = element('div', '', 'window-frame resource-menu');
    resourceMenu.role = 'menu';
    resourceMenu.hidden = true;
    const menuCraft = element('button');
    menuCraft.type = 'button';
    menuCraft.role = 'menuitem';
    resourceMenu.append(menuCraft);
    host.append(resourceMenu);
    let menuItem: ThemeState['selected'] = null;
    let menuSource: HTMLElement | null = null;
    let menuGrid: string | null = null;
    function closeResourceMenu(restore: boolean) {
        if (resourceMenu.hidden) return;
        resourceMenu.hidden = true;
        if (restore && menuSource?.isConnected) menuSource.focus({ preventScroll: true });
        menuItem = null;
    }
    function showResourceMenu(item: ThemeState['items'][number], source: HTMLElement, x: number, y: number) {
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

    function createItemRow(item: ThemeState['items'][number]) {
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
            mobile.open(controls.resourcePanel, locale.common('details'), button);
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
            if (itemTooltip?.row === row && itemTooltip.pointer && tooltips.isActive(row.button)) {
                itemTooltip.x = event.clientX;
                itemTooltip.y = event.clientY;
                tooltips.position(event.clientX, event.clientY);
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
        const list = controls.items;
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
        controls.pages.hidden = allFiltered.length <= PAGE_SIZE;
        controls.previousPage.disabled = page === 0;
        controls.nextPage.disabled = (page + 1) * PAGE_SIZE >= allFiltered.length;
        controls.pageCount.textContent = `${page + 1} / ${Math.max(1, Math.ceil(allFiltered.length / PAGE_SIZE))}`;
        slots.update(rows.length);
    }
    function renderDetails() {
        const details = controls.details;
        const item = state.selected;
        controls.order.hidden = !item?.craftable || !item.itemKey;
        if (!item) {
            details.replaceChildren(element('p', locale.common('selectItem')));
            return;
        }
        const name = element('h4');
        name.append(renderMinecraftText(item.displayName));
        details.replaceChildren(
            name,
            ...resourceMetadata(item, 'p', locale),
            element('p', `${locale.common('stored')}: ${locale.number(item.quantity)}`),
            element('p', locale.common(item.craftable ? 'craftableYes' : 'craftableNo'))
        );
        if (!item.itemKey) details.append(element('p', locale.common('identityUnavailable'), 'hint'));
        if (item.craftable && item.itemKey) {
            const order = craftingView.order(item, state.crafting, locale);
            if (order.parentNode !== controls.order) controls.order.append(order);
        }
    }

    function renderControls() {
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
                let value: keyof typeof symbols;
                if (button.dataset.preference === 'showItems') value = enabled ? 'itemsOn' : 'itemsOff';
                else value = enabled ? 'fluidsOn' : 'fluidsOff';
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
        if (toolTooltip && tooltips.isActive(toolTooltip)) showToolTooltip(toolTooltip);
    }
    const search = controls.search;
    search.addEventListener('input', () => {
        hideTooltip();
        application.search(search.value);
    });
    const showSearchHelp = () => {
        hideTooltip();
        toolTooltip = null;
        itemTooltip = null;
        const box = search.getBoundingClientRect();
        tooltips.show(
            search,
            locale
                .common('searchHelp')
                .split('\n')
                .map((line) => element('span', line)),
            box.left,
            box.bottom
        );
    };
    search.addEventListener('pointerenter', showSearchHelp);
    search.addEventListener('focus', showSearchHelp);
    search.addEventListener('pointerleave', hideTooltip);
    search.addEventListener('blur', hideTooltip);
    function showToolTooltip(button: HTMLButtonElement) {
        hideTooltip();
        itemTooltip = null;
        toolTooltip = button;
        const text = [button.getAttribute('aria-label'), button.getAttribute('aria-description')]
            .filter(Boolean)
            .join(' · ');
        const box = button.getBoundingClientRect();
        tooltips.show(button, [document.createTextNode(text)], box.right, box.top);
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
    controls.previousPage.addEventListener('click', () => {
        page--;
        renderPage();
        controls.itemScroll.scrollTop = 0;
    });
    controls.nextPage.addEventListener('click', () => {
        page++;
        renderPage();
        controls.itemScroll.scrollTop = 0;
    });

    window.addEventListener(
        'keydown',
        (event) => {
            if (event.key === 'Escape') {
                hideTooltip();
                closeResourceMenu(true);
            }
        },
        { signal: lifetime.signal }
    );
    lifetime.onCleanup(() => {
        resourceMenu.remove();
        hideTooltip();
    });
    return {
        element: controls.terminal,
        heading: root.querySelector<HTMLElement>('#terminal > .terminal-heading')!,
        tools: controls.terminalTools,
        panel: controls.resourcePanel,
        focusSearch: () => search.focus(),
        render(next: ThemeState, translator: Translator, display: TerminalDisplay) {
            state = next;
            locale = translator;
            language = state.preferences.language;
            const changed = effectiveDisplay !== display;
            effectiveDisplay = display;
            controls.terminal.hidden = state.route.view !== 'items';
            controls.terminalTools.hidden = state.route.view !== 'items';
            controls.resourcePanel.hidden = state.route.view !== 'items';
            controls.items.dataset.display = display;
            controls.items.classList.toggle('resource-icons-enabled', display !== 'names');
            search.placeholder = locale.common('searchHint');
            search.setAttribute('aria-description', locale.common('searchHelp'));
            controls.previousPage.setAttribute('aria-label', locale.common('previousPage'));
            controls.nextPage.setAttribute('aria-label', locale.common('nextPage'));
            if (changed) {
                renderPage();
                hideTooltip();
            }
            renderControls();
            if (search.value !== state.search) search.value = state.search;
            renderItems();
            renderDetails();
            if (state.route.view !== 'items' || state.itemStatus === 'error' || menuGrid !== state.route.gridKey)
                closeResourceMenu(false);
            slots.update(rows.length);
        },
        dispose() {
            itemIcons.dispose();
            slots.dispose();
            resourceMenu.remove();
            hideTooltip();
        }
    };
}
