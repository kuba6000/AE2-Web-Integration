import type { ThemeState, ThemeApplication, ThemeLifetime } from '../../app/theme-api.js';
import type { Translator } from '../../app/i18n.js';
import { element } from './dom.js';
import type { StoredResource } from '../../app/api-types.js';
import { createResourceIcon, paintResourceIcon } from './resource-icon.js';
import { renderMinecraftText } from './minecraft-text.js';
import { parseCraftingQuantity } from '../../app/crafting-quantity.js';

export function createCraftingDialog(
    application: ThemeApplication,
    beforeDialog: (source: HTMLElement) => HTMLElement,
    lifetime: ThemeLifetime
) {
    const dialog = element('dialog');
    lifetime.onCleanup(() => {
        dialog.close();
        dialog.remove();
    });
    dialog.className = 'window-frame crafting-dialog';
    dialog.setAttribute('aria-labelledby', 'craft-dialog-title');
    const dialogTitle = element('h2');
    dialogTitle.id = 'craft-dialog-title';
    const close = element('button', '×');
    close.type = 'button';
    close.className = 'close-dialog';
    const heading = element('div');
    heading.className = 'craft-dialog-heading';
    heading.append(dialogTitle, close);
    const product = element('p');
    product.className = 'craft-product';
    const productIcons = application.icons.observe(dialog, paintResourceIcon);
    const craft = element('button');
    craft.type = 'button';
    const order = element('form');
    const quantityLabel = element('label');
    const quantityText = element('span');
    const quantity = element('input');
    quantity.type = 'text';
    quantity.maxLength = 256;
    quantity.value = '1';
    quantity.required = true;
    quantityLabel.append(quantityText, quantity);
    const adjustments = element('div');
    adjustments.className = 'quantity-adjustments';
    let invalidQuantity = '';
    function readQuantity() {
        const value = parseCraftingQuantity(quantity.value);
        quantity.setAttribute('aria-invalid', String(value === null));
        quantity.setCustomValidity(value === null ? invalidQuantity : '');
        return value;
    }
    quantity.addEventListener('input', () => {
        quantity.setCustomValidity('');
        quantity.removeAttribute('aria-invalid');
    });
    for (const delta of [-1000, -64, -1, 1, 64, 1000]) {
        const button = element('button', `${delta > 0 ? '+' : ''}${delta}`);
        button.type = 'button';
        button.addEventListener('click', () => {
            const value = readQuantity();
            if (value !== null && Number.isSafeInteger(value + delta))
                quantity.value = String(Math.max(1, value + delta));
            else {
                quantity.setAttribute('aria-invalid', 'true');
                quantity.setCustomValidity(invalidQuantity);
                quantity.reportValidity();
            }
        });
        adjustments.append(button);
    }
    const calculate = element('button');
    calculate.type = 'submit';
    const orderMessage = element('p');
    orderMessage.role = 'status';
    const automatic = element('input');
    automatic.type = 'checkbox';
    const automaticText = element('span');
    const automaticLabel = element('label');
    automaticLabel.className = 'checkbox';
    automaticLabel.append(automatic, automaticText);
    const lightMode = element('input');
    lightMode.type = 'checkbox';
    const lightText = element('span');
    const lightLabel = element('label');
    lightLabel.className = 'checkbox';
    lightLabel.append(lightMode, lightText);
    const ignoreMissing = element('input');
    ignoreMissing.type = 'checkbox';
    const missingText = element('span');
    const missingLabel = element('label');
    missingLabel.className = 'checkbox';
    missingLabel.append(ignoreMissing, missingText);
    order.append(quantityLabel, adjustments, automaticLabel, lightLabel, missingLabel, calculate, orderMessage);
    const terminalMessage = element('p');
    terminalMessage.role = 'status';
    const orderControls = element('div');
    orderControls.append(craft, terminalMessage);

    let item: StoredResource;
    let opener: HTMLElement | null = null;
    let gridKey: string | null = null;
    let submitted = false;
    function open(selected: StoredResource, source: HTMLElement) {
        if (!selected.craftable || !selected.itemKey || application.state.route.view !== 'items') return;
        item = selected;
        gridKey = application.state.route.gridKey;
        opener = beforeDialog(source);
        quantity.value = '1';
        quantity.setCustomValidity('');
        quantity.removeAttribute('aria-invalid');
        automatic.checked = false;
        lightMode.checked = false;
        ignoreMissing.checked = false;
        submitted = false;
        product.replaceChildren(createResourceIcon(), renderMinecraftText(selected.displayName));
        if (!dialog.open) dialog.showModal();
        productIcons.update([{ element: product, icon: selected.icon }], application.state.itemIcons);
        terminalMessage.hidden = true;
        quantity.focus();
        quantity.select();
    }
    craft.addEventListener('click', () => open(item, craft));
    close.addEventListener('click', () => dialog.close());
    dialog.addEventListener('close', () => {
        productIcons.update([], null);
        terminalMessage.hidden = !terminalMessage.textContent;
        if (
            opener?.isConnected &&
            application.state.route.view === 'items' &&
            application.state.route.gridKey === gridKey
        )
            opener.focus({ preventScroll: true });
    });
    dialog.append(heading, product, order);
    document.body.append(dialog);
    order.addEventListener('submit', (event) => {
        event.preventDefault();
        const amount = readQuantity();
        if (amount === null) {
            quantity.reportValidity();
            return;
        }
        submitted = true;
        void application.crafting.create(
            item,
            amount,
            automatic.checked,
            application.state.capabilities.craftingLightMode === true && lightMode.checked,
            application.state.capabilities.craftingIgnoreMissing === true && ignoreMissing.checked
        );
    });

    return {
        open,
        order(selected: StoredResource, state: ThemeState['crafting'], locale: Translator) {
            if (!dialog.open) item = selected;
            craft.textContent = locale.common('craft');
            dialogTitle.textContent = locale.common('craft');
            close.setAttribute('aria-label', locale.common('close'));
            quantityText.textContent = locale.common('craftQuantity');
            invalidQuantity = locale.common('invalidCraftQuantity');
            automaticText.textContent = locale.common('autostart');
            lightText.textContent = locale.common('craftingLightMode');
            lightLabel.hidden = application.state.capabilities.craftingLightMode !== true;
            missingText.textContent = locale.common('ignoreMissing');
            missingLabel.hidden = application.state.capabilities.craftingIgnoreMissing !== true;
            calculate.textContent = locale.common('calculatePlan');
            calculate.disabled = !!state.mutation || !!state.uncertain || state.status === 'calculating';
            orderMessage.textContent = '';
            terminalMessage.textContent = '';
            if (state.uncertain) {
                orderMessage.textContent = locale.common('uncertainCreate');
                terminalMessage.textContent = orderMessage.textContent;
            } else if (state.error) {
                orderMessage.textContent = locale.common(state.error);
                terminalMessage.textContent = orderMessage.textContent;
            } else if (state.mutation || ['calculating', 'submitted'].includes(state.status)) {
                terminalMessage.textContent = locale.common(state.mutation ? 'calculating' : state.status);
            }
            terminalMessage.hidden = dialog.open || !terminalMessage.textContent;
            return orderControls;
        },
        render(terminal: ThemeState, enabled: boolean) {
            const { route, crafting: state } = terminal;
            if (dialog.open && submitted && ['calculating', 'submitted'].includes(state.status)) dialog.close();
            if (route.view !== 'items' || route.gridKey !== gridKey || terminal.itemStatus === 'error') {
                opener = null;
                if (dialog.open) dialog.close();
                product.replaceChildren();
            }
            dialog.classList.toggle('resource-icons-enabled', enabled);
            productIcons.update(
                dialog.open
                    ? [
                          {
                              element: product,
                              icon: terminal.items.find((resource) => resource.itemKey === item?.itemKey)?.icon
                          }
                      ]
                    : [],
                terminal.itemIcons
            );
        },
        dispose() {
            opener = null;
            dialog.close();
            dialog.remove();
            productIcons.dispose();
        }
    };
}
