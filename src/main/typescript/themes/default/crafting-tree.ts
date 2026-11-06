import { craftingTreeContent } from './crafting-tree-content.js';
import { layoutCraftingTree, treeCardTextLayout, treeViewport } from './crafting-tree-layout.js';
import type { CraftingTooltipPresenter } from './crafting.js';
import type { TerminalDisplay } from './web-settings.js';
import { craftingHammer, terminalIcons } from './icons/pixel/terminal.js';
import { plainMinecraftText } from '../../app/minecraft-text.js';
import { element } from './dom.js';
import { createResourceIcon, paintResourceIcon } from './resource-icon.js';
import { renderMinecraftText } from './minecraft-text.js';
import type { ThemeApplication, ThemeState } from '../../app/theme-api.js';
import type { Translator } from '../../app/i18n.js';

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
    const canvas = element('canvas', '', 'tree-overview');
    canvas.setAttribute('aria-hidden', 'true');
    space.prepend(canvas);
    let current: ThemeState['crafting']['tree'] | undefined;
    let layout = layoutCraftingTree([]);
    let indices = new Map<string, number>();
    const presentations = new Map<number, ReturnType<typeof craftingTreeContent>>();
    let frame = 0;
    let disposed = false;
    let wheel: { value: number; x: number; y: number } | null = null;
    function schedulePaint() {
        if (frame || disposed || root.hidden) return;
        frame = requestAnimationFrame(() => {
            frame = 0;
            if (disposed || root.hidden) return;
            if (wheel) {
                const pending = wheel;
                wheel = null;
                zoomAt(pending.value, pending.x, pending.y);
            }
            paintViewport();
        });
    }
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
                const index = current.referenceTarget ? indices.get(current.referenceTarget) : undefined;
                if (index !== undefined) focusRow(index);
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
        reset.textContent = scale < 0.01 ? '<1%' : `${Math.round(scale * 100)}%`;
        schedulePaint();
    }
    /** Zoom so that the diagram point under the given screen position stays under it. */
    function zoomAt(value: number, clientX: number, clientY: number) {
        const box = viewport.getBoundingClientRect();
        const { x: padX, y: padY } = origin();
        const localX = clientX - box.left - viewport.clientLeft;
        const localY = clientY - box.top - viewport.clientTop;
        const x = (viewport.scrollLeft + localX - padX) / scale;
        const y = (viewport.scrollTop + localY - padY) / scale;
        zoom(value);
        viewport.scrollTo(padX + x * scale - localX, padY + y * scale - localY);
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
    const darkAppearance = window.matchMedia('(prefers-color-scheme: dark)');
    darkAppearance.addEventListener('change', schedulePaint);
    const appearance = new MutationObserver(schedulePaint);
    appearance.observe(document.documentElement, { attributes: true, attributeFilter: ['data-appearance'] });
    viewport.addEventListener(
        'wheel',
        (event) => {
            if (outline.matches || Math.abs(event.deltaX) > Math.abs(event.deltaY)) return;
            event.preventDefault();
            const lines = event.deltaMode === WheelEvent.DOM_DELTA_LINE ? 16 : 1;
            // Trackpad pinch arrives as a ctrl+wheel with small deltas.
            wheel = {
                value: (wheel?.value ?? scale) * Math.exp((-event.deltaY * lines) / (event.ctrlKey ? 100 : 700)),
                x: event.clientX,
                y: event.clientY
            };
            schedulePaint();
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
    function paintViewport() {
        const state = current;
        if (!state || !locale || !lastDisplay || root.hidden || disposed) return;
        const display = lastDisplay;
        const active = document.activeElement as HTMLElement | null;
        const focused = active?.dataset.treeKey;
        const top = viewport.scrollTop,
            left = viewport.scrollLeft;
        const targets = [];
        const currentCards: ReturnType<typeof createCard>[] = [];
        const retained = new Set<string>();
        const pad = origin();
        const overscan = 160 / scale;
        const visible = treeViewport(layout.nodes, {
            x: (left - pad.x) / scale - overscan,
            y: (top - pad.y) / scale - overscan,
            width: viewport.clientWidth / scale + 2 * overscan,
            height: viewport.clientHeight / scale + 2 * overscan
        });
        const overview = !outline.matches && scale < 0.35;
        paintCanvas(visible, overview);
        const mounted = outline.matches ? state.rows.map((_, index) => index) : overview ? [] : visible.cards;
        const pinned = focused ?? pendingAnchor?.key;
        const pinnedIndex = pinned ? indices.get(pinned) : undefined;
        if (pinnedIndex !== undefined && !mounted.includes(pinnedIndex)) mounted.push(pinnedIndex);
        mounted.sort((a, b) => a - b);
        for (const index of mounted) {
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
            let presentation = presentations.get(index);
            if (!presentation) {
                presentation = craftingTreeContent(row, state, locale!, document.documentElement.lang);
                presentations.set(index, presentation);
            }
            if (card.title !== presentation.title) {
                card.title = presentation.title;
                name.replaceChildren(renderMinecraftText(presentation.title));
            }
            const leaf = row.node.kind === 'CONTRIBUTION';
            card.presentation = presentation;
            card.resource = row.node.resourceId != null ? (state.resources[row.node.resourceId] ?? null) : null;
            marker.textContent = row.reference ? '↪' : leaf ? '' : state.expanded[row.key] ? '−' : '+';
            const textLayout = treeCardTextLayout(position.height, presentation.details.length);
            button.style.setProperty('--tree-text-height', `${textLayout.height}px`);
            details.textContent = presentation.details.slice(0, textLayout.lineCount).join('\n');
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
        icons.update(targets, state.icons);
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
    }
    function paintCanvas(visible: ReturnType<typeof treeViewport>, overview: boolean) {
        const state = current;
        canvas.hidden = outline.matches;
        if (outline.matches || !state) return;
        const w = viewport.clientWidth,
            h = viewport.clientHeight;
        const dpr = Math.min(window.devicePixelRatio || 1, 2);
        if (canvas.width !== Math.ceil(w * dpr)) canvas.width = Math.ceil(w * dpr);
        if (canvas.height !== Math.ceil(h * dpr)) canvas.height = Math.ceil(h * dpr);
        canvas.style.width = `${w}px`;
        canvas.style.height = `${h}px`;
        canvas.style.left = `${viewport.scrollLeft}px`;
        canvas.style.top = `${viewport.scrollTop}px`;
        const ctx = canvas.getContext('2d');
        if (!ctx) return;
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        ctx.clearRect(0, 0, w, h);
        const pad = origin();
        ctx.translate(pad.x - viewport.scrollLeft, pad.y - viewport.scrollTop);
        ctx.scale(scale, scale);
        const colors = getComputedStyle(root);
        const color = (name: string) => colors.getPropertyValue(name).trim();
        const stroke = {
            NONE: color('--surface-text'),
            BLOCKED: color('--tree-blocked'),
            MISSING: color('--tree-missing-border')
        };
        for (const shortage of ['NONE', 'BLOCKED', 'MISSING'] as const) {
            ctx.strokeStyle = stroke[shortage];
            ctx.globalAlpha = shortage === 'NONE' ? 0.5 : 1;
            ctx.lineWidth = (shortage === 'NONE' ? 1 : 2) / (overview ? scale : 1);
            ctx.beginPath();
            for (const index of visible.connections) {
                if (state.rows[index].shortage !== shortage) continue;
                const node = layout.nodes[index],
                    parent = layout.nodes[node.parent!];
                const x = parent.x + parent.width / 2,
                    y = parent.y + parent.height;
                ctx.moveTo(x, y);
                ctx.lineTo(x, node.y - 16);
                ctx.lineTo(node.x + node.width / 2, node.y - 16);
                ctx.lineTo(node.x + node.width / 2, node.y);
            }
            ctx.stroke();
        }
        ctx.globalAlpha = 1;
        if (overview)
            for (const index of visible.cards) {
                const row = state.rows[index],
                    node = layout.nodes[index];
                const recipe = row.node.kind === 'TASK' || row.node.kind === 'RECIPE';
                ctx.fillStyle = color(
                    row.shortage === 'MISSING' ? '--tree-missing' : recipe ? '--tree-recipe' : '--slot'
                );
                ctx.fillRect(node.x, node.y, node.width, node.height);
                ctx.strokeStyle = row.shortage === 'NONE' ? color('--slot-edge-light') : stroke[row.shortage];
                ctx.lineWidth = 1 / scale;
                ctx.strokeRect(node.x, node.y, node.width, node.height);
            }
    }
    function focusRow(index: number) {
        const state = current;
        if (!state) return;
        const row = state.rows[index],
            node = layout.nodes[index];
        if (!row || !node) return;
        if (!outline.matches) {
            if (scale < 0.35) zoom(1);
            const pad = origin();
            viewport.scrollTo(
                pad.x + (node.x + node.width / 2) * scale - viewport.clientWidth / 2,
                pad.y + (node.y + node.height / 2) * scale - viewport.clientHeight / 2
            );
        }
        paintViewport();
        const button = cards.get(row.key)?.button;
        if (outline.matches) button?.scrollIntoView({ block: 'nearest' });
        button?.focus({ preventScroll: true });
    }
    viewport.addEventListener('keydown', (event) => {
        const state = current;
        if (!state || !['ArrowDown', 'ArrowUp', 'Home', 'End', 'Tab'].includes(event.key)) return;
        const key = (document.activeElement as HTMLElement | null)?.dataset.treeKey;
        const index = key ? (indices.get(key) ?? -1) : -1;
        if (event.key === 'Tab' && (outline.matches || index < 0)) return;
        const next =
            event.key === 'Home'
                ? 0
                : event.key === 'End'
                  ? state.rows.length - 1
                  : index + (event.key === 'ArrowUp' || event.shiftKey ? -1 : 1);
        if (next < 0 || next >= state.rows.length) return;
        event.preventDefault();
        focusRow(next);
    });
    viewport.addEventListener('dblclick', (event) => {
        const state = current;
        if (outline.matches || scale >= 0.35 || !state) return;
        const box = viewport.getBoundingClientRect(),
            pad = origin();
        const x = (event.clientX - box.left - viewport.clientLeft + viewport.scrollLeft - pad.x) / scale;
        const y = (event.clientY - box.top - viewport.clientTop + viewport.scrollTop - pad.y) / scale;
        const index = layout.nodes.findIndex(
            (node) => x >= node.x && x <= node.x + node.width && y >= node.y && y <= node.y + node.height
        );
        if (index >= 0) focusRow(index);
        else zoomAt(1, event.clientX, event.clientY);
    });
    viewport.addEventListener('scroll', schedulePaint, { passive: true });
    const resize = new ResizeObserver(() => {
        if (!root.hidden) zoom(scale, true);
    });
    resize.observe(viewport);
    return {
        element: root,
        render(
            nextState: ThemeState['crafting']['tree'],
            nextLocale: Translator,
            busy: boolean,
            display: TerminalDisplay
        ) {
            const state = nextState;
            root.hidden = !state.visible;
            if (!state.visible) {
                cancelAnimationFrame(frame);
                frame = 0;
                wheel = null;
                icons.update([], null);
                revision = -1;
                return;
            }
            focusMissing.textContent = nextLocale.common('treeFocusMissing');
            focusMissing.disabled = busy || state.status !== 'READY' || state.expanding;
            const unchanged = revision === state.revision && locale === nextLocale && lastDisplay === display;
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
            // Expansion progress changes controls, not the displayed graph.
            if (unchanged) return;
            current = nextState;
            layout = layoutCraftingTree(state.rows, display);
            indices = new Map(state.rows.map((row, index) => [row.key, index]));
            presentations.clear();
            width = layout.width;
            height = layout.height;
            content.style.width = `${width}px`;
            content.style.height = `${height}px`;
            zoom(scale, true);
            if (!panInitialized) {
                resetPan();
                panInitialized = true;
            }
            paintViewport();
        },
        dispose() {
            disposed = true;
            cancelAnimationFrame(frame);
            resize.disconnect();
            appearance.disconnect();
            darkAppearance.removeEventListener('change', schedulePaint);
            viewport.removeEventListener('scroll', schedulePaint);
            icons.dispose();
            outline.removeEventListener('change', changeOutline);
            cards.clear();
            presentations.clear();
            indices.clear();
            current = undefined;
            canvas.width = canvas.height = 0;
            root.remove();
        }
    };
}
