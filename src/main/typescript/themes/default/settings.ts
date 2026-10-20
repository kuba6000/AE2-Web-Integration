import type { ThemeState as TerminalState, ThemeApplication as Terminal } from '../../app/theme-api.js';
import type { AccessSource, HistorySetting } from '../../app/api-types.js';
import type { Translator as Locale } from '../../app/i18n.js';
import { historySettingFields, networkNameError, settingRangeError } from '../../app/settings.js';
import { networkIcon } from './icons/hackernoon/chart-network.js';
import { networkLabel } from './network.js';

function element<Tag extends keyof HTMLElementTagNameMap>(tag: Tag, text = ''): HTMLElementTagNameMap[Tag] {
    const node = document.createElement(tag);
    node.textContent = text;
    return node;
}
const kinds: Record<string, string> = {
    controller: 'accessController',
    wireless_access_point: 'accessWireless',
    terminal: 'accessTerminal',
    security_terminal: 'accessSecurity'
};
const reasons: Record<string, string> = {
    node_owner: 'accessBlockOwner',
    security_owner: 'accessSecurityOwner',
    security_card: 'accessCard'
};

export function createSettingsView(root: HTMLElement, application: Terminal) {
    const view = element('section');
    view.hidden = true;
    view.className = 'settings-view';
    const heading = element('div');
    heading.className = 'terminal-heading';
    const title = element('h2');
    title.innerHTML = `${networkIcon}<span></span>`;
    heading.append(title);
    const body = element('div');
    body.className = 'terminal-body';
    const scroll = element('div');
    scroll.className = 'terminal-scroll network-details-scroll';
    scroll.role = 'region';
    scroll.tabIndex = 0;
    body.append(scroll);
    const status = element('p');
    status.role = 'status';
    const information = element('section');
    information.className = 'network-information';
    const networkTitle = element('h3');
    const metadata = element('dl');
    const ownerLabel = element('dt');
    const owner = element('dd');
    const identityLabel = element('dt');
    const identity = element('dd');
    const identityHelp = element('p');
    identityHelp.className = 'hint';
    identity.append(element('code'));
    metadata.append(ownerLabel, owner, identityLabel, identity);
    information.append(networkTitle, metadata, identityHelp);
    const access = element('section');
    const accessTitle = element('h3');
    const accessHelp = element('p');
    accessHelp.className = 'hint';
    const sources = element('div');
    sources.className = 'network-access inset-frame';
    const emptySources = element('p');
    access.append(accessTitle, accessHelp, sources, emptySources);
    const settingsTitle = element('h3');
    const form = element('form');
    const nameLabel = element('label');
    const nameText = element('span');
    const name = element('input');
    name.type = 'text';
    name.id = 'network-name';
    name.autocomplete = 'off';
    name.setAttribute('aria-describedby', 'network-name-help');
    nameLabel.append(nameText, name);
    const nameHelp = element('p');
    nameHelp.id = 'network-name-help';
    nameHelp.className = 'hint';
    const label = element('label');
    label.className = 'checkbox';
    const tracking = element('input');
    tracking.type = 'checkbox';
    tracking.id = 'network-tracking';
    const labelText = element('span');
    label.append(tracking, labelText);
    const machineLabel = element('label');
    machineLabel.className = 'checkbox';
    const machineTracking = element('input');
    machineTracking.type = 'checkbox';
    machineTracking.id = 'network-machine-tracking';
    const machineText = element('span');
    machineLabel.append(machineTracking, machineText);
    machineTracking.addEventListener('change', () =>
        application.settings.edit('trackMachineCrafting', machineTracking.checked)
    );
    const storageLabel = element('label');
    storageLabel.className = 'checkbox';
    const storageTracking = element('input');
    storageTracking.type = 'checkbox';
    storageTracking.id = 'network-storage-tracking';
    const storageText = element('span');
    storageLabel.append(storageTracking, storageText);
    storageTracking.addEventListener('change', () =>
        application.settings.edit('isStorageTracked', storageTracking.checked)
    );
    const flowLabel = element('label');
    flowLabel.className = 'checkbox';
    const flowTracking = element('input');
    flowTracking.type = 'checkbox';
    flowTracking.id = 'network-flow-tracking';
    const flowText = element('span');
    flowLabel.append(flowTracking, flowText);
    flowTracking.addEventListener('change', () => application.settings.edit('isFlowTracked', flowTracking.checked));
    const intervals = element('div');
    intervals.className = 'network-tracking-controls';
    function intervalControl(field: HistorySetting, id: string, labelKey: string, unitMillis: number) {
        const container = element('div');
        container.className = 'network-policy-control';
        const label = element('label');
        label.htmlFor = `${id}-range`;
        label.id = `${id}-label`;
        const value = element('output');
        value.htmlFor = `${id}-range ${id}`;
        const heading = element('div');
        heading.className = 'network-policy-heading';
        heading.append(label, value);
        const range = element('input');
        range.type = 'range';
        range.id = `${id}-range`;
        range.step = '1';
        range.setAttribute('aria-labelledby', label.id);
        const number = element('input');
        number.type = 'number';
        number.id = id;
        number.step = '1';
        const unit = element('span');
        unit.id = `${id}-unit`;
        number.setAttribute('aria-labelledby', `${label.id} ${unit.id}`);
        const exact = element('div');
        exact.className = 'network-policy-exact';
        exact.append(number, unit);
        const controls = element('div');
        controls.className = 'network-policy-inputs';
        controls.append(range, exact);
        const help = element('p');
        help.className = 'hint';
        help.id = `${id}-help`;
        range.setAttribute('aria-describedby', help.id);
        number.setAttribute('aria-describedby', help.id);
        range.addEventListener('input', () => application.settings.edit(field, range.valueAsNumber));
        number.addEventListener('input', () => application.settings.edit(field, number.valueAsNumber));
        container.append(heading, controls, help);
        intervals.append(container);
        return { field, labelKey, unitMillis, label, value, range, number, unit, help };
    }
    const intervalControls = [
        intervalControl('stockSampleIntervalSeconds', 'network-stock-interval', 'stockSampleInterval', 1000),
        intervalControl('flowFlushIntervalSeconds', 'network-flow-interval', 'flowFlushInterval', 1000),
        intervalControl('historyRetentionDays', 'network-history-retention', 'historyRetention', 86400000)
    ];
    const save = element('button');
    save.type = 'submit';
    const actions = element('div');
    actions.className = 'settings-actions';
    actions.append(save);
    form.append(settingsTitle, nameLabel, nameHelp, label, machineLabel, flowLabel, storageLabel, intervals, actions);
    name.addEventListener('input', () => application.settings.edit('name', name.value));
    tracking.addEventListener('change', () => application.settings.edit('isTracked', tracking.checked));
    form.addEventListener('submit', (event) => {
        event.preventDefault();
        void application.settings.save();
    });
    scroll.append(status, information, access, form);
    view.append(heading, body);
    root.append(view);
    type Person = ReturnType<typeof createPerson>;
    const people = new Map<string, Person>();
    let locale: Locale;
    let selectedGrid: string | null = null;
    function renderSources(person: Person) {
        if (!person.details.open) {
            person.list.replaceChildren();
            return;
        }
        person.list.replaceChildren();
        for (const source of person.entries) {
            const row = element('li');
            const description = element('div');
            description.className = 'network-source-heading';
            description.append(
                element('strong', locale.common(kinds[source.kind] || source.kind)),
                element('span', locale.common(reasons[source.reason] || source.reason))
            );
            row.append(
                description,
                element(
                    'code',
                    locale.common('position', { dimension: source.position.dimensionId, ...source.position }) +
                        (source.side ? ` · ${locale.common('accessSide', { side: source.side })}` : '')
                )
            );
            person.list.append(row);
        }
    }
    function createPerson(uuid: string) {
        const details = element('details');
        details.className = 'network-person';
        const summary = element('summary');
        const name = element('strong');
        const count = element('span');
        count.className = 'network-source-count';
        const labels = element('span');
        labels.className = 'network-person-labels';
        labels.append(name, count);
        summary.append(labels);
        const identity = element('div');
        identity.className = 'network-person-identity';
        identity.append(element('span', 'UUID'), element('code', uuid));
        const list = element('ul');
        details.append(summary, identity, list);
        const person = { details, name, count, list, entries: [] as readonly AccessSource[] };
        details.addEventListener('toggle', () => renderSources(person));
        return person;
    }
    return {
        render(applicationState: TerminalState, nextLocale: Locale) {
            const { route, settings: state } = applicationState;
            view.hidden = route.view !== 'settings';
            if (route.view !== 'settings') return;
            const localeChanged = locale !== nextLocale;
            locale = nextLocale;
            const { common: t } = locale;
            if (selectedGrid !== route.gridKey) {
                people.clear();
                sources.replaceChildren();
                scroll.scrollTop = 0;
            }
            selectedGrid = route.gridKey;
            const grid = applicationState.grids.find((grid) => grid.key === route.gridKey);
            title.querySelector('span')!.textContent = t('networkDetails');
            scroll.setAttribute('aria-label', t('networkDetails'));
            labelText.textContent = t('recordHistory');
            machineText.textContent = t('trackMachineCrafting');
            storageText.textContent = t('recordResourceHistory');
            flowText.textContent = t('recordFlowHistory');
            nameText.textContent = t('networkName');
            nameHelp.textContent = t('networkNameHelp');
            settingsTitle.textContent = t('gridSettings');
            save.textContent = t('saveSettings');
            const dirty =
                state.current &&
                (state.draft.isTracked !== state.current.isTracked ||
                    state.draft.trackMachineCrafting !== state.current.trackMachineCrafting ||
                    state.draft.isStorageTracked !== state.current.isStorageTracked ||
                    state.draft.isFlowTracked !== state.current.isFlowTracked ||
                    historySettingFields.some((field) => state.draft[field] !== state.current![field]) ||
                    state.draft.name.trim() !== state.current.name);
            status.textContent = '';
            if (state.saving) status.textContent = t('settingsSaving');
            else if (state.uncertain) status.textContent = t('settingsUnconfirmed');
            else if (state.error) status.textContent = t(state.error);
            else if (state.status === 'loading') status.textContent = t('loading');
            else if (dirty) status.textContent = t('settingsDraft');
            if (state.uncertain && state.error) status.textContent += ` ${t(state.error)}`;
            status.hidden = !status.textContent;
            status.classList.toggle('api-error', !!state.error || state.uncertain);
            information.hidden = state.status !== 'ready' || !grid;
            networkTitle.textContent = grid ? networkLabel({ ...grid, name: state.current?.name || '' }) : '';
            ownerLabel.textContent = t('owner');
            owner.textContent = grid?.owner || t('unknownOwner');
            identityLabel.textContent = t('networkIdentifier');
            identityHelp.textContent = t('networkIdentifierHelp');
            identity.querySelector('code')!.textContent = route.gridKey;
            form.hidden = state.current === null;
            if (form.hidden) {
                if (status.parentNode !== scroll) scroll.prepend(status);
            } else if (status.parentNode !== actions) actions.append(status);
            if (name.value !== state.draft.name) name.value = state.draft.name;
            name.disabled = state.saving || state.status !== 'ready';
            const error = networkNameError(state.draft.name);
            name.setCustomValidity(error ? t(error) : '');
            name.setAttribute('aria-invalid', String(!!error));
            nameHelp.textContent = t(error || 'networkNameHelp');
            storageTracking.checked = state.draft.isStorageTracked;
            storageTracking.disabled = state.saving || state.status !== 'ready';
            flowTracking.checked = state.draft.isFlowTracked;
            flowTracking.disabled = state.saving || state.status !== 'ready';
            let invalidInterval = false;
            for (const control of intervalControls) {
                const { field, range, number, unitMillis } = control;
                const limits = state.limits?.[field];
                const value = state.draft[field];
                control.label.textContent = t(control.labelKey);
                control.unit.textContent = t(field === 'historyRetentionDays' ? 'historyDays' : 'historySeconds');
                const duration = Number.isFinite(value) ? locale.duration(value * unitMillis) : '—';
                control.value.textContent = duration;
                range.setAttribute('aria-valuetext', duration);
                if (limits) {
                    for (const input of [range, number]) {
                        input.min = String(limits.min);
                        input.max = String(limits.max);
                    }
                    control.help.textContent = t('historySettingBounds', {
                        min: locale.duration(limits.min * unitMillis),
                        max: locale.duration(limits.max * unitMillis),
                        default: locale.duration(limits.defaultValue * unitMillis)
                    });
                    if (field === 'historyRetentionDays' && state.limits)
                        control.help.textContent += ` ${t('historyRetentionCap', { count: state.limits.historyMaxRowsPerGrid })}`;
                }
                if (Number.isFinite(value)) range.value = String(value);
                if (document.activeElement !== number) number.value = Number.isFinite(value) ? String(value) : '';
                let disabled = state.saving || state.status !== 'ready' || !limits;
                if (field === 'stockSampleIntervalSeconds') disabled ||= !state.draft.isStorageTracked;
                if (field === 'flowFlushIntervalSeconds') disabled ||= !state.draft.isFlowTracked;
                range.disabled = disabled;
                number.disabled = disabled;
                const intervalError = limits ? settingRangeError(value, limits) : null;
                number.setCustomValidity(intervalError ? t(intervalError) : '');
                number.setAttribute('aria-invalid', String(!!intervalError));
                invalidInterval ||= !!intervalError;
            }
            tracking.checked = state.draft.isTracked;
            tracking.disabled = state.saving || state.status !== 'ready';
            machineTracking.checked = state.draft.trackMachineCrafting;
            machineTracking.disabled = state.saving || state.status !== 'ready' || !state.draft.isTracked;
            save.disabled =
                state.saving || state.status !== 'ready' || !!error || invalidInterval || (!state.uncertain && !dirty);
            accessTitle.textContent = t('explicitAccess');
            accessHelp.textContent = t('explicitAccessHelp');
            access.hidden = state.status !== 'ready';
            emptySources.textContent = t('noAccessSources');
            const groups = Object.entries(state.sources).filter(([, entries]) => entries.length);
            emptySources.hidden = !!groups.length;
            const retained = new Set(groups.map(([uuid]) => uuid));
            for (const [uuid, person] of people)
                if (!retained.has(uuid)) {
                    person.details.remove();
                    people.delete(uuid);
                }
            for (const [uuid, entries] of groups) {
                const person = people.get(uuid) || createPerson(uuid);
                people.set(uuid, person);
                person.name.textContent = entries[0].player.name;
                person.count.textContent = t('accessSourceCount', { count: entries.length });
                if (person.entries !== entries || localeChanged) {
                    person.entries = entries;
                    renderSources(person);
                }
                if (!person.details.parentNode) sources.append(person.details);
            }
        }
    };
}
