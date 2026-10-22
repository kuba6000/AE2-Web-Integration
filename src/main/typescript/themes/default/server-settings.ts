import type { ServerConfiguration, SettingRange, ThemeApplication, ThemeState } from '../../app/theme-api.js';
import type { Translator } from '../../app/i18n.js';
import type { TooltipBindings } from './tooltip-bindings.js';
import { element } from './dom.js';
import { infoCircle } from './icons/hackernoon/info-circle.js';

type Draft = NonNullable<ThemeState['serverSettings']['draft']>;

export function createServerSettingsView(root: HTMLElement, application: ThemeApplication, tooltips: TooltipBindings) {
    const view = element('section', '', 'page-view server-settings-view');
    view.id = 'server-settings';
    view.hidden = true;
    const heading = element('div', '', 'terminal-heading');
    const title = element('h2');
    title.id = 'server-settings-title';
    heading.append(title);
    const body = element('div', '', 'terminal-body');
    const scroll = element('div', '', 'terminal-scroll page-scroll');
    scroll.role = 'region';
    scroll.tabIndex = 0;
    scroll.setAttribute('aria-labelledby', title.id);
    const intro = element('p', '', 'hint');
    const form = element('form', '', 'server-settings-form');
    const status = element('p');
    status.role = 'status';
    const save = element('button');
    save.type = 'submit';
    const reload = element('button');
    reload.type = 'button';
    const actions = element('div', '', 'settings-actions server-settings-actions');
    actions.append(save, reload);
    scroll.append(intro, status, form, actions);
    body.append(scroll);
    view.append(heading, body);
    root.append(view);
    let locale: Translator;
    const labels: { node: HTMLElement; key: string }[] = [];
    const inputs: { input: HTMLInputElement; update(draft: Draft): void }[] = [];
    const validations: (() => void)[] = [];
    const translated = <T extends HTMLElement>(node: T, key: string) => {
        labels.push({ node, key });
        return node;
    };
    function help(target: HTMLElement, key: string) {
        const icon = element('span', '', 'server-setting-help');
        icon.tabIndex = 0;
        icon.role = 'img';
        icon.innerHTML = infoCircle;
        icon.setAttribute('aria-label', key);
        labels.push({ node: icon, key });
        tooltips.bind(icon, () => [element('span', locale.t(key))]);
        target.append(icon);
    }
    function group(key: string, description?: string) {
        const group = element('section', '', 'server-settings-group');
        group.append(translated(element('h3'), key));
        if (description) group.append(translated(element('p', '', 'hint'), description));
        const fields = element('div', '', 'server-settings-fields');
        group.append(fields);
        form.append(group);
        return fields;
    }
    function control<S extends keyof ServerConfiguration, F extends keyof ServerConfiguration[S]>(
        container: HTMLElement,
        section: S,
        field: F,
        key: string,
        type: 'text' | 'password' | 'checkbox' | 'number' = 'text',
        options: { min?: number; max?: number; help?: string } = {}
    ) {
        const wrapper = element('div', '', 'server-setting');
        const label = element('label', '', type === 'checkbox' ? 'checkbox' : '');
        const text = translated(element('span'), key);
        const input = element('input');
        input.type = type;
        input.id = `server-${section}-${String(field)}`;
        label.htmlFor = input.id;
        input.autocomplete = type === 'password' ? 'new-password' : 'off';
        if (type === 'number') {
            input.required = true;
            input.step = '1';
            input.min = String(options.min ?? 0);
            input.max = String(options.max ?? 2147483647);
        }
        if (type === 'checkbox') label.append(input, text);
        else label.append(text, input);
        wrapper.append(label);
        if (options.help) help(wrapper, options.help);
        container.append(wrapper);
        input.addEventListener(type === 'checkbox' ? 'change' : 'input', () => {
            const value = type === 'checkbox' ? input.checked : type === 'number' ? input.valueAsNumber : input.value;
            application.serverSettings.edit(section, field, value as ServerConfiguration[S][F]);
        });
        inputs.push({
            input,
            update(draft) {
                const value = draft[section][field];
                if (type === 'checkbox') input.checked = value === true;
                else if (document.activeElement !== input) {
                    const text = typeof value === 'number' && !Number.isFinite(value) ? '' : String(value);
                    if (input.value !== text) input.value = text;
                }
            }
        });
    }
    function range(
        container: HTMLElement,
        id: string,
        key: string,
        read: (draft: Draft) => Readonly<SettingRange>,
        write: (value: SettingRange) => void
    ) {
        const fieldset = element('fieldset', '', 'server-settings-range');
        fieldset.append(translated(element('legend'), key));
        const fields = element('div');
        const controls = (['min', 'defaultValue', 'max'] as const).map((part) => {
            const label = element('label');
            const input = element('input');
            input.type = 'number';
            input.required = true;
            input.min = '1';
            input.max = '2147483647';
            input.step = '1';
            input.id = `server-${id}-${part}`;
            label.htmlFor = input.id;
            label.append(translated(element('span'), `serverBound${part}`), input);
            fields.append(label);
            input.addEventListener('input', () => {
                const draft = application.state.serverSettings.draft;
                if (!draft) return;
                write({ ...read(draft), [part]: input.valueAsNumber });
            });
            inputs.push({
                input,
                update(draft) {
                    const value = read(draft)[part];
                    if (document.activeElement !== input) input.value = Number.isFinite(value) ? String(value) : '';
                }
            });
            return input;
        });
        validations.push(() => {
            const [min, value, max] = controls.map((input) => input.valueAsNumber);
            const valid = min <= value && value <= max;
            for (const input of controls) {
                input.setCustomValidity(valid ? '' : locale.t('serverBoundsInvalid'));
                input.setAttribute('aria-invalid', String(!valid));
            }
        });
        fieldset.append(fields);
        container.append(fieldset);
    }

    const general = group('serverGeneral');
    control(general, 'general', 'publicMode', 'serverPublicMode', 'checkbox', { help: 'serverPublicModeHelp' });
    control(general, 'general', 'allowNoPasswordOnLocalhost', 'serverLocalhost', 'checkbox', {
        help: 'serverLocalhostHelp'
    });
    control(general, 'general', 'checkForUpdates', 'serverUpdates', 'checkbox');
    control(general, 'general', 'maxRequestsBeforeLoggedInPerMinute', 'serverLoginRate', 'number', {
        min: 1,
        max: 1000
    });
    control(general, 'general', 'trustedProxies', 'serverProxies', 'text', { help: 'serverProxiesHelp' });
    control(general, 'general', 'defaultTheme', 'serverDefaultTheme', 'text', { help: 'serverDefaultThemeHelp' });
    const notifications = group('serverNotifications', 'serverNotificationsHelp');
    control(notifications, 'notifications', 'minimumCraftingDurationSeconds', 'serverMinimumDuration', 'number');
    control(notifications, 'notifications', 'minimumCraftingAmount', 'serverMinimumAmount', 'number');
    control(notifications, 'notifications', 'fullDomain', 'serverWebAddress', 'text', { help: 'serverWebAddressHelp' });
    const discord = group('serverDiscord', 'serverDiscordHelp');
    control(discord, 'discord', 'webhook', 'serverWebhook', 'password');
    control(discord, 'discord', 'roleId', 'serverRole');
    const ntfy = group('serverNtfy', 'serverNtfyHelp');
    control(ntfy, 'ntfy', 'host', 'serverNtfyHost');
    control(ntfy, 'ntfy', 'topic', 'serverNtfyTopic');
    control(ntfy, 'ntfy', 'user', 'serverNtfyUser');
    control(ntfy, 'ntfy', 'password', 'serverNtfyPassword', 'password');
    const history = group('serverResourceHistory', 'serverHistoryHelp');
    range(
        history,
        'stock-interval',
        'serverStockInterval',
        (draft) => draft.resourceHistory.stockSampleIntervalSeconds,
        (value) => application.serverSettings.edit('resourceHistory', 'stockSampleIntervalSeconds', value)
    );
    range(
        history,
        'flow-interval',
        'serverFlowInterval',
        (draft) => draft.resourceHistory.flowFlushIntervalSeconds,
        (value) => application.serverSettings.edit('resourceHistory', 'flowFlushIntervalSeconds', value)
    );
    range(
        history,
        'resource-retention',
        'serverRetention',
        (draft) => draft.resourceHistory.historyRetentionDays,
        (value) => application.serverSettings.edit('resourceHistory', 'historyRetentionDays', value)
    );
    control(history, 'resourceHistory', 'historyMaxRowsPerGrid', 'serverRecordCap', 'number', {
        min: 1,
        help: 'serverRecordCapHelp'
    });
    const crafting = group('serverCraftingHistory', 'serverCraftingHistoryHelp');
    range(
        crafting,
        'crafting-retention',
        'serverRetention',
        (draft) => draft.craftingHistory.historyRetentionDays,
        (value) => application.serverSettings.edit('craftingHistory', 'historyRetentionDays', value)
    );
    control(crafting, 'craftingHistory', 'historyMaxJobsPerGrid', 'serverJobCap', 'number', { min: 1 });
    const advanced = group('serverAdvanced', 'serverAdvancedHelp');
    control(advanced, 'resourceTracking', 'activeIntervalMillis', 'serverActiveInterval', 'number', { min: 50 });
    control(advanced, 'resourceTracking', 'reconciliationIntervalSeconds', 'serverReconciliation', 'number', {
        min: 1
    });
    control(advanced, 'resourceTracking', 'maxResourcesPerGrid', 'serverResourceCap', 'number', { min: 1 });
    control(advanced, 'resourceTracking', 'maxQueuedRows', 'serverQueueCap', 'number', { min: 1 });
    // The actions remain visible when loading failed; only the form is hidden without confirmed data.
    save.addEventListener('click', () => form.requestSubmit());
    form.addEventListener('submit', (event) => {
        event.preventDefault();
        void application.serverSettings.save();
    });
    reload.addEventListener('click', () => {
        void application.serverSettings.reload();
    });

    return {
        render(state: ThemeState, nextLocale: Translator) {
            view.hidden = state.route.view !== 'server-settings';
            if (view.hidden) return;
            locale = nextLocale;
            const config = state.serverSettings;
            title.textContent = locale.common('serverSettings');
            intro.textContent = locale.t('serverSettingsIntro');
            for (const { node, key } of labels) {
                if (node.hasAttribute('aria-label')) node.setAttribute('aria-label', locale.t(key));
                else node.textContent = locale.t(key);
            }
            form.hidden = !config.draft;
            for (const control of inputs) {
                control.input.disabled = config.saving || config.status !== 'ready';
                if (config.draft) control.update(config.draft);
                else {
                    control.input.value = '';
                    control.input.checked = false;
                }
            }
            if (config.draft) for (const validate of validations) validate();
            let message = '';
            if (config.saving) message = locale.common('settingsSaving');
            else if (config.uncertain) message = locale.t('serverSaveUnconfirmed');
            else if (config.error === 'CONFIG_CHANGED') message = locale.t('serverConflict');
            else if (config.error === 'NO_PERMISSIONS') message = locale.t('serverAccessDenied');
            else if (config.error) message = locale.common(config.error);
            else if (config.status === 'loading') message = locale.common('loading');
            else if (config.saved) message = locale.t('serverSaved');
            else if (config.dirty) message = locale.common('settingsDraft');
            status.textContent = message;
            status.hidden = !message;
            status.classList.toggle('api-error', !!config.error || config.uncertain);
            save.textContent = locale.t('serverSave');
            save.disabled =
                !config.dirty ||
                config.saving ||
                config.status !== 'ready' ||
                config.uncertain ||
                config.error === 'CONFIG_CHANGED';
            save.hidden = !config.draft;
            reload.textContent = locale.t(config.dirty ? 'serverDiscardReload' : 'serverReload');
            reload.disabled = config.saving || config.status === 'loading';
            reload.hidden = config.error === 'NO_PERMISSIONS';
        }
    };
}
