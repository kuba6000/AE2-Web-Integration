import type { ThemeApplication, ThemeState } from '../../app/theme-api.js';
import type { Translator } from '../../app/i18n.js';
import type { StandingOrderStatusName } from '../../app/api-types.js';
import type { IconTarget } from '../../app/icons.js';
import { cpuHref, standingOrdersHref } from '../../app/router.js';
import { reasonMessageKey } from '../../app/standing-order-model.js';
import { statusExplanation } from '../../app/standing-orders.js';
import { element } from './dom.js';
import { craftingHammer } from './icons/pixel/terminal.js';
import { createResourceIcon, inlineResourceName, paintResourceIcon, resourceMessage } from './resource-icon.js';
import { renderMinecraftText } from './minecraft-text.js';
import { createStandingOrderEditor } from './standing-order-editor.js';

type Tone = 'active' | 'waiting' | 'problem' | 'off';
/** How the status chip is colored; the label comes from the status itself. */
const tones: Record<StandingOrderStatusName, Tone> = {
    IDLE: 'active',
    CALCULATING: 'active',
    CRAFTING: 'active',
    COOLDOWN: 'active',
    CRAFTING_LONG: 'waiting',
    QUEUED: 'waiting',
    WAITING_FOR_STOCK: 'waiting',
    WAITING_FOR_CPU: 'waiting',
    BLOCKED_MISSING: 'problem',
    SUBMIT_FAILED: 'problem',
    UNAVAILABLE: 'problem',
    GRID_UNAVAILABLE: 'problem',
    DISABLED: 'off',
    SWITCHED_OFF: 'off'
};

function createRow() {
    const li = element('li', '', 'standing-order');
    const head = element('div', '', 'standing-order-head');
    const name = element('h3');
    const chip = element('a', '', 'standing-order-status');
    head.append(name, chip);
    const target = element('div', '', 'standing-order-target');
    const targetName = element('strong');
    target.append(createResourceIcon(), targetName);
    const message = element('p', '', 'standing-order-message');
    // The crafting system's own words, shown as they came.
    const submitError = element('p', '', 'standing-order-message');
    const missing = element('ul', '', 'standing-order-missing');
    const detail = element('p', '', 'hint standing-order-detail');
    const toggleLabel = element('label', '', 'checkbox');
    const toggle = element('input');
    toggle.type = 'checkbox';
    const toggleText = element('span');
    toggleLabel.append(toggle, toggleText);
    const run = element('button');
    run.type = 'button';
    const edit = element('a', '', 'button-link');
    const remove = element('button');
    remove.type = 'button';
    const actions = element('div', '', 'standing-order-buttons');
    actions.append(toggleLabel, run, edit, remove);
    li.append(head, target, message, submitError, missing, detail, actions);
    return {
        resourceMessageKey: '',
        messageIcons: [] as IconTarget[],
        li,
        name,
        chip,
        target,
        targetName,
        message,
        submitError,
        missing,
        detail,
        toggle,
        toggleText,
        toggleLabel,
        run,
        edit,
        remove
    };
}

/** The network's standing orders: the list with its controls, and the editor that takes its place. */
export function createStandingOrdersView(root: HTMLElement, application: ThemeApplication) {
    const view = element('section', '', 'settings-view standing-orders-view');
    view.hidden = true;
    const heading = element('div', '', 'terminal-heading');
    const title = element('h2');
    title.innerHTML = `${craftingHammer}<span></span>`;
    const create = element('a', '', 'button-link');
    heading.append(title, create);
    const body = element('div', '', 'terminal-body');
    const scroll = element('div', '', 'terminal-scroll network-details-scroll');
    scroll.role = 'region';
    scroll.tabIndex = 0;
    body.append(scroll);
    const status = element('p', '', 'hint');
    status.role = 'status';
    const notices = element('div', '', 'standing-order-notices');
    const cadence = element('p', '', 'hint');
    const cadenceLink = element('a');
    const empty = element('p', '', 'hint');
    const list = element('ul', '', 'standing-order-list inset-frame');
    let editorMetadata: ThemeState['itemIcons'] = null;
    let savedMetadata: ThemeState['standingOrders']['icons'] = null;
    let editor: ReturnType<typeof createStandingOrderEditor> | undefined;
    let editorKey: string | null = null;
    scroll.append(status, notices, cadence, empty, list);
    view.append(heading, body);
    root.append(view);
    const listIcons = application.icons.observe(list, paintResourceIcon);
    // Modal results render in the top layer, outside the scrolling panel.
    const editorIcons = application.icons.observe(null, paintResourceIcon);
    const savedEditorIcons = application.icons.observe(scroll, paintResourceIcon);
    const rows = new Map<string, ReturnType<typeof createRow>>();
    let previous = '';

    function renderEditor(state: ThemeState, locale: Translator, icons: boolean) {
        const model = state.standingOrders.editor;
        const key = model ? JSON.stringify([state.route.gridKey, model.order, model.status === 'ready']) : null;
        if (key !== editorKey) {
            editor?.dispose();
            editor?.element.remove();
            editor = undefined;
            editorKey = key;
            if (model) {
                const form = createStandingOrderEditor(application, updateEditorIcons);
                editor = form;
                scroll.append(editor.element);
            }
        }
        if (model && editor) editor.render(state, model, locale, icons);
        updateEditorIcons();
    }
    function updateEditorIcons() {
        savedEditorIcons.update(editor?.targets(true) ?? [], savedMetadata);
        editorIcons.update(editor?.targets(false) ?? [], editorMetadata);
    }
    function clearList() {
        if (rows.size) {
            rows.clear();
            list.replaceChildren();
        }
        listIcons.update([], null);
    }
    function notice(text: string, problem: boolean) {
        notices.append(element('p', text, problem ? 'api-error' : 'hint'));
    }
    function updateRow(
        row: ReturnType<typeof createRow>,
        order: ThemeState['standingOrders']['orders'][number],
        state: ThemeState,
        locale: Translator
    ) {
        const orders = state.standingOrders;
        const { common: t } = locale;
        const definition = order.definition;
        const runtime = order.status;
        const label = definition?.name ?? t('standingOrderUnreadableName');
        row.name.textContent = definition ? definition.name : t('standingOrderUnreadableName');
        const statusName = runtime?.status;
        const tone = definition ? (statusName ? (tones[statusName] ?? 'waiting') : 'waiting') : 'problem';
        row.chip.dataset.tone = tone;
        row.chip.textContent = definition
            ? statusName
                ? tones[statusName]
                    ? t(`standingOrderStatus_${statusName}`)
                    : statusName
                : t('standingOrderStatus_PENDING')
            : t('standingOrderStatus_UNAVAILABLE');
        if (definition && runtime?.activeCpuKey && (statusName === 'CRAFTING' || statusName === 'CRAFTING_LONG'))
            row.chip.href = cpuHref(state.route.gridKey, runtime.activeCpuKey);
        else row.chip.removeAttribute('href');
        const resource = definition ? orders.resources[definition.action.resource] : undefined;
        row.target.hidden = !definition;
        if (definition) {
            row.targetName.replaceChildren(
                resource ? renderMinecraftText(resource.displayName) : t('standingOrderUnknownResource')
            );
            row.target.dataset.resource = definition.action.resource;
        }
        const explained = definition && runtime ? statusExplanation(runtime, orders.resources, locale) : null;
        const reason =
            !definition && order.invalid
                ? t('standingOrderUnreadable', { reason: t(reasonMessageKey(order.invalid.reason)) })
                : (explained?.reason ?? '');
        const ambiguous =
            runtime?.reason === 'AMBIGUOUS_RESOURCE' && runtime.resource ? orders.resources[runtime.resource] : null;
        const shortfalls = runtime?.missing ?? [];
        // Keep the same atlas targets when a live update changes only the timestamps or order controls.
        const messageKey = JSON.stringify([reason, ambiguous, shortfalls, state.preferences.language]);
        if (messageKey !== row.resourceMessageKey) {
            row.resourceMessageKey = messageKey;
            row.messageIcons = [];
            if (ambiguous) {
                const label = inlineResourceName(ambiguous.displayName);
                row.message.replaceChildren(
                    resourceMessage(locale, 'standingOrderStatusReason_AMBIGUOUS_RESOURCE', { resource: label })
                );
                row.messageIcons.push({ element: label, icon: ambiguous.icon });
            } else row.message.textContent = reason;
            row.message.hidden = !reason;
            row.missing.replaceChildren(
                ...shortfalls.map((entry) => {
                    const label = inlineResourceName(entry.displayName);
                    const line = element('li');
                    line.append(
                        resourceMessage(locale, 'standingOrderMissingIngredient', { amount: entry.amount, name: label })
                    );
                    row.messageIcons.push({ element: label, icon: entry.icon });
                    return line;
                })
            );
        }
        row.submitError.textContent = explained?.submitError ?? '';
        row.submitError.hidden = !row.submitError.textContent;
        row.missing.setAttribute('aria-label', t('standingOrderMissingList'));
        row.missing.hidden = !shortfalls.length;
        const facts: string[] = [];
        if (runtime) {
            if (runtime.lastSubmittedAmount > 0)
                facts.push(t('standingOrderLastSubmitted', { amount: locale.number(runtime.lastSubmittedAmount) }));
            if (runtime.craftingSince > 0)
                facts.push(
                    t('standingOrderCraftingSince', { time: locale.dateTime(runtime.craftingSince, Date.now()) })
                );
            if (runtime.nextCheckAt > Date.now())
                facts.push(t('standingOrderNextCheck', { time: locale.dateTime(runtime.nextCheckAt, Date.now()) }));
        }
        row.detail.textContent = facts.join(' · ');
        row.detail.hidden = !facts.length;
        const editable = orders.canEdit && !orders.pending;
        row.toggleLabel.hidden = !definition || !orders.canEdit;
        // While a change is in flight the box keeps what was clicked; the answer or a refusal then settles it.
        if (!orders.pending) row.toggle.checked = definition?.enabled ?? false;
        // With the server switch off an order can still be switched off, but not on.
        row.toggle.disabled = !editable || (!orders.serverEnabled && !definition?.enabled);
        row.toggleText.textContent = t('standingOrderToggle');
        row.toggle.setAttribute('aria-label', `${t('standingOrderToggle')}: ${label}`);
        row.run.hidden = !definition?.enabled || !orders.canEdit || !orders.serverEnabled;
        row.run.disabled = !editable || !orders.gridEnabled;
        row.run.textContent = t('standingOrderRun');
        row.run.setAttribute('aria-label', `${t('standingOrderRun')}: ${label}`);
        row.edit.hidden = !orders.canEdit;
        row.edit.href = standingOrdersHref(state.route.gridKey, { order: order.orderKey, itemKey: null });
        row.edit.textContent = t(definition ? 'standingOrderEdit' : 'standingOrderRepair');
        row.edit.setAttribute('aria-label', `${row.edit.textContent}: ${label}`);
        row.remove.hidden = !orders.canEdit;
        row.remove.disabled = !editable;
        row.remove.textContent = t('standingOrderDelete');
        row.remove.setAttribute('aria-label', `${t('standingOrderDelete')}: ${label}`);
        row.toggle.onchange = () => void application.standingOrders.setEnabled(order.orderKey, row.toggle.checked);
        row.run.onclick = () => void application.standingOrders.run(order.orderKey);
        row.remove.onclick = () => {
            if (window.confirm(t('standingOrderDeleteConfirm', { name: label })))
                void application.standingOrders.remove(order.orderKey);
        };
    }
    function renderList(state: ThemeState, locale: Translator, icons: boolean) {
        const orders = state.standingOrders;
        const { common: t } = locale;
        const retained = new Set(orders.orders.map((order) => order.orderKey));
        for (const [key, row] of rows)
            if (!retained.has(key)) {
                row.li.remove();
                rows.delete(key);
            }
        orders.orders.forEach((order, index) => {
            let row = rows.get(order.orderKey);
            if (!row) {
                row = createRow();
                rows.set(order.orderKey, row);
            }
            updateRow(row, order, state, locale);
            if (list.children[index] !== row.li) list.insertBefore(row.li, list.children[index] ?? null);
        });
        list.setAttribute('aria-label', t('standingOrderList'));
        listIcons.update(
            icons
                ? orders.orders.flatMap((order) => {
                      const row = rows.get(order.orderKey);
                      const resource = order.definition && orders.resources[order.definition.action.resource];
                      return row
                          ? [...(resource ? [{ element: row.target, icon: resource.icon }] : []), ...row.messageIcons]
                          : [];
                  })
                : [],
            orders.icons
        );
    }
    function renderNotices(state: ThemeState, locale: Translator) {
        const orders = state.standingOrders;
        const { common: t } = locale;
        notices.replaceChildren();
        if (orders.status !== 'ready') return;
        if (!orders.serverEnabled) notice(t('standingOrdersServerOff'), true);
        else if (!orders.gridEnabled) notice(t('standingOrdersGridOff'), true);
        if (!orders.canEdit) notice(t('standingOrdersReadOnly'), false);
        else if (orders.serverEnabled && orders.orders.length >= orders.maxOrders)
            notice(t('standingOrdersLimit', { max: orders.maxOrders }), false);
    }
    return {
        render(state: ThemeState, locale: Translator, icons: boolean) {
            editorMetadata = state.itemIcons;
            savedMetadata = state.standingOrders.icons;
            const { route, standingOrders: orders } = state;
            view.hidden = route.view !== 'standing-orders';
            if (route.view !== 'standing-orders') {
                renderEditor(state, locale, false);
                clearList();
                previous = '';
                return;
            }
            const { common: t } = locale;
            const key = `${route.gridKey}`;
            if (previous !== key) {
                scroll.scrollTop = 0;
                previous = key;
            }
            title.querySelector('span')!.textContent = t('standingOrders');
            scroll.setAttribute('aria-label', t('standingOrders'));
            status.textContent = '';
            if (orders.status === 'error') status.textContent = t(orders.error ?? 'error');
            else if (orders.status === 'loading') status.textContent = t('loading');
            status.hidden = !status.textContent;
            status.classList.toggle('api-error', orders.status === 'error');
            const editing = route.editor !== null;
            create.href = standingOrdersHref(route.gridKey, { order: null, itemKey: null });
            create.textContent = t('newStandingOrder');
            create.hidden =
                editing ||
                orders.status !== 'ready' ||
                !orders.canEdit ||
                !orders.serverEnabled ||
                orders.orders.length >= orders.maxOrders;
            renderNotices(state, locale);
            const settingsHref = `#/grids/${encodeURIComponent(route.gridKey)}/settings`;
            cadence.hidden = editing || orders.status !== 'ready' || orders.checkIntervalSeconds === null;
            if (!cadence.hidden) {
                cadenceLink.href = settingsHref;
                cadenceLink.textContent = t('networkSettingsLink');
                cadence.replaceChildren(
                    t('standingOrderCheckCadence', { interval: locale.duration(orders.checkIntervalSeconds! * 1000) }),
                    ' ',
                    cadenceLink
                );
            }
            const listing = !editing && orders.status === 'ready';
            list.hidden = !listing || !orders.orders.length;
            empty.textContent = t('standingOrdersEmpty');
            empty.hidden = !listing || orders.orders.length > 0;
            if (listing) renderList(state, locale, icons);
            else clearList();
            renderEditor(state, locale, icons);
        },
        dispose() {
            editor?.dispose();
            listIcons.dispose();
            editorIcons.dispose();
            savedEditorIcons.dispose();
        }
    };
}
