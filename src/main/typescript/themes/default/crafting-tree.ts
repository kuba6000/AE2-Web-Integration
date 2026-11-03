import { craftingTreeContent } from './crafting-tree-content.js';
import { layoutCraftingTree } from './crafting-tree-layout.js';
import type { CraftingTooltipPresenter } from './crafting.js';
import type { TerminalDisplay } from './web-settings.js';
import { craftingHammer, terminalIcons } from './icons/pixel/terminal.js';
import { plainMinecraftText } from '../../app/minecraft-text.js';
import { element } from './dom.js';
import { createResourceIcon, paintResourceIcon } from './resource-icon.js';
import { renderMinecraftText } from './minecraft-text.js';
import type { ThemeApplication, ThemeState } from '../../app/theme-api.js';
import type { Translator } from '../../app/i18n.js';
import { treeConnectionOrder } from './crafting-tree-paint.js';

/** Pan and zoom diagram with an ordinary button outline on narrow screens. */
export function createCraftingTreeView(application: ThemeApplication, tooltip: CraftingTooltipPresenter) {
    const root = element('section', '', 'plan-tree');
    const bar = element('div', '', 'tree-controls');
    const out = element('button', '−');
    const reset = element('button', '100%');
    const zoomIn = element('button', '+');
    const fit = element('button', '⊡');
    const retry = element('button');
    const expandAll = element('button');
    const collapseAll = element('button');
    const focusMissing = element('button');
    const stopExpansion = element('button');
    const message = element('p', '', 'tree-message');
    const caveat = element('p', '', 'tree-caveat');
    const viewport = element('div', '', 'tree-viewport');
    viewport.tabIndex = 0;
    const space = element('div', '', 'tree-space');
    const content = element('div', '', 'tree-content');
    space.append(content);
    viewport.append(space);
    bar.append(out, reset, zoomIn, fit, expandAll, collapseAll, focusMissing, stopExpansion, retry);
    root.append(bar, message, caveat, viewport);
    for (const button of [out, reset, zoomIn, fit, expandAll, collapseAll, focusMissing, stopExpansion, retry])
        button.type = 'button';
    const icons = application.icons.observe(viewport, paintResourceIcon);
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.classList.add('tree-lines');
    svg.setAttribute('aria-hidden', 'true');
    content.append(svg);
    type Row = ThemeState['crafting']['tree']['rows'][number];
    function createCard(row: Row) {
        const wrapper = element('div', '', 'tree-row');
        const button = element('button', '', 'tree-card slot-frame');
        button.type = 'button';
        const marker = element('span', '', 'tree-marker');
        const name = element('strong');
        const details = element('span', '', 'tree-details');
        const amount = element('span', '', 'tree-amount');
        const symbol = element('span', '', 'tree-symbol');
        symbol.setAttribute('aria-hidden', 'true');
        const card = {
            wrapper,
            button,
            marker,
            name,
            details,
            amount,
            symbol,
            row,
            icon: null as HTMLSpanElement | null,
            title: '',
            symbolName: '',
            presentation: null as ReturnType<typeof craftingTreeContent> | null,
            resource: null as ThemeState['crafting']['tree']['resources'][number] | null
        };
        button.append(marker, symbol, name, amount, details);
        wrapper.append(button);
        tooltip.bind(button, () => {
            const presentation = card.presentation;
            if (!presentation) return [];
            const heading = element('strong');
            heading.append(renderMinecraftText(presentation.title));
            return [
                heading,
                ...(card.resource ? tooltip.metadata(card.resource) : []),
                ...presentation.details.map((line) => element('span', line))
            ];
        });
        button.onclick = () => {
            const current = card.row;
            if (current.reference) {
                const target = current.referenceTarget ? cards.get(current.referenceTarget)?.button : null;
                target?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
                target?.focus({ preventScroll: true });
                return;
            }
            if (current.node.kind === 'CONTRIBUTION') return;
            const box = button.getBoundingClientRect();
            pendingAnchor = { key: current.key, left: box.left, top: box.top };
            void application.crafting.toggleTree(current.key);
        };
        return card;
    }
    const cards = new Map<string, ReturnType<typeof createCard>>();
    let pendingAnchor: { key: string; left: number; top: number } | null = null;
    let lastDisplay: TerminalDisplay | undefined;
    let captureId: string | null = null;
    let panInitialized = false;
    const outline = window.matchMedia('(width <= 700px)');
    const origin = () => (outline.matches ? { x: 0, y: 0 } : { x: viewport.clientWidth, y: viewport.clientHeight });
    function resetPan() {
        const { x, y } = origin();
        viewport.scrollTo(x, y);
    }
    let scale = 1,
        width = 300,
        height = 120,
        revision = -1;
    let locale: Translator | undefined;
    function zoom(value: number, fit = false) {
        scale = Math.max(fit ? 0.001 : 0.05, Math.min(1.8, value));
        content.style.transform = `scale(${scale})`;
        const { x: padX, y: padY } = origin();
        content.style.left = `${padX}px`;
        content.style.top = `${padY}px`;
        space.style.width = `${width * scale + 2 * padX}px`;
        space.style.height = `${height * scale + 2 * padY}px`;
        reset.textContent = `${Math.round(scale * 100)}%`;
    }
    /** Zoom so that the diagram point under the given screen position stays under it. */
    function zoomAt(value: number, clientX: number, clientY: number) {
        const before = content.getBoundingClientRect();
        const x = (clientX - before.left) / scale,
            y = (clientY - before.top) / scale;
        zoom(value);
        const after = content.getBoundingClientRect();
        viewport.scrollLeft += after.left + x * scale - clientX;
        viewport.scrollTop += after.top + y * scale - clientY;
    }
    fit.onclick = () => {
        zoom(Math.min(viewport.clientWidth / width, viewport.clientHeight / height), true);
        resetPan();
    };
    out.onclick = () => zoom(scale / 1.2);
    zoomIn.onclick = () => zoom(scale * 1.2);
    reset.onclick = () => {
        zoom(1);
        resetPan();
    };
    expandAll.onclick = () => void application.crafting.expandAllTree();
    collapseAll.onclick = () => application.crafting.collapseAllTree();
    stopExpansion.onclick = () => application.crafting.stopTreeExpansion();
    focusMissing.onclick = () => void application.crafting.focusMissingTree();
    retry.onclick = () => void application.crafting.retryTree();
    // The narrow layout is an ordinary outline: it has nothing to zoom and its wheel scrolls the page.
    function changeOutline() {
        zoom(scale, true);
        resetPan();
    }
    outline.addEventListener('change', changeOutline);
    viewport.addEventListener(
        'wheel',
        (event) => {
            if (outline.matches || Math.abs(event.deltaX) > Math.abs(event.deltaY)) return;
            event.preventDefault();
            const lines = event.deltaMode === WheelEvent.DOM_DELTA_LINE ? 16 : 1;
            // Trackpad pinch arrives as a ctrl+wheel with small deltas.
            zoomAt(
                scale * Math.exp((-event.deltaY * lines) / (event.ctrlKey ? 100 : 700)),
                event.clientX,
                event.clientY
            );
        },
        { passive: false }
    );
    // A press moves the diagram only after it travels a few pixels, so a plain click still reaches its card.
    const DRAG_DISTANCE = 4;
    let drag: { x: number; y: number; left: number; top: number; pointer: number; moving: boolean } | null = null;
    let dragged = false;
    function endDrag() {
        drag = null;
        delete viewport.dataset.dragging;
    }
    viewport.addEventListener('pointerdown', (event) => {
        if (event.pointerType !== 'mouse' || event.button !== 0) return;
        dragged = false;
        drag = {
            x: event.clientX,
            y: event.clientY,
            left: viewport.scrollLeft,
            top: viewport.scrollTop,
            pointer: event.pointerId,
            moving: false
        };
    });
    viewport.addEventListener('pointermove', (event) => {
        if (!drag || event.pointerId !== drag.pointer) return;
        const dx = event.clientX - drag.x,
            dy = event.clientY - drag.y;
        if (!drag.moving) {
            if (Math.hypot(dx, dy) < DRAG_DISTANCE) return;
            drag.moving = dragged = true;
            viewport.dataset.dragging = 'true';
            viewport.setPointerCapture(event.pointerId);
        }
        viewport.scrollTo(drag.left - dx, drag.top - dy);
    });
    viewport.addEventListener('pointerup', endDrag);
    viewport.addEventListener('pointercancel', endDrag);
    // Releasing a drag over a card must not also press it.
    viewport.addEventListener(
        'click',
        (event) => {
            if (!dragged) return;
            dragged = false;
            event.stopPropagation();
            event.preventDefault();
        },
        true
    );
    return {
        element: root,
        render(state: ThemeState['crafting']['tree'], nextLocale: Translator, busy: boolean, display: TerminalDisplay) {
            root.hidden = !state.visible;
            if (!state.visible) {
                icons.update([], null);
                revision = -1;
                return;
            }
            focusMissing.textContent = nextLocale.common('treeFocusMissing');
            focusMissing.disabled = busy || state.status !== 'READY' || state.expanding;
            if (revision === state.revision && locale === nextLocale && lastDisplay === display) return;
            if (captureId !== state.captureId) {
                for (const card of cards.values()) card.wrapper.remove();
                cards.clear();
                captureId = state.captureId;
                panInitialized = false;
            }
            lastDisplay = display;
            root.dataset.display = display;
            revision = state.revision;
            locale = nextLocale;
            const t = locale.common;
            out.setAttribute('aria-label', t('treeZoomOut'));
            zoomIn.setAttribute('aria-label', t('treeZoomIn'));
            reset.setAttribute('aria-label', t('treeReset'));
            fit.setAttribute('aria-label', t('treeFit'));
            retry.textContent = t('retry');
            expandAll.textContent = t('treeExpandAll');
            collapseAll.textContent = t('treeCollapseAll');
            stopExpansion.textContent = t('treeStopExpansion');
            expandAll.disabled = state.status !== 'READY' || state.expanding || state.expandedComplete;
            collapseAll.disabled = !state.rows.length;
            stopExpansion.disabled = !state.expanding;
            retry.hidden = state.status !== 'ERROR';
            retry.disabled = state.expanding || !!state.loading;
            viewport.setAttribute('aria-label', t('treeNavigation'));
            message.textContent =
                state.status === 'UNAVAILABLE'
                    ? t('treeUnavailable')
                    : state.status === 'TOO_LARGE'
                      ? t('treeTooLarge')
                      : state.status === 'ERROR'
                        ? `${t('treeError')} (${state.error})`
                        : state.status === 'PREPARING' || state.status === 'CALCULATING' || state.status === 'IDLE'
                          ? t('treePreparing')
                          : [
                                state.expanding
                                    ? `${t('treeExpanding')} ${state.expandedNodes}`
                                    : state.expandedComplete
                                      ? t('treeExpandedComplete')
                                      : '',
                                state.partial ? t('treePartial') : '',
                                state.root?.simulated ? t('treeSimulation') : ''
                            ]
                                .filter(Boolean)
                                .join(' ');
            // Only an aggregate tree is an approximation; a trace mirrors the native job.
            caveat.textContent =
                state.mode === 'AGGREGATE'
                    ? t(state.rootKind === 'REQUEST' ? 'treeOrderedApproximation' : 'treeApproximation')
                    : '';
            if (state.status === 'READY' && !state.missingComplete)
                message.textContent += ' ' + t('treeMissingIncomplete');
            const active = document.activeElement as HTMLElement | null;
            const focused = active?.dataset.treeKey;
            const top = viewport.scrollTop,
                left = viewport.scrollLeft;
            svg.replaceChildren();
            const targets = [];
            const layout = layoutCraftingTree(state.rows, display);
            width = layout.width;
            height = layout.height;
            const currentCards: ReturnType<typeof createCard>[] = [];
            const retained = new Set<string>();
            const presentations = state.rows.map((row) =>
                craftingTreeContent(row, state, locale!, document.documentElement.lang)
            );
            for (let index = 0; index < state.rows.length; index++) {
                const row = state.rows[index];
                const position = layout.nodes[index];
                const { x, y } = position;
                const identity = row.key;
                retained.add(identity);
                let card = cards.get(identity);
                if (!card) {
                    card = createCard(row);
                    cards.set(identity, card);
                }
                currentCards.push(card);
                card.row = row;
                const { wrapper, button, marker, name, details, amount, symbol } = card;
                wrapper.style.width = `${position.width}px`;
                wrapper.style.height = `${position.height}px`;
                wrapper.style.left = `${x}px`;
                wrapper.style.top = `${y}px`;
                wrapper.style.setProperty('--tree-indent', `${Math.min(row.depth, 6) * 12}px`);
                button.dataset.treeKey = row.key;
                button.dataset.reference = String(row.reference);
                const presentation = presentations[index];
                if (card.title !== presentation.title) {
                    card.title = presentation.title;
                    name.replaceChildren(renderMinecraftText(presentation.title));
                }
                const leaf = row.node.kind === 'CONTRIBUTION';
                card.presentation = presentation;
                card.resource = row.node.resourceId != null ? (state.resources[row.node.resourceId] ?? null) : null;
                marker.textContent = row.reference ? '↪' : leaf ? '' : state.expanded[row.key] ? '−' : '+';
                details.textContent = presentation.details.join(' · ');
                amount.textContent = presentation.count;
                const symbolName = presentation.symbol ?? '';
                if (card.symbolName !== symbolName) {
                    card.symbolName = symbolName;
                    symbol.innerHTML =
                        symbolName === 'craft' ? craftingHammer : symbolName === 'storage' ? terminalIcons.stored : '';
                    if (!symbol.innerHTML)
                        symbol.textContent =
                            ({ reused: '↻', missing: '!', emitted: '↑', unknown: '?' } as Record<string, string>)[
                                symbolName
                            ] ?? '';
                }
                symbol.hidden = !symbolName;
                button.setAttribute(
                    'aria-label',
                    [plainMinecraftText(presentation.title), ...presentation.details].join(' · ')
                );
                button.dataset.missing = String(presentation.missing);
                button.dataset.blocked = String(presentation.blocked);
                button.dataset.recipe = String(presentation.symbol === 'craft');
                if (display !== 'names' && (presentation.icon || (display === 'icons' && card.resource))) {
                    if (!card.icon) {
                        card.icon = createResourceIcon();
                        button.insertBefore(card.icon, name);
                    }
                    if (presentation.icon) targets.push({ element: button, icon: presentation.icon });
                    else paintResourceIcon(button, null);
                } else if (card.icon) {
                    card.icon.remove();
                    card.icon = null;
                }
                button.dataset.kind = row.node.kind;
                if (!row.reference && !leaf) button.setAttribute('aria-expanded', String(!!state.expanded[row.key]));
                else button.removeAttribute('aria-expanded');
            }
            for (const index of treeConnectionOrder(presentations)) {
                const position = layout.nodes[index];
                if (position.parent === null) continue;
                const parent = layout.nodes[position.parent];
                const px = parent.x + parent.width / 2,
                    py = parent.y + parent.height;
                const line = document.createElementNS('http://www.w3.org/2000/svg', 'path');
                line.setAttribute(
                    'd',
                    `M${px} ${py} V${position.y - 16} H${position.x + position.width / 2} V${position.y}`
                );
                line.dataset.shortage = state.rows[index].shortage;
                svg.append(line);
            }
            for (const [identity, card] of cards) {
                if (retained.has(identity)) continue;
                card.wrapper.remove();
                cards.delete(identity);
            }
            // Insert additions in preorder without moving the cards already in the correct order.
            let following: ChildNode | null = null;
            for (let index = currentCards.length - 1; index >= 0; index--) {
                const wrapper = currentCards[index].wrapper;
                if (wrapper.parentNode !== content || wrapper.nextSibling !== following)
                    content.insertBefore(wrapper, following);
                following = wrapper;
            }
            content.style.width = `${width}px`;
            content.style.height = `${height}px`;
            svg.setAttribute('width', String(width));
            svg.setAttribute('height', String(height));
            zoom(scale, true);
            icons.update(targets, state.icons);
            if (panInitialized) viewport.scrollTo(left, top);
            else {
                resetPan();
                panInitialized = true;
            }
            if (pendingAnchor) {
                const target = cards.get(pendingAnchor.key)?.button;
                if (target) {
                    const box = target.getBoundingClientRect();
                    viewport.scrollBy(box.left - pendingAnchor.left, box.top - pendingAnchor.top);
                }
                pendingAnchor = null;
            }
            if (focused && document.activeElement !== active) {
                const target = active?.isConnected
                    ? active
                    : [...content.querySelectorAll<HTMLElement>('[data-tree-key]')].find(
                          (node) => node.dataset.treeKey === focused
                      );
                target?.focus({ preventScroll: true });
            }
        },
        dispose() {
            icons.dispose();
            outline.removeEventListener('change', changeOutline);
            cards.clear();
            root.remove();
        }
    };
}
