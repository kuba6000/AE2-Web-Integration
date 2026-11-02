import { craftingTreeContent } from './crafting-tree-content.js';
import { layoutCraftingTree } from './crafting-tree-layout.js';
import type { TooltipBindings } from './tooltip-bindings.js';
import { element } from './dom.js';
import { createResourceIcon, paintResourceIcon } from './resource-icon.js';
import { renderMinecraftText } from './minecraft-text.js';
import type { ThemeApplication, ThemeState } from '../../app/theme-api.js';
import type { Translator } from '../../app/i18n.js';

/** Fixed-card diagram with an ordinary button outline on narrow screens. Data belongs to the app. */
export function createCraftingTreeView(application: ThemeApplication, tooltip: TooltipBindings) {
    const root = element('section', '', 'plan-tree');
    const bar = element('div', '', 'tree-controls');
    const out = element('button', '−');
    const reset = element('button', '100%');
    const zoomIn = element('button', '+');
    const fit = element('button', '⊡');
    const retry = element('button');
    const reveal = element('button');
    const expandAll = element('button');
    const collapseAll = element('button');
    const missingOnly = element('button');
    const stopExpansion = element('button');
    const previous = element('button');
    const next = element('button');
    const message = element('p', '', 'tree-message');
    const caveat = element('p', '', 'tree-caveat');
    const viewport = element('div', '', 'tree-viewport');
    viewport.tabIndex = 0;
    const space = element('div', '', 'tree-space');
    const content = element('div', '', 'tree-content');
    space.append(content);
    viewport.append(space);
    bar.append(
        out,
        reset,
        zoomIn,
        fit,
        expandAll,
        collapseAll,
        missingOnly,
        stopExpansion,
        previous,
        next,
        retry,
        reveal
    );
    root.append(bar, message, caveat, viewport);
    for (const button of [
        out,
        reset,
        zoomIn,
        fit,
        expandAll,
        collapseAll,
        missingOnly,
        stopExpansion,
        previous,
        next,
        retry,
        reveal
    ])
        button.type = 'button';
    const icons = application.icons.observe(viewport, paintResourceIcon);
    let scale = 1,
        width = 300,
        height = 120,
        revision = -1;
    let locale: Translator | undefined;
    function zoom(value: number, fit = false) {
        scale = Math.max(fit ? 0.001 : 0.05, Math.min(1.8, value));
        content.style.transform = `scale(${scale})`;
        space.style.width = `${width * scale}px`;
        space.style.height = `${height * scale}px`;
        reset.textContent = `${Math.round(scale * 100)}%`;
    }
    /** Zoom so that the diagram point under the given screen position stays under it. */
    function zoomAt(value: number, clientX: number, clientY: number) {
        const before = space.getBoundingClientRect();
        const x = (clientX - before.left) / scale,
            y = (clientY - before.top) / scale;
        zoom(value);
        const after = space.getBoundingClientRect();
        viewport.scrollLeft += after.left + x * scale - clientX;
        viewport.scrollTop += after.top + y * scale - clientY;
    }
    previous.onclick = () => {
        application.crafting.windowTree(-1);
        requestAnimationFrame(() =>
            content.querySelector('[data-tree-key]')?.scrollIntoView({ block: 'start', inline: 'start' })
        );
    };
    next.onclick = () => {
        application.crafting.windowTree(1);
        requestAnimationFrame(() =>
            content.querySelector('[data-tree-key]')?.scrollIntoView({ block: 'start', inline: 'start' })
        );
    };
    fit.onclick = () => {
        zoom(Math.min(viewport.clientWidth / width, viewport.clientHeight / height), true);
        viewport.scrollTo(0, 0);
    };
    out.onclick = () => zoom(scale / 1.2);
    zoomIn.onclick = () => zoom(scale * 1.2);
    reset.onclick = () => {
        application.crafting.focusTree(null);
        zoom(1);
        viewport.scrollTo(0, 0);
    };
    expandAll.onclick = () => void application.crafting.expandAllTree();
    collapseAll.onclick = () => application.crafting.collapseAllTree();
    stopExpansion.onclick = () => application.crafting.stopTreeExpansion();
    missingOnly.onclick = () => void application.crafting.missingTree(!application.state.crafting.tree.missingOnly);
    reveal.onclick = () => void application.crafting.revealMissingTree();
    retry.onclick = () => void application.crafting.retryTree();
    // The narrow layout is an ordinary outline: it has nothing to zoom and its wheel scrolls the page.
    const outline = window.matchMedia('(width <= 700px)');
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
        render(state: ThemeState['crafting']['tree'], nextLocale: Translator, busy: boolean) {
            root.hidden = !state.visible;
            if (!state.visible) {
                icons.update([], null);
                revision = -1;
                return;
            }
            missingOnly.textContent = nextLocale.common('treeMissingOnly');
            missingOnly.setAttribute('aria-pressed', String(state.missingOnly));
            missingOnly.disabled = busy;
            if (revision === state.revision && locale === nextLocale) return;
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
            reveal.textContent = t('treeRevealMissing');
            reveal.hidden = !state.missingOnly;
            reveal.disabled = state.expanding || !!state.loading || !state.canRevealMissing;
            previous.textContent = t('treePrevious');
            next.textContent = t('treeNext');
            previous.hidden = state.windowStart === 0;
            next.hidden = state.windowStart + state.rows.length >= state.totalRows;
            retry.hidden = state.status !== 'ERROR' && !state.bounded;
            retry.disabled = state.expanding || !!state.loading;
            viewport.setAttribute('aria-label', t('treeNavigation'));
            message.textContent =
                state.status === 'UNAVAILABLE'
                    ? t('treeUnavailable')
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
                              state.cacheBounded ? t('treeCacheBounded') : state.bounded ? t('treeBounded') : '',
                              state.root?.simulated ? t('treeSimulation') : ''
                          ]
                              .filter(Boolean)
                              .join(' ');
            // Only an aggregate tree is an approximation; a trace mirrors the native job.
            caveat.textContent =
                state.mode === 'AGGREGATE'
                    ? t('treeApproximation') + (state.missingOnly ? ' ' + t('treeMissingPaths') : '')
                    : '';
            if (
                state.missingOnly &&
                state.status === 'READY' &&
                state.missingComplete &&
                state.missingResourceCount === 0
            )
                message.textContent = t('treeNoMissing');
            else if (state.missingOnly && !state.missingComplete)
                message.textContent += ' ' + t('treeMissingIncomplete');
            const active = document.activeElement as HTMLElement | null;
            const focused = active?.dataset.treeKey ?? active?.dataset.treeMore;
            const focusedMore = !!active?.dataset.treeMore;
            const top = viewport.scrollTop,
                left = viewport.scrollLeft;
            content.replaceChildren();
            const targets = [];
            const layout = layoutCraftingTree(state.rows);
            width = layout.width;
            height = layout.height;
            const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
            svg.classList.add('tree-lines');
            svg.setAttribute('aria-hidden', 'true');
            content.append(svg);
            for (let index = 0; index < state.rows.length; index++) {
                const row = state.rows[index];
                const position = layout.nodes[index];
                const { x, y } = position;
                if (position.parent !== null) {
                    const parent = layout.nodes[position.parent];
                    const px = parent.x + parent.width / 2,
                        py = parent.y + 108;
                    const line = document.createElementNS('http://www.w3.org/2000/svg', 'path');
                    line.setAttribute('d', `M${px} ${py} V${y - 16} H${x + position.width / 2} V${y}`);
                    svg.append(line);
                }
                const wrapper = element('div', '', 'tree-row');
                wrapper.style.width = `${position.width}px`;
                wrapper.style.left = `${x}px`;
                wrapper.style.top = `${y}px`;
                wrapper.style.setProperty('--tree-indent', `${Math.min(row.depth, 6) * 12}px`);
                const button = element('button', '', 'tree-card slot-frame');
                button.type = 'button';
                button.dataset.treeKey = row.key;
                button.dataset.reference = String(row.reference);
                const presentation = craftingTreeContent(row, state, locale, document.documentElement.lang);
                const name = element('strong');
                name.append(renderMinecraftText(presentation.title));
                const leaf = row.node.kind === 'CONTRIBUTION';
                const marker = element(
                    'span',
                    row.reference ? '↪' : leaf ? '•' : state.expanded[row.key] ? '−' : '+',
                    'tree-marker'
                );
                const details = element('span', presentation.details.join(' · '), 'tree-details');
                button.dataset.missing = String(presentation.missing);
                button.append(marker);
                if (presentation.icon) {
                    button.append(createResourceIcon());
                    targets.push({ element: button, icon: presentation.icon });
                }
                button.append(name, details);
                tooltip.bind(button, () => [
                    element('strong', name.textContent ?? ''),
                    element('span', details.textContent ?? '')
                ]);
                button.dataset.kind = row.node.kind;
                if (!row.reference && !leaf) button.setAttribute('aria-expanded', String(!!state.expanded[row.key]));
                button.onclick = () => {
                    if (leaf) return;
                    if (row.reference) {
                        application.crafting.focusTree(row.key);
                        requestAnimationFrame(() => {
                            const target = [...content.querySelectorAll<HTMLElement>('[data-tree-key]')].find(
                                (entry) => entry.dataset.treeKey === row.key && entry.dataset.reference === 'false'
                            );
                            target?.scrollIntoView({ block: 'center', inline: 'center' });
                            target?.focus({ preventScroll: true });
                        });
                    } else void application.crafting.toggleTree(row.key);
                };
                button.setAttribute(
                    'aria-disabled',
                    String(
                        !leaf && !!state.loading && !state.pages[row.key] && !state.expanded[row.key] && !row.reference
                    )
                );
                wrapper.append(button);
                if (!row.reference && state.expanded[row.key] && state.pages[row.key]?.nextOffset != null) {
                    const more = element('button', t('treeMore'), 'tree-more');
                    more.type = 'button';
                    more.dataset.treeMore = row.key;
                    more.setAttribute(
                        'aria-disabled',
                        String(state.expanding || !!state.loading || state.cacheBounded)
                    );
                    more.onclick = () => void application.crafting.moreTree(row.key);
                    wrapper.append(more);
                }
                content.append(wrapper);
            }
            content.style.width = `${width}px`;
            content.style.height = `${height}px`;
            svg.setAttribute('width', String(width));
            svg.setAttribute('height', String(height));
            zoom(scale, true);
            icons.update(targets, state.icons);
            viewport.scrollTo(left, top);
            if (focused) {
                const control = focusedMore
                    ? [...content.querySelectorAll<HTMLElement>('[data-tree-more]')].find(
                          (node) => node.dataset.treeMore === focused
                      )
                    : null;
                (
                    control ??
                    [...content.querySelectorAll<HTMLElement>('[data-tree-key]')].find(
                        (node) => node.dataset.treeKey === focused
                    )
                )?.focus({ preventScroll: true });
            }
        },
        dispose() {
            icons.dispose();
            root.remove();
        }
    };
}
