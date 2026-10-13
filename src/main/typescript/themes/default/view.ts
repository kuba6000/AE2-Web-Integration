import { createResourceTerminal, resourceMetadata } from './resource-terminal.js';
import { createTooltips } from './tooltips.js';
import { createWebSettings, type TerminalDisplay } from './web-settings.js';
import { createNotices } from './notices.js';
import { createNotifications } from './notifications.js';
import { createHeader } from './header.js';
import { translations } from './translations.js';
import { createMobilePanels, type MobilePane } from './mobile-panels.js';
import { filterIcon } from './icons/hackernoon/filter.js';
import { networkIcon } from './icons/hackernoon/chart-network.js';
import { cpuChipIcon } from './icons/pixel/cpu-controls.js';
import type { ThemeContext } from '../../app/theme-api.js';
import type { ThemeState, ThemeApplication } from '../../app/theme-api.js';
import type { Translator } from '../../app/i18n.js';

import { createCraftingView } from './crafting.js';
import { createHomeView } from './home.js';
import { createCpuView } from './cpus.js';
import { createHistoryView } from './history.js';
import { createSettingsView } from './settings.js';
import { createAboutView } from './about.js';
import { infoCircle } from './icons/hackernoon/info-circle.js';

/* Theme renderer. Server operations and preference ownership are supplied by the application. */

export function mount(root: HTMLElement, application: ThemeApplication, context: ThemeContext) {
    const { modVersion, updateNotice, settings, i18n, lifetime } = context;
    i18n.register(translations);
    let effectiveDisplay: TerminalDisplay = 'names';
    root.innerHTML = `
        <div class="workspace" id="workspace">
            <div class="window-frame" id="window">
                <div id="network-message" role="status"></div>
                <section id="server-settings" hidden><h2 data-text="serverSettings"></h2></section>
                <section id="web-settings" hidden></section>
                <p id="missing" data-text="invalidRoute" hidden></p>
            </div>
        </div>`;
    const workspace = root.querySelector<HTMLDivElement>('#workspace')!;
    const windowFrame = root.querySelector<HTMLDivElement>('#window')!;
    const networkStatus = root.querySelector<HTMLDivElement>('#network-message')!;
    const webSettingsRoot = root.querySelector<HTMLElement>('#web-settings')!;
    const serverSettingsRoot = root.querySelector<HTMLElement>('#server-settings')!;
    const missingView = root.querySelector<HTMLParagraphElement>('#missing')!;
    const tooltips = createTooltips(root, lifetime);
    let language: ThemeState['preferences']['language'];
    let locale: Translator;
    let state: ThemeState;
    const header = createHeader(application, context, tooltips);
    const notifications = createNotifications(application, lifetime, header.element, workspace);
    root.append(notifications.element);
    root.prepend(header.element);
    const notices = createNotices(
        context,
        {
            enableIcons: () => webSettings.selectDisplay('both'),
            focusSearch: () => resourceTerminal.focusSearch(),
            focusRefresh: header.focusRefresh,
            refresh: renderDisplay
        },
        tooltips
    );
    header.element.after(notices.element);
    const webSettings = createWebSettings(webSettingsRoot, workspace, application, context, () => {
        notices.acknowledgeIcons();
        renderDisplay();
    });
    const mobile = createMobilePanels(root, workspace, header.navigation, tooltips.hide);
    lifetime.onCleanup(() => mobile.dispose());
    const homeView = createHomeView(workspace, application, mobile.beforeDialog);
    lifetime.onCleanup(() => homeView.dispose());
    const craftingView = createCraftingView(
        windowFrame,
        application,
        workspace,
        {
            bind: tooltips.bind,
            hide: tooltips.hide,
            metadata: (resource) => resourceMetadata(resource, 'span', locale)
        },
        mobile.beforeDialog,
        lifetime
    );
    lifetime.onCleanup(() => craftingView.dispose());
    const resourceTerminal = createResourceTerminal(root, application, { craftingView, tooltips, mobile, lifetime });
    lifetime.onCleanup(resourceTerminal.dispose);
    workspace.prepend(resourceTerminal.tools);
    windowFrame.insertBefore(resourceTerminal.element, missingView);
    workspace.insertBefore(resourceTerminal.panel, windowFrame.nextSibling);
    const cpuView = createCpuView(windowFrame, application, { workspace: workspace, settings, lifetime });
    lifetime.onCleanup(() => cpuView.dispose());
    const historyView = createHistoryView(windowFrame, application, tooltips.bind);
    lifetime.onCleanup(() => historyView.dispose());
    const settingsView = createSettingsView(windowFrame, application);
    const aboutView = createAboutView(windowFrame, modVersion);
    function updateLabels() {
        locale = i18n.forLanguage(language);
        document.documentElement.lang = language;
        (
            root.querySelectorAll('[data-theme-text]') as NodeListOf<HTMLElement & { dataset: { themeText: string } }>
        ).forEach((node) => {
            node.textContent = locale.t(node.dataset.themeText);
        });
        (root.querySelectorAll('[data-text]') as NodeListOf<HTMLElement & { dataset: { text: string } }>).forEach(
            (node) => {
                node.textContent = locale.common(node.dataset.text);
            }
        );
        (root.querySelectorAll('[data-label]') as NodeListOf<HTMLElement & { dataset: { label: string } }>).forEach(
            (node) => node.setAttribute('aria-label', locale.common(node.dataset.label))
        );
        (root.querySelectorAll('[data-theme-label]') as NodeListOf<HTMLElement>).forEach((node) =>
            node.setAttribute('aria-label', locale.t(node.dataset.themeLabel!))
        );
    }
    function renderNetworks() {
        let networkMessage = '';
        if (state.gridStatus === 'loading') networkMessage = locale.common('loading');
        else if (state.gridError) networkMessage = locale.common(state.gridError);
        else if (!state.grids.length)
            networkMessage = `${locale.common('noNetworks')}. ${locale.common('noNetworksHelp')}`;
        networkStatus.textContent = networkMessage;
        networkStatus.classList.toggle('api-error', !!state.gridError);
    }
    function renderDisplay() {
        effectiveDisplay = webSettings.render(state, locale);
        workspace.dataset.resourceDisplay = effectiveDisplay;
        workspace.classList.toggle('cpu-icons-enabled', effectiveDisplay !== 'names');
        workspace.classList.toggle('product-icons-enabled', effectiveDisplay !== 'names');
        notices.render(state, locale, effectiveDisplay === 'names');
        resourceTerminal.render(state, locale, effectiveDisplay);
    }

    function render(next: ThemeState) {
        state = next;
        if (language !== state.preferences.language) {
            language = state.preferences.language;
            updateLabels();
        }
        header.render(state, locale);
        windowFrame.hidden = state.route.view === 'home';
        workspace.classList.toggle('with-home', state.route.view === 'home');
        serverSettingsRoot.hidden = state.route.view !== 'server-settings';
        workspace.classList.toggle(
            'with-terminal',
            state.route.view === 'items' ||
                state.route.view === 'plan' ||
                (state.route.view === 'cpus' && state.route.cpuKey !== null)
        );
        missingView.hidden = state.route.view !== 'missing';
        renderDisplay();
        homeView.render(state, locale);
        renderNetworks();
        craftingView.render(state, locale, effectiveDisplay !== 'names');
        tooltips.refresh();
        cpuView.render(state.route, state.cpus, locale);
        historyView.render(state, locale);
        settingsView.render(state, locale);
        aboutView.render(state.route, locale);
        const panes: MobilePane[] = [];
        let mobileHeading: HTMLElement | null = null;
        if (state.route.view === 'items') {
            mobileHeading = resourceTerminal.heading;
            panes.push({ content: resourceTerminal.tools, title: locale.t('mobileFilters'), icon: filterIcon });
            if (state.selected)
                panes.push({ content: resourceTerminal.panel, title: locale.common('details'), icon: infoCircle });
        } else if (state.route.view === 'plan') {
            mobileHeading = root.querySelector('.crafting-plan > .terminal-heading');
            panes.push({
                content: root.querySelector('.plan-tools')!,
                title: locale.t('mobileFilters'),
                icon: filterIcon
            });
            panes.push({
                content: root.querySelector('#plan-panel')!,
                title: locale.common('cpus'),
                icon: cpuChipIcon
            });
        } else if (state.route.view === 'cpus' && state.route.cpuKey) {
            mobileHeading = root.querySelector('.cpu-terminal > .terminal-heading');
            panes.push({
                content: root.querySelector('.cpu-tools')!,
                title: locale.t('mobileFilters'),
                icon: filterIcon
            });
            panes.push({ content: root.querySelector('#cpu-panel')!, title: locale.common('cpus'), icon: cpuChipIcon });
        } else if (state.route.view === 'home') {
            mobileHeading = root.querySelector('#home > .terminal-heading');
            panes.push({
                content: root.querySelector('#home-network-panel')!,
                title: locale.common('network'),
                icon: networkIcon
            });
        }
        mobile.render(
            JSON.stringify(state.route),
            panes,
            {
                navigation: locale.t('navigation'),
                close: locale.t('closePanel')
            },
            mobileHeading
        );
        notifications.render(locale);
    }
    application.subscribe(render);
    updateNotice.subscribe(() => render(state));
}
