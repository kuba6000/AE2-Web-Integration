/**
 * @typedef {ReturnType<typeof import('../../app/terminal.mjs').createTerminal>} Terminal
 * @typedef {Terminal['state']} TerminalState
 * @typedef {import('../../app/i18n.mjs').Translator} Locale
 */

/**
 * @template {keyof HTMLElementTagNameMap} Tag
 * @param {Tag} tag
 * @param {string} [text]
 * @returns {HTMLElementTagNameMap[Tag]}
 */
function element(tag, text = '') {
    const node = document.createElement(tag);
    node.textContent = text;
    return node;
}

/** @type {Record<string, string>} */
const kinds = {
    controller: 'accessController',
    wireless_access_point: 'accessWireless',
    terminal: 'accessTerminal',
    security_terminal: 'accessSecurity'
};
/** @type {Record<string, string>} */
const reasons = { node_owner: 'accessBlockOwner', security_owner: 'accessSecurityOwner', security_card: 'accessCard' };

/**
 * @param {HTMLElement} root
 * @param {Terminal} application
 */
export function createSettingsView(root, application) {
    const view = element('section');
    view.hidden = true;
    view.className = 'settings-view';
    const title = element('h2');
    const status = element('p');
    status.role = 'status';
    const form = element('form');
    const label = element('label');
    label.className = 'checkbox';
    const tracking = element('input');
    tracking.type = 'checkbox';
    const labelText = element('span');
    label.append(tracking, labelText);
    const save = element('button');
    save.type = 'submit';
    form.append(label, save);
    tracking.addEventListener('change', () => application.settings.edit(tracking.checked));
    form.addEventListener('submit', (event) => {
        event.preventDefault();
        application.settings.save();
    });
    const accessTitle = element('h3');
    const sources = element('div');
    const back = element('a');
    view.append(title, status, form, accessTitle, sources, back);
    root.append(view);
    /** @type {TerminalState['settings']['sources'] | undefined} */
    let lastSources;
    /** @type {Locale | undefined} */
    let lastLocale;
    return {
        /**
         * @param {TerminalState['route']} route
         * @param {TerminalState['settings']} state
         * @param {Locale} locale
         */
        render(route, state, locale) {
            view.hidden = route.view !== 'settings';
            if (route.view !== 'settings') return;
            const { common: t } = locale;
            title.textContent = t('gridSettings');
            labelText.textContent = t('recordHistory');
            save.textContent = t('saveSettings');
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
                        : state.draft !== state.current
                          ? t('settingsDraft')
                          : '';
            if (state.uncertain && state.error) status.textContent += ` ${t(state.error)}`;
            form.hidden = state.current === null;
            tracking.checked = state.draft;
            tracking.disabled = state.saving || state.status !== 'ready';
            save.disabled =
                state.saving || state.status !== 'ready' || (!state.uncertain && state.draft === state.current);
            accessTitle.textContent = t('accessSources');
            back.textContent = t('backResources');
            back.href = `#/grids/${encodeURIComponent(route.gridKey)}/items`;
            accessTitle.hidden = state.status !== 'ready';
            sources.hidden = state.status !== 'ready';
            if (lastSources === state.sources && lastLocale === locale) return;
            lastSources = state.sources;
            lastLocale = locale;
            sources.replaceChildren();
            const groups = Object.entries(state.sources).filter(([, entries]) => entries.length);
            if (!groups.length) sources.append(element('p', t('noAccessSources')));
            for (const [uuid, entries] of groups) {
                const group = element('section');
                group.append(element('h4', entries[0].player.name), element('code', uuid));
                const list = element('ul');
                for (const source of entries) {
                    const row = element('li');
                    const kind = kinds[source.kind] ? t(kinds[source.kind]) : source.kind;
                    const reason = reasons[source.reason] ? t(reasons[source.reason]) : source.reason;
                    row.append(
                        element('p', `${kind} · ${reason}`),
                        element(
                            'code',
                            t('position', {
                                dimension: source.position.dimid,
                                x: source.position.x,
                                y: source.position.y,
                                z: source.position.z
                            }) + (source.side ? ` · ${t('accessSide', { side: source.side })}` : '')
                        )
                    );
                    list.append(row);
                }
                group.append(list);
                sources.append(group);
            }
        }
    };
}
