import type { ThemeState, ThemeApplication, ThemeLifetime, ThemeSettings } from '../../app/theme-api.js';
import type { CpuResource } from '../../app/api-types.js';
import type { Translator } from '../../app/i18n.js';
import { element } from './dom.js';
import { registryId } from '../../app/resource-helpers.js';
import { plainMinecraftText } from '../../app/minecraft-text.js';
import { renderMinecraftText } from './minecraft-text.js';
import { createResourceIcon, paintResourceIcon } from './resource-icon.js';
import { createSlotGrid } from './slot-grid.js';
import { slotQuantity } from './resource-quantity.js';
import { terminalIcons, craftingHammer, craftingQueue, craftingPriorityIcon } from './icons/pixel/terminal.js';
import type { CpuTooltips } from './cpu-tooltips.js';

const RESOURCE_SORTS = ['name', 'active', 'pending', 'stored', 'shareInCraftingTime'] as const;
type ResourceSort = (typeof RESOURCE_SORTS)[number];

export function createCpuResources(
    application: ThemeApplication,
    tips: CpuTooltips,
    themeLifetime: ThemeLifetime,
    settings: ThemeSettings
) {
    const terminal = element('section', '', 'cpu-terminal');
    const header = element('div', '', 'terminal-heading');
    const heading = element('h2');
    const searchLabel = element('label', '', 'search');
    const searchName = element('span', '', 'sr-only');
    const search = element('input');
    search.type = 'search';
    searchLabel.append(searchName, search);
    header.append(heading, searchLabel);
    const body = element('div', '', 'terminal-body');
    const scroll = element('div', '', 'terminal-scroll');
    scroll.role = 'region';
    scroll.tabIndex = 0;
    const grid = element('ul', '', 'resource-grid cpu-items');
    const icons = application.icons.observe(scroll, paintResourceIcon);
    const slots = createSlotGrid(grid, scroll, terminal);
    themeLifetime.onCleanup(slots.dispose);
    const empty = element('p', '', 'cpu-empty');
    scroll.append(grid);
    body.append(empty, scroll);
    terminal.append(header, body);
    const tools = element('div', '', 'cpu-tools terminal-tools');
    const filterGroup = element('div');
    filterGroup.role = 'group';
    const sortGroup = element('div');
    sortGroup.role = 'group';
    tools.append(filterGroup, sortGroup);
    const itemRows: Map<string, ReturnType<typeof createResourceRow>> = new Map();
    let hideStored = settings.get('cpuResourceHideStored') === true;
    let activeFirst = settings.get('cpuResourceActiveFirst') === true;
    const savedSort = settings.get('cpuResourceSort');
    let sort: ResourceSort = RESOURCE_SORTS.find((value) => value === savedSort) ?? 'name';

    let locale: Translator;

    let state: ThemeState['cpus'];
    let selectedRoute = '';
    let updatingRows = false;
    let order: ReturnType<typeof createResourceRow>[] = [];
    let snapshotRevision = -1;
    let unkeyedRevision = -1;
    let lastItems: readonly CpuResource[] | null = null;
    let lastIcons: ThemeState['cpus']['icons'];
    let tracked = false;
    const effectiveSort = (): ResourceSort => (!tracked && sort === 'shareInCraftingTime' ? 'name' : sort);
    let presentation = '';
    let headingName = '';
    let orderAfter = 0;
    let orderTimer: ReturnType<typeof setTimeout> | undefined;

    function queueOrder() {
        if (orderTimer || !selectedRoute || !state.stream) return;
        orderTimer = setTimeout(
            () => {
                orderTimer = undefined;
                if (document.hidden) {
                    orderAfter = Date.now() + 5000;
                    queueOrder();
                } else renderResources(true);
            },
            Math.max(0, orderAfter - Date.now())
        );
    }
    themeLifetime.onCleanup(() => clearTimeout(orderTimer));

    function createResourceRow(item: CpuResource) {
        const li = element('li');
        const button = element('button', '', 'item cpu-item slot-frame');
        button.type = 'button';
        const name = element('strong');
        const amounts = element('span', '', 'cpu-item-amounts');
        const counts = ['cpuActive', 'cpuPending', 'cpuStored'].map((key) => {
            const amount = element('span', '', 'cpu-amount');
            const label = element('span');
            const quantity = element('span', '', 'quantity');
            amount.append(label, quantity);
            amounts.append(amount);
            return { key, amount, label, quantity };
        });
        const share = element('span', '', 'cpu-amount');
        const shareLabel = element('span');
        const shareValue = element('span', '', 'quantity');
        share.append(shareLabel, shareValue);
        amounts.append(share);
        button.append(createResourceIcon(), name, amounts);
        li.append(button);
        const row = {
            item,
            li,
            button,
            name,
            counts,
            share,
            shareLabel,
            shareValue,
            painted: null as CpuResource | null,
            nameText: plainMinecraftText(item.displayName),
            staticLabel: '',
            shareText: '',
            present: true,
            matches: true
        };
        tips.bindResource(row, () => updatingRows);
        return row;
    }

    const controls: Array<{
        button: HTMLButtonElement;
        value: ResourceSort | 'hideStored' | 'activeFirst';
        key: string;
    }> = [];

    function addControl(
        value: ResourceSort | 'hideStored' | 'activeFirst',
        key: string,
        group: 'filter' | 'sort',
        icon: string
    ) {
        const button = element('button', '', 'tool-button');
        button.type = 'button';
        button.innerHTML = icon;
        button.addEventListener('click', () => {
            if (value === 'hideStored') {
                hideStored = !hideStored;
                settings.set('cpuResourceHideStored', hideStored);
            } else if (value === 'activeFirst') {
                activeFirst = !activeFirst;
                settings.set('cpuResourceActiveFirst', activeFirst);
            } else {
                sort = value;
                settings.set('cpuResourceSort', sort);
            }
            scroll.scrollTop = 0;
            tips.hide();
            renderResources(true);
        });
        tips.bindTool(button);
        (group === 'filter' ? filterGroup : sortGroup).append(button);
        controls.push({ button, value, key });
    }
    addControl('hideStored', 'cpuHideStored', 'filter', terminalIcons.stored);
    addControl('activeFirst', 'cpuActiveFirst', 'sort', craftingPriorityIcon);
    addControl('name', 'name', 'sort', terminalIcons.name);
    addControl('active', 'cpuSortActive', 'sort', craftingHammer);
    addControl('pending', 'cpuSortPending', 'sort', craftingQueue);
    addControl('stored', 'cpuSortStored', 'sort', terminalIcons.quantity);
    addControl('shareInCraftingTime', 'cpuProcessingShare', 'sort', '<span aria-hidden="true">%</span>');

    function craftingPriority(item: CpuResource) {
        return item.active > 0 ? 0 : item.pending > 0 ? 1 : 2;
    }

    function renderControls(hasTracking: boolean) {
        filterGroup.setAttribute('aria-label', locale.common('cpuShow'));
        sortGroup.setAttribute('aria-label', locale.common('sort'));
        for (const control of controls) {
            control.button.hidden = control.value === 'shareInCraftingTime' && !hasTracking;
            if (control.button.hidden) tips.hideTool(control.button);
            control.button.setAttribute(
                'aria-label',
                control.value === 'hideStored' || control.value === 'activeFirst'
                    ? locale.common(control.key)
                    : `${locale.common('sort')}: ${locale.common(control.key)}`
            );
            let pressed = control.value === effectiveSort();
            if (control.value === 'hideStored') pressed = hideStored;
            else if (control.value === 'activeFirst') pressed = activeFirst;
            control.button.setAttribute('aria-pressed', String(pressed));
        }
    }

    function text(target: HTMLElement, value: string) {
        if (target.textContent !== value) target.textContent = value;
    }

    function paintTiming(row: ReturnType<typeof createResourceRow>) {
        const share = tracked ? `${locale.number(Math.round(row.item.shareInCraftingTime * 1000) / 10)}%` : '';
        if (row.shareText !== share) {
            row.shareText = share;
            text(row.shareValue, share);
        }
        const label = row.staticLabel + (tracked ? ` · ${locale.common('cpuProcessingShare')}: ${share}` : '');
        if (row.button.getAttribute('aria-label') !== label) row.button.setAttribute('aria-label', label);
    }

    function paintRow(row: ReturnType<typeof createResourceRow>, force: boolean) {
        const item = row.item;
        const previous = row.painted;
        const renamed = !previous || previous.displayName !== item.displayName;
        if (renamed) {
            row.nameText = plainMinecraftText(item.displayName);
            row.name.replaceChildren(renderMinecraftText(item.displayName));
        }
        const countsChanged =
            !previous ||
            previous.active !== item.active ||
            previous.pending !== item.pending ||
            previous.stored !== item.stored;
        if (countsChanged)
            row.button.dataset.crafting = item.active > 0 ? 'active' : item.pending > 0 ? 'pending' : 'stored';
        if (force || countsChanged) {
            const quantities = [item.active, item.pending, item.stored];
            row.counts.forEach((count, i) => {
                count.amount.hidden = quantities[i] <= 0;
                text(count.label, locale.common(count.key));
                text(count.quantity, slotQuantity(quantities[i], locale));
            });
        }
        if (force || countsChanged || renamed) {
            const quantities = [item.active, item.pending, item.stored];
            row.staticLabel = [
                row.nameText,
                ...row.counts.flatMap((count, i) =>
                    quantities[i] > 0 ? [`${locale.common(count.key)}: ${locale.number(quantities[i])}`] : []
                )
            ].join(' · ');
        }
        if (force || !previous) {
            row.share.hidden = !tracked;
            text(row.shareLabel, locale.common('cpuTimeShare'));
        }
        const disabled = !row.present || !row.matches;
        if (row.button.disabled !== disabled) row.button.disabled = disabled;
        paintTiming(row);
        row.painted = item;
    }

    function renderResources(reorder = false, force = false, geometryChanged = false) {
        if (!state.stream) {
            clearTimeout(orderTimer);
            orderTimer = undefined;
        }
        const incoming = state.detail?.isBusy && !state.error ? state.detail.items : null;
        const hasTracking = !!state.detail?.hasTrackingInfo && !state.error;
        if (tracked !== hasTracking) {
            tracked = hasTracking;
            force = true;
            geometryChanged = true;
            grid.classList.toggle('cpu-items-tracked', tracked);
        }
        const ordering = effectiveSort();
        if (force || reorder) renderControls(tracked);
        const changed = incoming !== lastItems || unkeyedRevision !== state.unkeyedRevision;
        if (!changed && !force && !reorder && lastIcons === state.icons) {
            if (geometryChanged) slots.update(order.length);
            return;
        }
        const terms = search.value.trim().toLocaleLowerCase(document.documentElement.lang).split(/\s+/);
        const language = document.documentElement.lang;
        const matches = (row: ReturnType<typeof createResourceRow>) => {
            const item = row.item;
            const value = `${row.nameText} ${registryId(item)}`.toLocaleLowerCase(language);
            const mod = (item.registryNamespace ?? '').toLocaleLowerCase(language);
            return (
                (!hideStored || item.active > 0 || item.pending > 0) &&
                terms.every((term) => (term.startsWith('@') ? mod.includes(term.slice(1)) : value.includes(term)))
            );
        };
        const replaceAnonymous = unkeyedRevision !== state.unkeyedRevision;
        const anonymousPositions = replaceAnonymous ? new Map(order.map((row, index) => [row, index])) : null;
        const current = new Set<string>();
        let anonymous = 0;
        let iconsChanged = lastIcons !== state.icons;
        let membershipChanged = false;
        updatingRows = true;
        for (const item of incoming ?? []) {
            // Anonymous keys name positions in this replacement list, never resource identities.
            const key = item.itemKey ? `key:${item.itemKey}` : `anonymous:${anonymous++}`;
            current.add(key);
            let row = itemRows.get(key);
            if (row && !item.itemKey && replaceAnonymous) {
                const replacement = createResourceRow(item);
                const index = anonymousPositions?.get(row);
                if (index !== undefined) {
                    order[index] = replacement;
                    row.li.replaceWith(replacement.li);
                }
                row = replacement;
                itemRows.set(key, row);
                iconsChanged = true;
            }
            if (!row) {
                row = createResourceRow(item);
                itemRows.set(key, row);
                iconsChanged = true;
            }
            if (row.item.icon !== item.icon) iconsChanged = true;
            const filterChanged =
                force ||
                reorder ||
                !row.painted ||
                !row.present ||
                row.item.displayName !== item.displayName ||
                row.item.registryNamespace !== item.registryNamespace ||
                row.item.registryPath !== item.registryPath ||
                (hideStored && (row.item.active !== item.active || row.item.pending !== item.pending));
            row.item = item;
            row.present = true;
            if (!row.painted || row.painted.displayName !== item.displayName)
                row.nameText = plainMinecraftText(item.displayName);
            if (filterChanged) row.matches = matches(row);
            paintRow(row, force);
            if (!reorder && row.matches && !row.li.parentNode) {
                grid.insertBefore(row.li, grid.children[order.length] ?? null);
                order.push(row);
                membershipChanged = true;
            }
        }
        for (const [key, row] of itemRows) {
            if (current.has(key) || !row.present) continue;
            row.present = false;
            row.matches = false;
            row.item = {
                ...row.item,
                active: 0,
                pending: 0,
                stored: 0,
                crafting: false,
                craftedTotal: 0,
                timeSpentCrafting: 0,
                shareInCraftingTime: 0,
                shareInCraftingTimeCombined: 0,
                craftsPerSec: 0
            };
            paintRow(row, force);
        }
        if (reorder) {
            clearTimeout(orderTimer);
            orderTimer = undefined;
            orderAfter = Date.now() + 5000;
            const focused = order.find((row) => row.button === document.activeElement);
            const next = [...itemRows.values()]
                .filter((row) => row.present && row.matches)
                .sort(
                    (a, b) =>
                        (activeFirst ? craftingPriority(a.item) - craftingPriority(b.item) : 0) ||
                        (ordering === 'name' ? 0 : b.item[ordering] - a.item[ordering]) ||
                        a.nameText.localeCompare(b.nameText, language)
                );
            const visible = new Set(next);
            for (const row of order) if (!visible.has(row)) row.li.remove();
            for (const [index, row] of next.entries())
                if (grid.children[index] !== row.li) grid.insertBefore(row.li, grid.children[index] ?? null);
            membershipChanged ||= order.length !== next.length || order.some((row) => !visible.has(row));
            order = next;
            for (const [key, row] of itemRows) if (!row.present) itemRows.delete(key);
            if (focused?.button.isConnected && !focused.button.disabled && document.activeElement !== focused.button)
                focused.button.focus({ preventScroll: true });
        } else if (changed) queueOrder();
        lastItems = incoming;
        unkeyedRevision = state.unkeyedRevision;
        lastIcons = state.icons;
        if (membershipChanged || geometryChanged) slots.update(order.length);
        if (iconsChanged || membershipChanged)
            icons.update(
                order.map((row) => ({ element: row.button, icon: row.item.icon })),
                state.icons
            );
        updatingRows = false;
        text(empty, state.detail?.isBusy && !state.error && !incoming?.length ? locale.common('cpuNoResources') : '');
        empty.hidden = !empty.textContent;
        tips.refreshResource();
    }

    function renderTiming() {
        if (!selectedRoute || !state.detail?.items || state.error) return;
        let anonymous = 0;
        for (const item of state.detail.items) {
            const row = itemRows.get(item.itemKey ? `key:${item.itemKey}` : `anonymous:${anonymous++}`);
            if (!row?.present) continue;
            row.item = item;
            if (row.li.parentNode) paintTiming(row);
        }
        // This timer owns ordering; the clock callback itself never moves or creates a row.
        if (effectiveSort() === 'shareInCraftingTime') queueOrder();
        lastItems = state.detail.items;
        tips.refreshResource();
    }
    search.addEventListener('input', () => {
        scroll.scrollTop = 0;
        tips.hide();
        renderResources(true);
    });
    scroll.addEventListener('scroll', tips.hide);

    return {
        element: terminal,
        tools,
        render(route: ThemeState['route'], next: ThemeState['cpus'], translator: Translator) {
            const languageChanged = locale !== translator;
            state = next;
            locale = translator;
            const selected = route.view === 'cpus' && !!route.cpuKey;
            terminal.hidden = !selected;
            tools.hidden = !selected;
            const routeKey = selected ? `${route.gridKey}/${route.cpuKey}` : '';
            if (routeKey !== selectedRoute) {
                selectedRoute = routeKey;
                tips.hide();
                for (const row of itemRows.values()) row.li.remove();
                itemRows.clear();
                order = [];
                lastItems = null;
                clearTimeout(orderTimer);
                orderTimer = undefined;
                search.value = '';
                scroll.scrollTop = 0;
            }

            if (!selected) {
                icons.update([], null);
                return;
            }
            const t = locale.common;
            const cpu = state.error ? undefined : state.cpus.find((entry) => entry.key === route.cpuKey);
            const nextName = cpu?.name || route.cpuKey || '';
            if (headingName !== nextName) {
                headingName = nextName;
                heading.replaceChildren(renderMinecraftText(nextName));
                heading.title = plainMinecraftText(nextName);
            }
            if (languageChanged || !search.placeholder) {
                text(searchName, t('searchCpuResources'));
                search.placeholder = t('searchHint');
                search.setAttribute('aria-description', t('searchHelp'));
                scroll.setAttribute('aria-label', t('cpuResources'));
                grid.setAttribute('aria-label', t('cpuResources'));
            }

            const appearance = JSON.stringify([
                settings.get('font'),
                settings.get('resourceColumns'),
                settings.get('terminalDisplay'),
                application.state.iconPack.available
            ]);
            const geometryChanged = presentation !== appearance || languageChanged;
            presentation = appearance;
            const snapshot = snapshotRevision !== state.detailSnapshotRevision;
            snapshotRevision = state.detailSnapshotRevision;
            renderResources(
                snapshot || languageChanged || !state.detail || !!state.error,
                languageChanged,
                geometryChanged || snapshot
            );
        },
        timing: renderTiming,
        dispose() {
            clearTimeout(orderTimer);
            icons.dispose();
            slots.dispose();
            terminal.remove();
            tools.remove();
        }
    };
}
