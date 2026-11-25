import type { PatternProvider, ResourceDescription, ResourceStack } from '../../app/api-types.js';
import type { IconTarget } from '../../app/icons.js';
import type { Translator } from '../../app/i18n.js';
import { indexPatternOutputs, type DuplicateOutput } from '../../app/pattern-duplicates.js';
import { plainMinecraftText } from '../../app/minecraft-text.js';
import type { ReadonlyData, ThemeApplication, ThemeState } from '../../app/theme-api.js';
import { element } from './dom.js';
import { createResourceIcon, paintResourceIcon } from './resource-icon.js';
import type { Tooltips } from './tooltips.js';
import type { TerminalDisplay } from './web-settings.js';

type Provider = ReadonlyData<PatternProvider>;
const pageSize = 16;

function position({ location: p }: Provider) {
    return [p.dimension, p.x + ', ' + p.y + ', ' + p.z, p.side].filter(Boolean).join(' · ');
}

function groupKey(p: Provider) {
    return JSON.stringify([
        p.name,
        p.icon?.itemKey ?? [p.icon?.registryNamespace, p.icon?.registryPath, p.icon?.damage, p.icon?.displayName]
    ]);
}

function resourcesText(stacks: readonly ReadonlyData<ResourceStack>[], locale: Translator) {
    return stacks.map((s) => locale.number(s.quantity) + ' × ' + plainMinecraftText(s.displayName)).join(', ') || '—';
}

/** Read-only physical slots. Page by provider (not group) so a single giant group stays inexpensive. */
export function createPatternView(parent: HTMLElement, app: ThemeApplication, tooltips: Tooltips) {
    const section = element('section', '', 'page-view pattern-view');
    section.hidden = true;
    section.innerHTML = `<div class="terminal-heading"><h1></h1></div>
        <div class="pattern-controls"><label><span></span><input type="search"></label><label><span></span>
        <select><option value="visible"></option><option value="all"></option><option value="notFull"></option><option value="duplicates"></option></select></label></div>
        <p class="pattern-message" role="status"></p><div class="pattern-scroll"></div>
        <div class="pattern-pages"><button type="button"></button><span></span><button type="button"></button></div>`;
    parent.append(section);
    const input = section.querySelector('input')!;
    const filter = section.querySelector('select')!;
    const labels = section.querySelectorAll('.pattern-controls label > span');
    const heading = section.querySelector('h1')!;
    const message = section.querySelector<HTMLElement>('.pattern-message')!;
    const scroll = section.querySelector<HTMLElement>('.pattern-scroll')!;
    const pages = section.querySelector<HTMLElement>('.pattern-pages')!;
    const [prev, next] = pages.querySelectorAll('button');
    const range = pages.querySelector('span')!;
    const icons = app.icons.observe(scroll, paintResourceIcon);
    let state: ThemeState;
    let locale: Translator;
    let display: TerminalDisplay;
    let sorted: Provider[] = [];
    let page = 0;
    let gridKey: string | null = null;
    let lastRows: readonly Provider[] | null = null;
    let lastIcons: ThemeState['patterns']['icons'] = null;
    let lastStatus: string | null = null;
    let lastError: string | null = null;
    let duplicates = new Map<string, DuplicateOutput>();
    const collapsed = new Set<string>();
    const keywords = new WeakMap<Provider, string>();
    function matches(p: Provider, query: string) {
        if (!query) return true;
        let text = keywords.get(p);
        if (text === undefined) {
            const words = new Set([plainMinecraftText(p.name)]);
            for (const slot of p.slots) {
                if (!slot.pattern) continue;
                for (const stacks of [slot.pattern.inputs, slot.pattern.outputs]) {
                    for (const stack of stacks) words.add(plainMinecraftText(stack.displayName));
                }
            }
            text = [...words].join(' ').toLowerCase();
            keywords.set(p, text);
        }
        return text.includes(query);
    }

    function addIcon(host: HTMLElement, resource: ReadonlyData<ResourceDescription> | null, targets: IconTarget[]) {
        if (display === 'names' || !resource?.icon) return false;
        host.append(createResourceIcon());
        targets.push({ element: host, icon: resource.icon });
        return true;
    }

    function duplicateCount(slot: Provider['slots'][number]) {
        let count = 0;
        for (const output of slot.pattern?.outputs ?? []) {
            if (output.itemKey) count = Math.max(count, duplicates.get(output.itemKey)?.count ?? 0);
        }
        return count;
    }

    function duplicateDetails(p: Provider, index: number) {
        const lines: Node[] = [];
        const shown = new Set<string>();
        for (const output of p.slots[index].pattern?.outputs ?? []) {
            const key = output.itemKey;
            if (!key || shown.has(key)) continue;
            const duplicate = duplicates.get(key);
            if (!duplicate) continue;
            shown.add(key);
            lines.push(
                element(
                    'strong',
                    locale.t('patternOtherSources', {
                        name: plainMinecraftText(output.displayName),
                        count: duplicate.count
                    })
                )
            );
            let listed = 0;
            for (const entry of duplicate.locations) {
                if (entry.provider.key === p.key && entry.slot === index) continue;
                listed++;
                lines.push(
                    element(
                        'span',
                        locale.t('patternSourceLocation', {
                            provider: plainMinecraftText(entry.provider.name),
                            location: position(entry.provider),
                            slot: entry.slot + 1
                        })
                    )
                );
            }
            const more = duplicate.count - 1 - listed;
            if (more > 0) lines.push(element('span', locale.t('patternMoreSources', { count: more })));
        }
        return lines;
    }

    function renderProvider(p: Provider, targets: IconTarget[]) {
        const article = element('article', '', 'pattern-provider');
        article.dataset.providerKey = p.key;
        const details = element('div', '', 'pattern-position');
        details.append(element('span', position(p)));
        if (!p.online) details.append(element('strong', locale.t('patternOffline'), 'pattern-offline'));
        if (!p.visible) details.append(element('span', locale.t('patternHidden')));
        article.append(details);
        const cells = element('div', '', 'pattern-slots');
        cells.style.setProperty('--pattern-columns', String(Math.max(1, Math.min(p.rowSize, p.slots.length))));
        p.slots.forEach((slot, index) => {
            const pat = slot.pattern;
            if (!pat) {
                const empty = element('span', '', 'slot-frame pattern-slot');
                empty.setAttribute('aria-hidden', 'true');
                cells.append(empty);
                return;
            }
            const cell = element('button', '', 'slot-frame pattern-slot');
            cell.type = 'button';
            cell.dataset.slot = String(index);
            const kind = locale.t('patternKind_' + pat.kind);
            const output = pat.outputs[0];
            const title = output ? plainMinecraftText(output.displayName) : kind;
            cell.dataset.label = locale.t('patternSlot', { slot: index + 1, name: title });
            const hasIcon = addIcon(cell, output ?? null, targets);
            if (!hasIcon || display !== 'icons') cell.append(element('span', title, 'pattern-slot-name'));
            if (pat.kind === 'invalid') cell.classList.add('pattern-invalid');
            const tip = () => [
                element('strong', title),
                ...(output ? [element('span', kind)] : []),
                ...(pat.kind === 'invalid'
                    ? []
                    : [
                          element('span', locale.t('patternOutputs', { items: resourcesText(pat.outputs, locale) })),
                          element('span', locale.t('patternInputs', { items: resourcesText(pat.inputs, locale) }))
                      ]),
                element('span', position(p)),
                ...duplicateDetails(p, index)
            ];
            tooltips.bind(cell, tip);
            cell.onclick = () => cell.focus(); // Touch browsers need not focus a tapped button.
            cells.append(cell);
        });
        article.append(cells);
        return article;
    }

    // An off-page provider can become a second source without changing this row.
    function updateBadges(article: HTMLElement, provider: Provider) {
        for (const button of article.querySelectorAll<HTMLElement>('button[data-slot]')) {
            const count = duplicateCount(provider.slots[Number(button.dataset.slot)]);
            if (button.dataset.duplicates === String(count)) continue;
            button.dataset.duplicates = String(count);
            let badge = button.querySelector<HTMLElement>('.pattern-duplicate-badge');
            if (count) {
                if (!badge) {
                    badge = element('span', '', 'pattern-duplicate-badge');
                    badge.setAttribute('aria-hidden', 'true');
                    button.append(badge);
                }
                badge.textContent = '×' + locale.number(count);
            } else badge?.remove();
            const label = button.dataset.label!;
            button.setAttribute(
                'aria-label',
                count ? label + '. ' + locale.t('patternDuplicateCount', { count }) : label
            );
        }
    }

    type MountedProvider = { provider: Provider; article: HTMLElement; targets: IconTarget[] };
    const mounted = new Map<string, MountedProvider>();
    const headers = new Map<string, HTMLButtonElement>();

    // Only move a child when its position changes. A live update in another provider
    // must not detach the focused slot, dismiss its tooltip or restart its icon.
    function reconcile(children: HTMLElement[]) {
        const keep = new Set<Element>(children);
        for (const child of Array.from(scroll.children)) if (!keep.has(child)) child.remove();
        let at = scroll.firstElementChild;
        for (const child of children) {
            if (child === at) at = at.nextElementSibling;
            else scroll.insertBefore(child, at);
        }
    }
    function renderRows(rows: Provider[]) {
        const targets: IconTarget[] = [];
        const children: HTMLElement[] = [];
        const usedGroups = new Set<string>();
        const usedProviders = new Set<string>();
        const focus = scroll.contains(document.activeElement) ? (document.activeElement as HTMLElement) : null;
        const focusGroup = focus?.dataset.group;
        const focusProvider = focus?.closest<HTMLElement>('[data-provider-key]')?.dataset.providerKey;
        const focusSlot = focus?.dataset.slot;
        const top = scroll.scrollTop;
        for (const p of rows) {
            const key = groupKey(p);
            if (!usedGroups.has(key)) {
                usedGroups.add(key);
                let toggle = headers.get(key);
                if (!toggle) {
                    toggle = element('button', '', 'pattern-group-toggle');
                    toggle.type = 'button';
                    toggle.dataset.group = key;
                    toggle.append(element('span', plainMinecraftText(p.name)));
                    toggle.onclick = () => {
                        if (collapsed.has(key)) collapsed.delete(key);
                        else collapsed.add(key);
                        draw();
                    };
                    headers.set(key, toggle);
                }
                toggle.setAttribute('aria-expanded', String(!collapsed.has(key)));
                if (display !== 'names' && p.icon?.icon) {
                    if (!toggle.querySelector('.resource-icon')) toggle.prepend(createResourceIcon());
                    targets.push({ element: toggle, icon: p.icon.icon });
                } else toggle.querySelector('.resource-icon')?.remove();
                children.push(toggle);
            }
            if (collapsed.has(key)) continue;
            usedProviders.add(p.key);
            let row = mounted.get(p.key);
            if (row?.provider !== p) {
                const rowTargets: IconTarget[] = [];
                row = { provider: p, article: renderProvider(p, rowTargets), targets: rowTargets };
                mounted.set(p.key, row);
            }
            updateBadges(row.article, p);
            children.push(row.article);
            targets.push(...row.targets);
        }
        for (const key of headers.keys()) if (!usedGroups.has(key)) headers.delete(key);
        for (const key of mounted.keys()) if (!usedProviders.has(key)) mounted.delete(key);
        reconcile(children);
        icons.update(targets, state.patterns.icons);
        if (focus && !focus.isConnected) {
            if (focusGroup) headers.get(focusGroup)?.focus({ preventScroll: true });
            if (focusProvider && focusSlot)
                mounted
                    .get(focusProvider)
                    ?.article.querySelector<HTMLElement>(`[data-slot="${CSS.escape(focusSlot)}"]`)
                    ?.focus({ preventScroll: true });
        }
        scroll.scrollTop = top;
        tooltips.refresh();
    }

    function draw() {
        const query = input.value.trim().toLowerCase();
        const rows = sorted.filter(
            (p) =>
                (filter.value !== 'visible' || p.visible) &&
                (filter.value !== 'notFull' || p.slots.some((s) => !s.pattern)) &&
                (filter.value !== 'duplicates' || p.slots.some((slot) => duplicateCount(slot) > 0)) &&
                matches(p, query)
        );
        const totalPages = Math.max(1, Math.ceil(rows.length / pageSize));
        page = Math.min(page, totalPages - 1);
        section.classList.toggle('pattern-duplicates-only', filter.value === 'duplicates');
        renderRows(rows.slice(page * pageSize, (page + 1) * pageSize));
        pages.hidden = totalPages <= 1;
        prev.disabled = page === 0;
        next.disabled = page === totalPages - 1;
        range.textContent = locale.t('patternRange', {
            first: page * pageSize + 1,
            last: Math.min((page + 1) * pageSize, rows.length),
            total: rows.length
        });
        const model = state.patterns;
        message.textContent =
            model.status === 'error'
                ? locale.common(model.error ?? 'error')
                : model.status !== 'ready' && !rows.length
                  ? locale.common('loading')
                  : !rows.length
                    ? locale.t(sorted.length ? 'patternNoMatch' : 'patternEmpty')
                    : '';
        message.hidden = !message.textContent;
    }
    function resetPage() {
        page = 0;
        scroll.scrollTop = 0;
        draw();
    }
    input.oninput = resetPage;
    filter.onchange = resetPage;
    prev.onclick = () => {
        page--;
        scroll.scrollTop = 0;
        draw();
    };
    next.onclick = () => {
        page++;
        scroll.scrollTop = 0;
        draw();
    };
    return {
        render(nextState: ThemeState, nextLocale: Translator, nextDisplay: TerminalDisplay) {
            const route = nextState.route;
            if (route.view !== 'patterns') {
                if (!section.hidden) {
                    section.hidden = true;
                    icons.update([], null);
                    scroll.replaceChildren();
                    headers.clear();
                    mounted.clear();
                    duplicates.clear();
                    sorted = [];
                    lastRows = null;
                    lastIcons = null;
                    collapsed.clear();
                }
                return;
            }
            const changedGrid = section.hidden || gridKey !== route.gridKey;
            gridKey = route.gridKey;
            section.hidden = false;
            state = nextState;
            const langChanged = locale !== nextLocale;
            const changedRows = changedGrid || lastRows !== state.patterns.providers;
            const repaint =
                changedRows ||
                langChanged ||
                display !== nextDisplay ||
                lastIcons !== state.patterns.icons ||
                lastStatus !== state.patterns.status ||
                lastError !== state.patterns.error;
            if (changedGrid || langChanged || display !== nextDisplay) {
                headers.clear();
                mounted.clear();
            }
            display = nextDisplay;
            section.dataset.display = display;
            locale = nextLocale;
            if (changedGrid) {
                input.value = '';
                filter.value = 'visible';
                page = 0;
                scroll.scrollTop = 0;
                collapsed.clear();
            }
            if (changedGrid || langChanged) {
                heading.textContent = locale.t(
                    state.patternTerminalName === 'pattern_access_terminal'
                        ? 'patternAccessTerminal'
                        : 'patternInterfaceTerminal'
                );
                labels[0].textContent = locale.t('patternSearch');
                labels[1].textContent = locale.t('patternFilter');
                for (const option of filter.options) option.textContent = locale.t('patternFilter_' + option.value);
                prev.textContent = locale.common('previousPage');
                next.textContent = locale.common('nextPage');
            }
            if (changedRows) duplicates = indexPatternOutputs(state.patterns.providers);
            if (changedRows || langChanged) {
                const collator = new Intl.Collator(state.preferences.language);
                sorted = state.patterns.providers
                    .map((p) => ({ p, name: plainMinecraftText(p.name), key: groupKey(p) }))
                    .sort(
                        (a, b) =>
                            collator.compare(a.name, b.name) ||
                            (a.key < b.key ? -1 : a.key > b.key ? 1 : 0) ||
                            a.p.sortValue - b.p.sortValue ||
                            (a.p.key < b.p.key ? -1 : a.p.key > b.p.key ? 1 : 0)
                    )
                    .map(({ p }) => p);
            }
            lastRows = state.patterns.providers;
            lastIcons = state.patterns.icons;
            lastStatus = state.patterns.status;
            lastError = state.patterns.error;
            if (repaint) draw();
        },
        dispose() {
            icons.dispose();
            section.remove();
        }
    };
}
