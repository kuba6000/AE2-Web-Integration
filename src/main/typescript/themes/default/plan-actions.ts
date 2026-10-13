import type { ThemeState, ThemeApplication } from '../../app/theme-api.js';
import type { Translator } from '../../app/i18n.js';
import { element } from './dom.js';
import type { TooltipBindings } from './tooltip-bindings.js';

export function createPlanActions(
    application: ThemeApplication,
    workspace: HTMLElement,
    tooltip: Pick<TooltipBindings, 'hide'>
) {
    const start = element('button');
    start.type = 'button';
    start.addEventListener('click', () => application.crafting.submit());
    const more = element('button', '▾');
    more.type = 'button';
    more.setAttribute('aria-haspopup', 'menu');
    more.setAttribute('aria-expanded', 'false');
    const menu = element('div');
    menu.className = 'window-frame resource-menu plan-recalculate-menu';
    menu.role = 'menu';
    menu.popover = 'auto';
    menu.id = 'plan-recalculate-menu';
    more.setAttribute('aria-controls', menu.id);
    const variants = [
        { automatic: false, ignoreMissing: false, label: 'recalculate' },
        { automatic: true, ignoreMissing: false, label: 'recalculateAuto' },
        { automatic: false, ignoreMissing: true, label: 'recalculateMissing' },
        { automatic: true, ignoreMissing: true, label: 'recalculateMissingAuto' }
    ]
        .filter((variant) => !variant.ignoreMissing || application.state.capabilities.craftingIgnoreMissing === true)
        .map((variant) => {
            const button = element('button');
            button.type = 'button';
            button.role = 'menuitem';
            button.addEventListener('click', () => {
                menu.hidePopover();
                more.focus();
                void application.crafting.recalculate(variant.automatic, variant.ignoreMissing);
            });
            menu.append(button);
            return { ...variant, button };
        });
    menu.addEventListener('toggle', () => {
        more.setAttribute('aria-expanded', String(menu.matches(':popover-open')));
    });
    more.addEventListener('click', () => {
        menu.togglePopover();
        if (!menu.matches(':popover-open')) return;
        tooltip.hide();
        const bounds = more.getBoundingClientRect();
        menu.style.left = `${Math.max(8, Math.min(bounds.right - menu.offsetWidth, innerWidth - menu.offsetWidth - 8))}px`;
        menu.style.top = `${Math.max(8, bounds.top - menu.offsetHeight - 4)}px`;
        menu.querySelector('button')?.focus();
    });
    menu.addEventListener('keydown', (event) => {
        const buttons = [...menu.querySelectorAll('button')];
        const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
        let next = index;
        if (event.key === 'ArrowDown') next = (index + 1) % buttons.length;
        else if (event.key === 'ArrowUp') next = (index + buttons.length - 1) % buttons.length;
        else if (event.key === 'Home') next = 0;
        else if (event.key === 'End') next = buttons.length - 1;
        else if (event.key === 'Escape') {
            menu.hidePopover();
            more.focus();
            return;
        } else if (event.key === 'Tab') {
            menu.hidePopover();
            return;
        } else return;
        event.preventDefault();
        buttons[next]?.focus();
    });
    const startControls = element('div');
    startControls.className = 'plan-start-controls';
    startControls.append(start, more);
    workspace.append(menu);
    const remove = element('button');
    remove.type = 'button';
    remove.addEventListener('click', () => application.crafting.remove());
    const actions = element('div');
    actions.className = 'plan-actions';
    actions.append(startControls, remove);

    return {
        element: actions,
        close() {
            menu.hidePopover();
        },
        render(state: ThemeState['crafting'], locale: Translator) {
            const t = locale.common;
            start.textContent = t('startCrafting');
            start.disabled = state.status !== 'ready' || !!state.mutation || !!state.uncertain || !state.selectedCpu;
            more.setAttribute('aria-label', t('recalculateOptions'));
            menu.setAttribute('aria-label', t('recalculateOptions'));
            more.disabled =
                !state.metadata?.itemKey ||
                !!state.mutation ||
                !!state.uncertain ||
                !['ready', 'error'].includes(state.status);
            if (more.disabled) menu.hidePopover();
            const ordered = [...variants].sort((a, b) => {
                const current = state.metadata;
                const aMatches = a.automatic === current?.automatic && a.ignoreMissing === current?.ignoreMissing;
                const bMatches = b.automatic === current?.automatic && b.ignoreMissing === current?.ignoreMissing;
                return Number(bMatches) - Number(aMatches);
            });
            for (const [index, variant] of ordered.entries()) {
                variant.button.textContent = t(variant.label);
                if (menu.children[index] !== variant.button)
                    menu.insertBefore(variant.button, menu.children[index] || null);
            }
            remove.textContent = t('cancelPlan');
            remove.hidden = ['submitted', 'deleted', 'unavailable', 'error'].includes(state.status);
            remove.disabled = !!state.mutation || !!state.uncertain;
        },
        dispose() {
            menu.remove();
            actions.remove();
        }
    };
}
