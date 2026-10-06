import type { TerminalState, createTerminal } from '../../app/terminal.js';
import type { AccessSource } from '../../app/api-types.js';
import type { Translator as Locale } from '../../app/i18n.js';
import { networkNameError } from '../../app/settings.js';
import { networkIcon } from './icons/hackernoon/chart-network.js';
import { networkLabel } from './network.js';
type Terminal = ReturnType<typeof createTerminal>;

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
    identity.append(element('code'));
    metadata.append(ownerLabel, owner, identityLabel, identity);
    information.append(networkTitle, metadata);
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
    const save = element('button');
    save.type = 'submit';
    const actions = element('div');
    actions.className = 'settings-actions';
    actions.append(save);
    form.append(settingsTitle, nameLabel, nameHelp, label, actions);
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
                    locale.common('position', { dimension: source.position.dimid, ...source.position }) +
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
        const person = { details, name, count, list, entries: [] as AccessSource[] };
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
            nameText.textContent = t('networkName');
            nameHelp.textContent = t('networkNameHelp');
            settingsTitle.textContent = t('gridSettings');
            save.textContent = t('saveSettings');
            const dirty =
                state.current &&
                (state.draft.isTracked !== state.current.isTracked || state.draft.name.trim() !== state.current.name);
            status.textContent = state.saving
                ? t('settingsSaving')
                : state.uncertain
                  ? t('settingsUnconfirmed')
                  : state.error
                    ? t(state.error)
                    : state.notice
                      ? t(state.notice)
                      : state.status === 'loading'
                        ? t('loading')
                        : dirty
                          ? t('settingsDraft')
                          : '';
            if (state.uncertain && state.error) status.textContent += ` ${t(state.error)}`;
            status.hidden = !status.textContent;
            information.hidden = state.status !== 'ready' || !grid;
            networkTitle.textContent = grid ? networkLabel({ ...grid, name: state.current?.name || '' }, locale) : '';
            ownerLabel.textContent = t('owner');
            owner.textContent = grid?.owner || t('unknownOwner');
            identityLabel.textContent = t('networkIdentifier');
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
            tracking.checked = state.draft.isTracked;
            tracking.disabled = state.saving || state.status !== 'ready';
            save.disabled = state.saving || state.status !== 'ready' || !!error || (!state.uncertain && !dirty);
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
