import type { ThemeApplication, ThemeState, Translator, PinPlacement } from '../../app/theme-api.js';
import { element } from './dom.js';

/** Shared resource actions; each placement is mutated independently. */
export function createPinActions(application: ThemeApplication, menu = false, activated = () => {}) {
    const elementHost = element('div', '', 'resource-pin-actions');
    let state: ThemeState, gridKey: string | null, itemKey: string | null;
    const current = () => state.pins.items.find((pin) => pin.gridKey === gridKey && pin.itemKey === itemKey);
    const buttons = (['terminal', 'home'] as const).map((placement) => {
        const button = element('button');
        button.type = 'button';
        if (menu) button.role = 'menuitem';
        button.addEventListener('click', () => {
            if (!gridKey || !itemKey) return;
            const pin = current();
            if (pin) void application.pins.update(pin.pinKey, { [placement]: pin[`${placement}Order`] === null });
            else void application.pins.add(gridKey, itemKey, placement);
            activated();
        });
        elementHost.append(button);
        return button;
    });
    const moves = (['earlier', 'later'] as const).map((direction) => {
        const button = element('button');
        button.type = 'button';
        if (menu) button.role = 'menuitem';
        button.addEventListener('click', () => {
            const pin = current();
            if (pin) void application.pins.update(pin.pinKey, { move: { placement: 'terminal', direction } });
            activated();
        });
        elementHost.append(button);
        return button;
    });
    return {
        element: elementHost,
        buttons: [...buttons, ...moves],
        render(next: ThemeState, locale: Translator, grid: string | null, item: string | null) {
            state = next;
            gridKey = grid;
            itemKey = item;
            elementHost.hidden = !grid || !item;
            const pin = current();
            (['terminal', 'home'] as PinPlacement[]).forEach((placement, index) => {
                const pinned = pin?.[`${placement}Order`] != null;
                buttons[index].textContent = locale.common(
                    `${pinned ? 'unpin' : 'pin'}${placement === 'terminal' ? 'Terminal' : 'Home'}`
                );
                buttons[index].disabled = state.pins.pending || state.pins.status !== 'ready';
                buttons[index].setAttribute('aria-pressed', String(pinned));
            });
            const ordered = state.pins.items
                .filter((value) => value.gridKey === grid && value.terminalOrder !== null)
                .sort((a, b) => a.terminalOrder! - b.terminalOrder!);
            const index = pin ? ordered.findIndex((value) => value.pinKey === pin.pinKey) : -1;
            moves.forEach((button, position) => {
                button.hidden = index < 0;
                button.disabled = state.pins.pending || (position === 0 ? index <= 0 : index === ordered.length - 1);
                button.textContent = locale.common(position === 0 ? 'moveEarlier' : 'moveLater');
            });
        }
    };
}
