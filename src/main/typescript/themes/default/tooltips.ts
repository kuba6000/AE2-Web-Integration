import type { ThemeLifetime } from '../../app/theme-api.js';
import { element } from './dom.js';

export type Tooltips = ReturnType<typeof createTooltips>;

export function createTooltips(root: HTMLElement, lifetime: ThemeLifetime) {
    const tooltip = element('div', '', 'tooltip');
    tooltip.id = 'resource-tooltip';
    tooltip.role = 'tooltip';
    tooltip.hidden = true;
    root.append(tooltip);

    let attachedTooltip: {
        target: HTMLElement;
        focusTarget: HTMLElement;
        content: () => Node[];
        pointer: boolean;
        x: number;
        y: number;
    } | null = null;

    let activeTarget: HTMLElement | null = null;
    function hideTooltip() {
        attachedTooltip?.focusTarget.removeAttribute('aria-describedby');
        attachedTooltip = null;
        activeTarget = null;
        tooltip.hidden = true;
    }
    function positionTooltip(x: number, y: number) {
        const box = tooltip.getBoundingClientRect();
        tooltip.style.left = `${Math.max(8, Math.min(x + 14, innerWidth - box.width - 8))}px`;
        tooltip.style.top = `${Math.max(8, y + box.height + 24 > innerHeight ? y - box.height - 10 : y + 16)}px`;
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
            activeTarget = target;
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

    window.addEventListener('scroll', hideTooltip, { capture: true, signal: lifetime.signal });
    window.addEventListener('resize', hideTooltip, { signal: lifetime.signal });
    window.addEventListener(
        'keydown',
        (event) => {
            if (event.key === 'Escape') hideTooltip();
        },
        { signal: lifetime.signal }
    );
    lifetime.onCleanup(() => {
        hideTooltip();
        tooltip.remove();
    });
    return {
        bind: bindTooltip,
        hide: hideTooltip,
        refresh: refreshAttachedTooltip,
        position: positionTooltip,
        isActive: (target: HTMLElement) => activeTarget === target && !tooltip.hidden,
        show(target: HTMLElement, content: Node[], x: number, y: number) {
            hideTooltip();
            activeTarget = target;
            tooltip.replaceChildren(...content);
            tooltip.hidden = false;
            positionTooltip(x, y);
        }
    };
}
