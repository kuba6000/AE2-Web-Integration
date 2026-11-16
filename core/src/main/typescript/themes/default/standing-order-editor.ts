import type { ConditionKind, FieldId, ReadonlyData, ThemeApplication, ThemeState } from '../../app/theme-api.js';
import type { Translator } from '../../app/i18n.js';
import type { IconTarget } from '../../app/icons.js';
import type { StandingOrderCondition, StandingOrderDefinition } from '../../app/api-types.js';
import { plainMinecraftText } from '../../app/minecraft-text.js';
import { standingOrdersHref } from '../../app/router.js';
import { maxConditions, reasonMessageKey } from '../../app/standing-order-model.js';
import { element } from './dom.js';
import { createResourcePicker, type ResourceChoice } from './resource-picker.js';

type Resources = ThemeState['standingOrders']['resources'];
let nextId = 0;

const kinds: { kind: ConditionKind; label: string }[] = [
    { kind: 'stockBelow', label: 'conditionStockBelow' },
    { kind: 'stockAtLeast', label: 'conditionStockAtLeast' },
    { kind: 'idleCpusAtLeast', label: 'conditionIdleCpus' }
];

/** A control with its label and the message of the field's current error. */
function field(control: HTMLElement, className = '') {
    const id = `standing-order-field-${nextId++}`;
    const text = element('span');
    text.id = `${id}-label`;
    const error = element('p', '', 'standing-order-error');
    error.id = `${id}-error`;
    error.hidden = true;
    const wrap = element('div', '', `standing-order-field ${className}`.trim());
    return { wrap, text, error, control, id };
}
type Field = ReturnType<typeof field>;

function labelledField(control: HTMLInputElement | HTMLSelectElement, className = '') {
    const result = field(control, className);
    const label = element('label');
    label.append(result.text, control);
    result.wrap.append(label, result.error);
    return result;
}

function setError(target: Field, message: string | undefined, controls = [target.control]) {
    target.error.hidden = !message;
    target.error.textContent = message ?? '';
    for (const control of controls) {
        control.setAttribute('aria-invalid', String(!!message));
        if (message) control.setAttribute('aria-describedby', target.error.id);
        else control.removeAttribute('aria-describedby');
    }
}

function option(value: string, text: string) {
    const node = element('option', text);
    node.value = value;
    return node;
}

function numberText(value: number | null, locale: Translator) {
    return value === null || !Number.isSafeInteger(value)
        ? locale.common('standingOrderLargeAmount')
        : locale.number(value);
}

function resourceName(key: string | null, resources: Resources, locale: Translator) {
    const description = key === null ? undefined : resources[key];
    return description ? plainMinecraftText(description.displayName) : locale.common('standingOrderUnknownResource');
}

function conditionSummary(condition: ReadonlyData<StandingOrderCondition>, resources: Resources, locale: Translator) {
    const item = element('li');
    const { common: t } = locale;
    if (condition.type === 'stockBelow' || condition.type === 'stockAtLeast') {
        item.textContent = t(
            condition.type === 'stockBelow' ? 'standingOrderSummaryBelow' : 'standingOrderSummaryAtLeast',
            {
                resource: resourceName(condition.resource, resources, locale),
                amount: numberText(condition.amount, locale)
            }
        );
    } else if (condition.type === 'idleCpusAtLeast') {
        item.textContent = t('standingOrderSummaryIdle', { count: condition.count ?? 0 });
    } else {
        const children = condition.type === 'not' ? [condition.child] : (condition.children ?? []);
        const group = { all: 'standingOrderGroupAll', any: 'standingOrderGroupAny', not: 'standingOrderGroupNot' };
        const list = element('ul');
        for (const child of children) if (child) list.append(conditionSummary(child, resources, locale));
        item.append(t(group[condition.type]), list);
    }
    return item;
}

/** The stored order, as text, for orders whose rules the editor cannot change. */
function ruleSummary(definition: ReadonlyData<StandingOrderDefinition>, resources: Resources, locale: Translator) {
    const { common: t } = locale;
    const { action } = definition;
    const triggers = element('ul');
    triggers.append(conditionSummary(definition.trigger, resources, locale));
    const amount = action.amount;
    const craft = element(
        'p',
        amount.type === 'fixed'
            ? t('standingOrderSummaryFixed', {
                  resource: resourceName(action.resource, resources, locale),
                  amount: numberText(amount.batch, locale)
              })
            : t('standingOrderSummaryFillTo', {
                  resource: resourceName(action.resource, resources, locale),
                  target: numberText(amount.target, locale),
                  maxBatch: numberText(amount.maxBatch, locale)
              })
    );
    const details = [craft];
    const options = [
        ...(action.options.lightMode ? [t('craftingLightMode')] : []),
        ...(action.options.ignoreMissing ? [t('ignoreMissing')] : [])
    ];
    if (options.length) details.push(element('p', `${t('standingOrderOptions')}: ${options.join(', ')}`));
    if (action.cpu.type === 'specific')
        details.push(element('p', t('standingOrderSummaryCpu', { cpu: action.cpu.key ?? '' })));
    return [triggers, ...details];
}

/** The form for creating, editing and repairing one standing order. */
export function createStandingOrderEditor(application: ThemeApplication, iconsChanged: () => void) {
    const form = element('form', '', 'standing-order-editor');
    form.noValidate = true;
    form.hidden = true;
    const edit = application.standingOrders.edit;

    const heading = element('div', '', 'standing-order-editor-heading');
    const back = element('a', '', 'button-link');
    const title = element('h3');
    heading.append(back, title);
    const status = element('p', '', 'hint');
    status.role = 'status';
    const failure = element('p', '', 'api-error');
    failure.role = 'alert';
    const notice = element('p', '', 'hint');

    const name = element('input');
    name.type = 'text';
    name.autocomplete = 'off';
    const nameField = labelledField(name);
    name.addEventListener('input', () => edit({ type: 'name', value: name.value }));
    const enabled = element('input');
    enabled.type = 'checkbox';
    const enabledLabel = element('label', '', 'checkbox');
    const enabledText = element('span');
    enabledLabel.append(enabled, enabledText);
    enabled.addEventListener('change', () => edit({ type: 'enabled', value: enabled.checked }));

    const whenSet = element('fieldset', '', 'standing-order-section');
    const whenLegend = element('legend');
    const match = element('select');
    match.append(option('all', ''), option('any', ''));
    const matchField = labelledField(match);
    match.addEventListener('change', () => edit({ type: 'match', value: match.value === 'any' ? 'any' : 'all' }));
    const triggerError = element('p', '', 'standing-order-error');
    triggerError.hidden = true;
    const rowList = element('div', '', 'standing-order-conditions');
    const add = element('button');
    add.type = 'button';
    add.addEventListener('click', () => edit({ type: 'addCondition' }));
    whenSet.append(whenLegend, matchField.wrap, rowList, triggerError, add);

    function createRow() {
        const row = {
            index: 0,
            group: element('fieldset', '', 'standing-order-condition'),
            legend: element('legend'),
            kind: element('select'),
            value: element('input'),
            negate: element('input'),
            negateText: element('span'),
            remove: element('button', '', 'standing-order-remove')
        };
        for (const { kind, label } of kinds) row.kind.append(option(kind, label));
        const kindField = labelledField(row.kind);
        row.value.type = 'text';
        row.value.autocomplete = 'off';
        row.value.inputMode = 'numeric';
        const valueField = labelledField(row.value, 'standing-order-value');
        row.negate.type = 'checkbox';
        const negateLabel = element('label', '', 'checkbox');
        negateLabel.append(row.negate, row.negateText);
        row.remove.type = 'button';
        const picker = createResourcePicker(
            (item) => edit({ type: 'conditionResource', index: row.index, resource: { itemKey: item.itemKey! } }, item),
            { craftableOnly: false, retry: () => void application.refresh(), changed: iconsChanged }
        );
        const resourceField = field(picker.choice, 'standing-order-condition-resource');
        resourceField.wrap.append(resourceField.text, picker.element, resourceField.error);
        row.group.append(row.legend, kindField.wrap, resourceField.wrap, valueField.wrap, negateLabel, row.remove);
        row.kind.addEventListener('change', () =>
            edit({ type: 'conditionKind', index: row.index, kind: row.kind.value as ConditionKind })
        );
        row.value.addEventListener('input', () =>
            edit({ type: 'conditionValue', index: row.index, value: row.value.value })
        );
        row.negate.addEventListener('change', () =>
            edit({ type: 'conditionNegate', index: row.index, value: row.negate.checked })
        );
        row.remove.addEventListener('click', () => edit({ type: 'removeCondition', index: row.index }));
        return Object.assign(row, { kindField, valueField, resourceField, picker });
    }
    const rows: ReturnType<typeof createRow>[] = [];

    const craftSet = element('fieldset', '', 'standing-order-section');
    const craftLegend = element('legend');
    const target = createResourcePicker(
        (item) => edit({ type: 'target', resource: { itemKey: item.itemKey! } }, item),
        { craftableOnly: true, retry: () => void application.refresh(), changed: iconsChanged }
    );
    const targetField = field(target.choice);
    targetField.wrap.append(targetField.text, target.element, targetField.error);
    const amountType = element('select');
    amountType.append(option('fillTo', ''), option('fixed', ''));
    const amountTypeField = labelledField(amountType);
    amountType.addEventListener('change', () =>
        edit({ type: 'amountType', value: amountType.value === 'fixed' ? 'fixed' : 'fillTo' })
    );
    function amountInput(fieldName: 'batch' | 'target' | 'maxBatch') {
        const input = element('input');
        input.type = 'text';
        input.autocomplete = 'off';
        input.inputMode = 'numeric';
        const result = labelledField(input, 'standing-order-value');
        input.addEventListener('input', () => edit({ type: 'amount', field: fieldName, value: input.value }));
        return { input, ...result };
    }
    const batch = amountInput('batch');
    const fillTarget = amountInput('target');
    const maxBatch = amountInput('maxBatch');
    const maxBatchHelp = element('p', '', 'hint');
    maxBatch.wrap.append(maxBatchHelp);
    const optionsGroup = element('div', '', 'standing-order-options');
    const optionsText = element('span', '', 'standing-order-options-title');
    function optionBox(change: 'lightMode' | 'ignoreMissing') {
        const input = element('input');
        input.type = 'checkbox';
        const text = element('span');
        const label = element('label', '', 'checkbox');
        label.append(input, text);
        input.addEventListener('change', () => edit({ type: change, value: input.checked }));
        const owner = field(input);
        owner.wrap.append(label, owner.error);
        return { input, text, owner };
    }
    const lightMode = optionBox('lightMode');
    const ignoreMissing = optionBox('ignoreMissing');
    optionsGroup.append(optionsText, lightMode.owner.wrap, ignoreMissing.owner.wrap);
    const cpu = element('select');
    const cpuField = labelledField(cpu);
    const cpuHelp = element('p', '', 'hint');
    cpuField.wrap.append(cpuHelp);
    cpu.addEventListener('change', () => edit({ type: 'cpu', key: cpu.value || null }));
    craftSet.append(
        craftLegend,
        targetField.wrap,
        amountTypeField.wrap,
        batch.wrap,
        fillTarget.wrap,
        maxBatch.wrap,
        optionsGroup,
        cpuField.wrap
    );

    const locked = element('section', '', 'standing-order-section standing-order-locked');
    const lockedTitle = element('h4');
    const lockedHelp = element('p', '', 'hint');
    const lockedBody = element('div', '', 'standing-order-summary');
    locked.append(lockedTitle, lockedHelp, lockedBody);

    const scheduleSet = element('fieldset', '', 'standing-order-section');
    const scheduleLegend = element('legend');
    const cooldown = element('input');
    cooldown.type = 'text';
    cooldown.autocomplete = 'off';
    cooldown.inputMode = 'numeric';
    const cooldownField = labelledField(cooldown, 'standing-order-value');
    const cooldownHelp = element('p', '', 'hint');
    cooldownField.wrap.append(cooldownHelp);
    cooldown.addEventListener('input', () => edit({ type: 'cooldownSeconds', value: cooldown.value }));
    scheduleSet.append(scheduleLegend, cooldownField.wrap);

    const save = element('button');
    save.type = 'submit';
    const cancel = element('a', '', 'button-link');
    const actions = element('div', '', 'settings-actions standing-order-actions');
    actions.append(save, cancel);
    form.append(
        heading,
        status,
        failure,
        notice,
        nameField.wrap,
        enabledLabel,
        whenSet,
        craftSet,
        locked,
        scheduleSet,
        actions
    );
    form.addEventListener('submit', (event) => {
        event.preventDefault();
        void application.standingOrders.save();
    });

    const errorTargets = new Map<string, { target: Field; controls: HTMLElement[] }>();
    let shownErrors = '';
    let editorKey = '';
    /** Unsaved changes, and the question that guards leaving them; both follow the last render. */
    let unsaved = false;
    let leaveQuestion = '';
    /** An in-app link away from an editor with unsaved changes asks first; saving navigates without a click. */
    function confirmLeaving(event: MouseEvent) {
        if (!unsaved || event.defaultPrevented || event.button !== 0) return;
        // A new tab or window leaves the editor where it is.
        if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
        const link = (event.target as Element | null)?.closest?.('a[href^="#"]');
        if (!link || link.hasAttribute('target') || link.getAttribute('href') === location.hash) return;
        if (!window.confirm(leaveQuestion)) event.preventDefault();
    }
    function warnBeforeUnload(event: BeforeUnloadEvent) {
        if (unsaved) event.preventDefault();
    }
    document.addEventListener('click', confirmLeaving, true);
    window.addEventListener('beforeunload', warnBeforeUnload);

    /** Closing a picker also removes its document listener. */
    function closePickers() {
        target.close();
        for (const row of rows) row.picker.close();
    }
    let visiblePickers: ReturnType<typeof createResourcePicker>[] = [];
    const targets = (): IconTarget[] => visiblePickers.flatMap((picker) => picker.targets());
    function render(state: ThemeState, locale: Translator, iconsOn: boolean): IconTarget[] {
        visiblePickers = [];
        const orders = state.standingOrders;
        const editor = orders.editor;
        form.hidden = !editor;
        unsaved = !!editor && editor.status === 'ready' && editor.dirty && !editor.saving;
        leaveQuestion = locale.common('standingOrderLeaveConfirm');
        if (!editor) {
            closePickers();
            editorKey = '';
            return [];
        }
        const { common: t } = locale;
        const gridKey = state.route.gridKey!;
        const key = `${gridKey}\0${editor.order ?? ''}`;
        if (key !== editorKey) {
            editorKey = key;
            shownErrors = '';
        }
        const listHref = standingOrdersHref(gridKey);
        back.href = listHref;
        back.textContent = t('standingOrderBack');
        cancel.href = listHref;
        cancel.textContent = t('standingOrderCancel');
        let heading = t('newStandingOrder');
        if (editor.order !== null) heading = t(editor.invalid ? 'standingOrderRepairTitle' : 'standingOrderEditTitle');
        title.textContent = heading;
        const ready = editor.status === 'ready';
        status.textContent = '';
        if (editor.status === 'loading' && orders.status !== 'error') status.textContent = t('loading');
        else if (editor.status === 'missing') status.textContent = t('standingOrderMissing');
        else if (editor.saving) status.textContent = t('standingOrderSaving');
        status.hidden = !status.textContent;
        failure.textContent = editor.formError ? t(editor.formError) : '';
        failure.hidden = !editor.formError;
        notice.textContent = '';
        if (ready && editor.invalid)
            notice.textContent = t('standingOrderRepairHelp', { reason: t(reasonMessageKey(editor.invalid.reason)) });
        else if (ready && !orders.canEdit) notice.textContent = t('standingOrdersReadOnly');
        else if (ready && editor.order === null) notice.textContent = `${t('keepStocked')}: ${t('keepStockedHelp')}`;
        notice.hidden = !notice.textContent;
        for (const part of [nameField.wrap, enabledLabel, whenSet, craftSet, scheduleSet, actions])
            part.hidden = !ready;
        if (!ready) {
            locked.hidden = true;
            return [];
        }
        const editable = orders.canEdit && (orders.serverEnabled || editor.order !== null) && !editor.saving;
        const draft = editor.draft;
        const rules = draft.rules;
        const flat = rules.locked === null;
        const errors = editor.errors as Partial<Record<FieldId, string>>;

        nameField.text.textContent = t('standingOrderName');
        name.placeholder = t('standingOrderNameExample');
        if (name.value !== draft.name) name.value = draft.name;
        enabledText.textContent = t('standingOrderEnabledField');
        enabled.checked = draft.enabled;
        cooldownField.text.textContent = t('standingOrderCooldown');
        scheduleLegend.textContent = t('standingOrderSchedule');
        if (cooldown.value !== draft.cooldownSeconds) cooldown.value = draft.cooldownSeconds;
        cooldownHelp.textContent = t('standingOrderCooldownHelp');
        save.textContent = t('standingOrderSave');

        const resourceChoice: ResourceChoice = {
            items: state.items,
            itemsReady: state.itemStatus === 'ready',
            itemsError: state.itemStatus === 'error' ? (state.itemError ?? 'error') : null,
            byKey: state.itemsByKey,
            known: orders.resources,
            locale,
            language: state.preferences.language
        };
        errorTargets.clear();
        errorTargets.set('name', { target: nameField, controls: [name] });
        errorTargets.set('cooldownSeconds', { target: cooldownField, controls: [cooldown] });

        whenSet.hidden = !flat;
        craftSet.hidden = !flat;
        locked.hidden = flat;
        if (rules.locked === null) {
            whenLegend.textContent = t('standingOrderWhen');
            craftLegend.textContent = t('standingOrderThenCraft');
            matchField.text.textContent = t('standingOrderMatch');
            match.options[0].textContent = t('standingOrderMatchAll');
            match.options[1].textContent = t('standingOrderMatchAny');
            match.value = rules.match;
            matchField.wrap.hidden = rules.conditions.length < 2;
            add.textContent = t('standingOrderAddCondition');
            add.disabled = !editable || rules.conditions.length >= maxConditions;
            while (rows.length < rules.conditions.length) {
                const row = createRow();
                rows.push(row);
                rowList.append(row.group);
            }
            while (rows.length > rules.conditions.length) {
                const row = rows.pop()!;
                row.picker.close();
                row.group.remove();
            }
            rules.conditions.forEach((condition, index) => {
                const row = rows[index];
                const idle = condition.kind === 'idleCpusAtLeast';
                row.index = index;
                row.legend.textContent = t('standingOrderCondition', { index: index + 1 });
                row.kindField.text.textContent = t('standingOrderConditionKind');
                kinds.forEach(({ label }, position) => (row.kind.options[position].textContent = t(label)));
                row.kind.value = condition.kind;
                row.valueField.text.textContent = t(
                    idle ? 'standingOrderConditionCpuCount' : 'standingOrderConditionAmount'
                );
                if (row.value.value !== condition.value) row.value.value = condition.value;
                row.negate.checked = condition.negate;
                row.negateText.textContent = t('standingOrderConditionNegate');
                row.resourceField.text.textContent = t('standingOrderResource');
                row.resourceField.wrap.hidden = idle;
                row.picker.render(
                    resourceChoice,
                    condition.resource,
                    !!errors[`condition.${index}.resource`],
                    t('standingOrderResource')
                );
                row.remove.textContent = t('standingOrderRemoveCondition', { index: index + 1 });
                row.remove.hidden = rules.conditions.length < 2;
                for (const control of [row.kind, row.value, row.negate, row.remove, row.picker.choice])
                    control.disabled = !editable;
                errorTargets.set(`condition.${index}.resource`, {
                    target: row.resourceField,
                    controls: [row.picker.choice]
                });
                errorTargets.set(`condition.${index}.value`, { target: row.valueField, controls: [row.value] });
                if (!idle && iconsOn) visiblePickers.push(row.picker);
            });

            targetField.text.textContent = t('standingOrderTarget');
            target.render(resourceChoice, rules.resource, !!errors['action.resource'], t('standingOrderTarget'));
            target.choice.disabled = !editable;
            if (iconsOn) visiblePickers.push(target);
            errorTargets.set('action.resource', { target: targetField, controls: [target.choice] });
            amountTypeField.text.textContent = t('standingOrderAmountKind');
            amountType.options[0].textContent = t('standingOrderAmountFillTo');
            amountType.options[1].textContent = t('standingOrderAmountFixed');
            amountType.value = rules.amount.type;
            const fixed = rules.amount.type === 'fixed';
            batch.wrap.hidden = !fixed;
            fillTarget.wrap.hidden = fixed;
            maxBatch.wrap.hidden = fixed;
            batch.text.textContent = t('standingOrderBatch');
            fillTarget.text.textContent = t('standingOrderFillTarget');
            maxBatch.text.textContent = t('standingOrderMaxBatch');
            maxBatchHelp.textContent = t('standingOrderMaxBatchHelp');
            for (const [input, value] of [
                [batch.input, rules.amount.batch],
                [fillTarget.input, rules.amount.target],
                [maxBatch.input, rules.amount.maxBatch]
            ] as const)
                if (input.value !== value) input.value = value;
            errorTargets.set('action.amount.batch', { target: batch, controls: [batch.input] });
            errorTargets.set('action.amount.target', { target: fillTarget, controls: [fillTarget.input] });
            errorTargets.set('action.amount.maxBatch', { target: maxBatch, controls: [maxBatch.input] });

            const light = state.capabilities.craftingLightMode === true || rules.lightMode;
            const ignore = state.capabilities.craftingIgnoreMissing === true || rules.ignoreMissing;
            optionsGroup.hidden = !light && !ignore;
            optionsText.textContent = t('standingOrderOptions');
            lightMode.owner.wrap.hidden = !light;
            ignoreMissing.owner.wrap.hidden = !ignore;
            lightMode.text.textContent = t('craftingLightMode');
            ignoreMissing.text.textContent = t('ignoreMissing');
            lightMode.input.checked = rules.lightMode;
            ignoreMissing.input.checked = rules.ignoreMissing;
            lightMode.input.disabled = !editable;
            ignoreMissing.input.disabled = !editable;

            cpuField.text.textContent = t('standingOrderCpu');
            cpuHelp.textContent = t('standingOrderCpuHelp');
            const choices = orders.cpus.filter((entry) => entry.eligible);
            const wanted = [
                ['', t('standingOrderCpuAny')],
                ...choices.map((entry) => [entry.key, `${entry.name || t('cpuUnnamed')} · ${entry.key.slice(0, 6)}`]),
                ...(rules.cpu !== null && !choices.some((entry) => entry.key === rules.cpu)
                    ? [[rules.cpu, `${t('standingOrderCpuMissing')} · ${rules.cpu.slice(0, 6)}`]]
                    : [])
            ] as [string, string][];
            if (
                cpu.options.length !== wanted.length ||
                wanted.some(
                    ([value, text], index) => cpu.options[index].value !== value || cpu.options[index].text !== text
                )
            )
                cpu.replaceChildren(...wanted.map(([value, text]) => option(value, text)));
            cpu.value = rules.cpu ?? '';
            cpu.disabled = !editable;
            errorTargets.set('action.cpu.key', { target: cpuField, controls: [cpu] });
            errorTargets.set('action.options.lightMode', { target: lightMode.owner, controls: [lightMode.input] });
            errorTargets.set('action.options.ignoreMissing', {
                target: ignoreMissing.owner,
                controls: [ignoreMissing.input]
            });
        } else if (editor.definition) {
            lockedTitle.textContent = t('standingOrderRules');
            lockedHelp.textContent = t(
                rules.locked === 'nested' ? 'standingOrderLockedNested' : 'standingOrderLockedLarge'
            );
            lockedBody.replaceChildren(...ruleSummary(editor.definition, orders.resources, locale));
        }
        for (const control of [
            name,
            enabled,
            cooldown,
            amountType,
            match,
            batch.input,
            fillTarget.input,
            maxBatch.input
        ])
            control.disabled = !editable;
        // With the server switch off an order can be switched off but not on.
        enabled.disabled = !editable || (!orders.serverEnabled && !draft.enabled && editor.baseline?.enabled !== true);
        save.disabled = !editable;
        triggerError.hidden = !errors.trigger;
        triggerError.textContent = errors.trigger ? t(errors.trigger) : '';
        for (const [id, { target: owner, controls }] of errorTargets) {
            const message = errors[id as FieldId];
            setError(owner, message ? t(message) : undefined, controls);
        }
        const signature = JSON.stringify(errors);
        if (signature !== shownErrors) {
            shownErrors = signature;
            const first = Object.keys(errors).find((id) => id === 'trigger' || errorTargets.has(id));
            const firstTarget = first && errorTargets.get(first)?.controls[0];
            if (firstTarget) firstTarget.focus();
            else if (first === 'trigger') add.focus();
        }
        return targets();
    }
    return {
        element: form,
        render,
        targets,
        dispose() {
            closePickers();
            document.removeEventListener('click', confirmLeaving, true);
            window.removeEventListener('beforeunload', warnBeforeUnload);
        }
    };
}
