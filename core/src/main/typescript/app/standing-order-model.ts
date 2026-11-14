import type {
    ResourceDescription,
    StandingOrderActionInput,
    StandingOrderChanges,
    StandingOrderCondition,
    StandingOrderConditionInput,
    StandingOrderDefinition,
    StandingOrderDefinitionInput,
    StandingOrderRejection
} from './api-types.js';
import { parseCraftingQuantity } from './crafting-quantity.js';

/** Limits of the server's validator; the server remains the authority and answers with the same reasons. */
export const maxNameLength = 128;
export const maxCooldownSeconds = 7 * 24 * 60 * 60;
export const maxIdleCpus = 1024;
/** One group plus a negated row per condition must stay within the server's 32 conditions. */
export const maxConditions = 15;

export type ConditionKind = 'stockBelow' | 'stockAtLeast' | 'idleCpusAtLeast';
/** `value` is the quantity of a stock condition or the CPU count of an idle-CPU condition, as typed. */
export type ConditionDraft = { kind: ConditionKind; resource: string | null; value: string; negate: boolean };
/** All three amounts are kept as typed so that switching between the two kinds loses nothing. */
export type AmountDraft = { type: 'fixed' | 'fillTo'; batch: string; target: string; maxBatch: string };
export type FlatRules = {
    locked: null;
    match: 'all' | 'any';
    conditions: ConditionDraft[];
    /** What the order crafts. */
    resource: string | null;
    amount: AmountDraft;
    lightMode: boolean;
    ignoreMissing: boolean;
    /** The key of the one CPU to use, or null for any. */
    cpu: string | null;
};
/** Conditions and action of a stored order the editor cannot change without altering it. */
export type LockedRules = { locked: 'nested' | 'large' };
export type OrderDraft<Rules extends FlatRules | LockedRules = FlatRules | LockedRules> = {
    name: string;
    enabled: boolean;
    cooldownSeconds: string;
    rules: Rules;
};
export type FlatDraft = OrderDraft<FlatRules>;

export type FieldId =
    | 'name'
    | 'cooldownSeconds'
    | 'trigger'
    | `condition.${number}.resource`
    | `condition.${number}.value`
    | 'action.resource'
    | 'action.amount.batch'
    | 'action.amount.target'
    | 'action.amount.maxBatch'
    | 'action.cpu.key'
    | 'action.options.lightMode'
    | 'action.options.ignoreMissing';
/** Message keys by the editor field they belong to. */
export type FieldErrors = Partial<Record<FieldId, string>>;
export type BuildResult<T> = { ok: true; value: T } | { ok: false; errors: FieldErrors };
/** A chosen resource; only its identity matters to the draft. */
export type ResourceChoice = Pick<ResourceDescription, 'itemKey'> & { itemKey: string };

export type DraftChange =
    | { type: 'name' | 'cooldownSeconds'; value: string }
    | { type: 'enabled' | 'lightMode' | 'ignoreMissing'; value: boolean }
    | { type: 'match'; value: 'all' | 'any' }
    | { type: 'target'; resource: ResourceChoice }
    | { type: 'amountType'; value: 'fixed' | 'fillTo' }
    | { type: 'amount'; field: 'batch' | 'target' | 'maxBatch'; value: string }
    | { type: 'cpu'; key: string | null }
    | { type: 'addCondition' }
    | { type: 'removeCondition'; index: number }
    | { type: 'conditionKind'; index: number; kind: ConditionKind }
    | { type: 'conditionResource'; index: number; resource: ResourceChoice }
    | { type: 'conditionValue'; index: number; value: string }
    | { type: 'conditionNegate'; index: number; value: boolean };

export const isFlat = (draft: OrderDraft): draft is FlatDraft => draft.rules.locked === null;

const stockKinds: readonly string[] = ['stockBelow', 'stockAtLeast'];

/** The template of the "Keep stocked" shortcut: order the resource up to a target whenever its stock runs low. */
export function keepStockedDraft(resource: string | null): FlatDraft {
    return {
        name: '',
        enabled: true,
        cooldownSeconds: '0',
        rules: {
            locked: null,
            match: 'all',
            conditions: [{ kind: 'stockBelow', resource, value: '', negate: false }],
            resource,
            amount: { type: 'fillTo', batch: '', target: '', maxBatch: '' },
            lightMode: false,
            ignoreMissing: false,
            cpu: null
        }
    };
}

function row(condition: StandingOrderCondition): ConditionDraft | null {
    if (condition.type === 'idleCpusAtLeast')
        return condition.count === null
            ? null
            : { kind: condition.type, resource: null, value: String(condition.count), negate: false };
    if (stockKinds.includes(condition.type) && condition.resource !== null && condition.amount !== null)
        return {
            kind: condition.type as ConditionKind,
            resource: condition.resource,
            value: String(condition.amount),
            negate: false
        };
    return null;
}

function flatRows(trigger: StandingOrderCondition): { match: 'all' | 'any'; rows: ConditionDraft[] } | null {
    const single = (condition: StandingOrderCondition): ConditionDraft | null => {
        if (condition.type !== 'not') return row(condition);
        const inner = condition.child && row(condition.child);
        return inner && { ...inner, negate: true };
    };
    if (trigger.type === 'all' || trigger.type === 'any') {
        const rows = (trigger.children ?? []).map(single);
        return rows.length && rows.every((entry) => entry !== null) ? { match: trigger.type, rows } : null;
    }
    const only = single(trigger);
    return only && { match: 'all', rows: [only] };
}

function numbers(condition: StandingOrderCondition): (number | null)[] {
    return [
        condition.amount,
        condition.count,
        ...(condition.children ?? []).flatMap(numbers),
        ...(condition.child ? numbers(condition.child) : [])
    ];
}

/** Whether a stored order can be edited as rows, or why not. */
export function draftFromDefinition(definition: StandingOrderDefinition): OrderDraft {
    const { action } = definition;
    const shared = {
        name: definition.name,
        enabled: definition.enabled,
        cooldownSeconds: String(definition.cooldownSeconds)
    };
    const flat = flatRows(definition.trigger);
    if (!flat) return { ...shared, rules: { locked: 'nested' } };
    const values = [...numbers(definition.trigger), action.amount.batch, action.amount.target, action.amount.maxBatch];
    // JSON parsing has already rounded such values; sending them back would silently change the order.
    if (values.some((value) => value !== null && !Number.isSafeInteger(value)))
        return { ...shared, rules: { locked: 'large' } };
    const text = (value: number | null) => (value === null ? '' : String(value));
    return {
        ...shared,
        rules: {
            locked: null,
            match: flat.match,
            conditions: flat.rows,
            resource: action.resource,
            amount: {
                type: action.amount.type,
                batch: text(action.amount.batch),
                target: text(action.amount.target),
                maxBatch: text(action.amount.maxBatch)
            },
            lightMode: action.options.lightMode,
            ignoreMissing: action.options.ignoreMissing,
            cpu: action.cpu.type === 'specific' ? action.cpu.key : null
        }
    };
}

function edited(rules: FlatRules, change: DraftChange): FlatRules {
    const at = (index: number, update: (condition: ConditionDraft) => ConditionDraft) => ({
        ...rules,
        conditions: rules.conditions.map((condition, position) => (position === index ? update(condition) : condition))
    });
    switch (change.type) {
        case 'match':
            return { ...rules, match: change.value };
        case 'target': {
            const watched = rules.conditions[0];
            const follows =
                watched.kind === 'stockBelow' && (watched.resource === null || watched.resource === rules.resource);
            return {
                ...rules,
                resource: change.resource.itemKey,
                conditions: follows
                    ? [{ ...watched, resource: change.resource.itemKey }, ...rules.conditions.slice(1)]
                    : rules.conditions
            };
        }
        case 'amountType':
            return { ...rules, amount: { ...rules.amount, type: change.value } };
        case 'amount':
            return { ...rules, amount: { ...rules.amount, [change.field]: change.value } };
        case 'lightMode':
        case 'ignoreMissing':
            return { ...rules, [change.type]: change.value };
        case 'cpu':
            return { ...rules, cpu: change.key };
        case 'addCondition':
            return rules.conditions.length >= maxConditions
                ? rules
                : {
                      ...rules,
                      conditions: [
                          ...rules.conditions,
                          { kind: 'stockAtLeast', resource: null, value: '', negate: false }
                      ]
                  };
        case 'removeCondition':
            return rules.conditions.length <= 1
                ? rules
                : { ...rules, conditions: rules.conditions.filter((_, position) => position !== change.index) };
        case 'conditionKind':
            return at(change.index, (condition) => ({
                ...condition,
                kind: change.kind,
                value:
                    (change.kind === 'idleCpusAtLeast') === (condition.kind === 'idleCpusAtLeast')
                        ? condition.value
                        : ''
            }));
        case 'conditionResource':
            return at(change.index, (condition) => ({ ...condition, resource: change.resource.itemKey }));
        case 'conditionValue':
            return at(change.index, (condition) => ({ ...condition, value: change.value }));
        case 'conditionNegate':
            return at(change.index, (condition) => ({ ...condition, negate: change.value }));
        default:
            return rules;
    }
}

/** Applies one editor change; the rules of a locked order accept none. */
export function applyChange(draft: OrderDraft, change: DraftChange): OrderDraft {
    if (change.type === 'name' || change.type === 'cooldownSeconds') return { ...draft, [change.type]: change.value };
    if (change.type === 'enabled') return { ...draft, enabled: change.value };
    return isFlat(draft) ? { ...draft, rules: edited(draft.rules, change) } : draft;
}

const hasControlCharacter = (text: string) =>
    [...text].some((character) => {
        const code = character.charCodeAt(0);
        return code <= 31 || (code >= 127 && code <= 159);
    });

function nameError(name: string) {
    if (!name || name.length > maxNameLength) return 'NAME_LENGTH';
    return hasControlCharacter(name) ? 'NAME_INVALID_CHARACTER' : null;
}

function cooldown(text: string) {
    const value = /^\d+$/.test(text.trim()) ? Number(text.trim()) : NaN;
    return value <= maxCooldownSeconds ? value : null;
}

function idleCount(text: string) {
    const value = /^\d+$/.test(text.trim()) ? Number(text.trim()) : NaN;
    return value >= 1 && value <= maxIdleCpus ? value : null;
}

/** Message keys of errors the editor finds itself; the server's refusals use `reasonMessageKey`. */
export const quantityError = 'INVALID_QUANTITY';
export const resourceError = 'standingOrderResourceRequired';

function triggerInput(rules: FlatRules, errors: FieldErrors): StandingOrderConditionInput | null {
    const inputs = rules.conditions.map((condition, index) => {
        let input: StandingOrderConditionInput | null = null;
        if (condition.kind === 'idleCpusAtLeast') {
            const count = idleCount(condition.value);
            if (count === null) errors[`condition.${index}.value`] = reasonMessageKey('IDLE_CPU_COUNT_OUT_OF_RANGE');
            else input = { type: condition.kind, count };
        } else {
            const amount = parseCraftingQuantity(condition.value);
            if (condition.resource === null) errors[`condition.${index}.resource`] = resourceError;
            if (amount === null) errors[`condition.${index}.value`] = quantityError;
            if (condition.resource !== null && amount !== null)
                input = { type: condition.kind, resource: condition.resource, amount };
        }
        return input && (condition.negate ? ({ type: 'not', child: input } as const) : input);
    });
    if (inputs.some((input) => input === null)) return null;
    const present = inputs as StandingOrderConditionInput[];
    return present.length === 1 ? present[0] : { type: rules.match, children: present };
}

function actionInput(rules: FlatRules, errors: FieldErrors): StandingOrderActionInput | null {
    const start = Object.keys(errors).length;
    if (rules.resource === null) errors['action.resource'] = resourceError;
    let amount: StandingOrderActionInput['amount'] | null = null;
    if (rules.amount.type === 'fixed') {
        const batch = parseCraftingQuantity(rules.amount.batch);
        if (batch === null) errors['action.amount.batch'] = quantityError;
        else amount = { type: 'fixed', batch };
    } else {
        const target = parseCraftingQuantity(rules.amount.target);
        if (target === null) errors['action.amount.target'] = quantityError;
        const typedLargest = rules.amount.maxBatch.trim();
        const largest = typedLargest ? parseCraftingQuantity(typedLargest) : target;
        if (typedLargest && largest === null) errors['action.amount.maxBatch'] = quantityError;
        if (target !== null && largest !== null) amount = { type: 'fillTo', target, maxBatch: largest };
    }
    if (Object.keys(errors).length > start || rules.resource === null || amount === null) return null;
    return {
        resource: rules.resource,
        amount,
        options: { lightMode: rules.lightMode, ignoreMissing: rules.ignoreMissing },
        cpu: rules.cpu === null ? { type: 'any' } : { type: 'specific', key: rules.cpu }
    };
}

function checkedName(draft: OrderDraft, errors: FieldErrors) {
    const name = draft.name.trim();
    const error = nameError(name);
    if (error) errors.name = reasonMessageKey(error);
    return name;
}

function checkedCooldown(draft: OrderDraft, errors: FieldErrors) {
    const seconds = cooldown(draft.cooldownSeconds);
    if (seconds === null) errors.cooldownSeconds = reasonMessageKey('COOLDOWN_OUT_OF_RANGE');
    return seconds;
}

/** Builds the body of a new standing order, or reports every field that is not acceptable yet. */
export function buildDefinition(draft: FlatDraft): BuildResult<StandingOrderDefinitionInput> {
    const errors: FieldErrors = {};
    const name = checkedName(draft, errors);
    const cooldownSeconds = checkedCooldown(draft, errors);
    const trigger = triggerInput(draft.rules, errors);
    const action = actionInput(draft.rules, errors);
    if (Object.keys(errors).length || cooldownSeconds === null || !trigger || !action) return { ok: false, errors };
    return { ok: true, value: { name, enabled: draft.enabled, cooldownSeconds, trigger, action } };
}

const triggerPart = (rules: FlatRules) => [rules.match, rules.conditions];
const actionPart = (rules: FlatRules) => [
    rules.resource,
    rules.amount,
    rules.lightMode,
    rules.ignoreMissing,
    rules.cpu
];

/**
 * Builds a partial edit of the order the baseline describes: only members the draft changed are present, so a
 * rename never resends rules and a locked order's rules are never sent.
 */
export function buildChanges(baseline: OrderDraft, draft: OrderDraft): BuildResult<StandingOrderChanges> {
    const errors: FieldErrors = {};
    const changes: StandingOrderChanges = {};
    const name = checkedName(draft, errors);
    const cooldownSeconds = checkedCooldown(draft, errors);
    if (name !== baseline.name) changes.name = name;
    if (draft.enabled !== baseline.enabled) changes.enabled = draft.enabled;
    if (cooldownSeconds !== null && cooldownSeconds !== cooldown(baseline.cooldownSeconds))
        changes.cooldownSeconds = cooldownSeconds;
    if (isFlat(draft) && isFlat(baseline)) {
        if (JSON.stringify(triggerPart(draft.rules)) !== JSON.stringify(triggerPart(baseline.rules))) {
            const trigger = triggerInput(draft.rules, errors);
            if (trigger) changes.trigger = trigger;
        }
        if (JSON.stringify(actionPart(draft.rules)) !== JSON.stringify(actionPart(baseline.rules))) {
            const action = actionInput(draft.rules, errors);
            if (action) changes.action = action;
        }
    }
    return Object.keys(errors).length ? { ok: false, errors } : { ok: true, value: changes };
}

/** The editor field a rejected member of the submitted document belongs to, or null for the form as a whole. */
export function fieldForPath(path: string, draft: OrderDraft): FieldId | null {
    const fixed = [
        'name',
        'cooldownSeconds',
        'action.resource',
        'action.amount.batch',
        'action.amount.target',
        'action.amount.maxBatch',
        'action.cpu.key',
        'action.options.lightMode',
        'action.options.ignoreMissing'
    ] as const;
    const direct = fixed.find((field) => field === path);
    if (direct) return direct;
    if (path !== 'trigger' && !path.startsWith('trigger.')) return null;
    const member = /^trigger(?:\.children\[(\d+)\])?(?:\.child)?\.(amount|count|resource)$/.exec(path);
    if (!member || !isFlat(draft)) return 'trigger';
    const index = member[1] === undefined ? 0 : Number(member[1]);
    if (index >= draft.rules.conditions.length) return 'trigger';
    return member[2] === 'resource' ? `condition.${index}.resource` : `condition.${index}.value`;
}

const reasons = new Set([
    'MALFORMED_JSON',
    'UNSUPPORTED_VERSION',
    'MISSING_FIELD',
    'UNKNOWN_FIELD',
    'DUPLICATE_FIELD',
    'WRONG_TYPE',
    'UNKNOWN_KIND',
    'INVALID_KEY',
    'INVALID_RESOURCE_SLOT',
    'INVALID_RESOURCE_TABLE',
    'NAME_LENGTH',
    'NAME_NOT_TRIMMED',
    'NAME_INVALID_CHARACTER',
    'COOLDOWN_OUT_OF_RANGE',
    'TREE_TOO_DEEP',
    'TREE_TOO_LARGE',
    'EMPTY_GROUP',
    'AMOUNT_NOT_POSITIVE',
    'IDLE_CPU_COUNT_OUT_OF_RANGE',
    'CAPABILITY_UNAVAILABLE'
]);

/** The message of a refusal reason; a reason this version does not know gets the generic refusal. */
export function reasonMessageKey(reason: string) {
    return reasons.has(reason) ? `standingOrderReason_${reason}` : 'INVALID_STANDING_ORDER';
}

/** Where a server refusal belongs in the editor: a field message, or a message for the form as a whole. */
export function placeRejection(rejection: StandingOrderRejection, draft: OrderDraft) {
    const field = fieldForPath(rejection.path, draft);
    return { field, message: reasonMessageKey(rejection.reason) };
}
