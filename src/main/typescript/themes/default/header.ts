import type { ApiError } from '../../app/api.js';
import type { ThemeApplication, ThemeContext, ThemeState } from '../../app/theme-api.js';
import type { Translator } from '../../app/i18n.js';
import { cpuHref, historyHref } from '../../app/router.js';
import { networkLabel } from './network.js';
import { craftingHammer } from './icons/pixel/terminal.js';
import { userIcon } from './icons/hackernoon/user.js';
import { refreshIcon } from './icons/hackernoon/refresh.js';
import { wifiIcon } from './icons/hackernoon/wifi.js';
import { powerIcon } from './icons/pixel/account.js';
import { element } from './dom.js';
import type { TooltipBindings } from './tooltip-bindings.js';

export function createHeader(
    application: ThemeApplication,
    { user, logout, updateNotice }: Pick<ThemeContext, 'user' | 'logout' | 'updateNotice'>,
    { bind: bindTooltip }: TooltipBindings
) {
    const root = document.createElement('div');
    root.innerHTML = `
        <div class="window-frame control-panel">
            <header class="site-header">
                <div class="brand">
                    <span class="brand-mark">${craftingHammer}</span>
                    <h1 aria-label="AE2 Web Integration"><span class="brand-full">AE2 <span>Web Integration</span></span><span class="brand-short" aria-hidden="true">AE2<span>WI</span></span></h1>
                </div>
                <span id="selected-network" tabindex="0" hidden></span>
                <div class="account-controls">
                    <span class="account-user">${userIcon}<span id="username"></span></span>
                    <span class="account-actions">
                        <button id="logout" class="account-action" data-label="logout">${powerIcon}</button>
                        <button id="refresh" class="account-action" data-label="refresh" aria-busy="false">${refreshIcon}<span class="live-icon" hidden aria-hidden="true">${wifiIcon}</span></button>
                    </span>
                    <span id="activity" class="sr-only" role="status" aria-live="off" hidden>
                        <span id="activity-text"></span>
                    </span>
                </div>
            </header>
            <nav class="view-tabs">
                <a href="#/" data-view="home" data-text="home"></a>
                <a id="settings-link" data-view="settings" data-text="network" hidden></a>
                <a id="terminal-link" data-view="items" data-text="terminal" hidden></a>
                <a id="cpu-link" data-view="cpus" data-text="cpus" hidden></a>
                <a id="history-link" data-view="history" data-text="history" hidden></a>
                <a href="#/server-settings" data-view="server-settings" data-text="serverSettings"></a>
                <a href="#/web-settings" data-view="web-settings" data-text="webSettings"></a>
                <a href="#/about" data-view="about" data-text="about"></a>
                <span id="updated" aria-live="off"></span>
            </nav>
            <p id="session-message" class="api-error" role="alert" hidden></p>
        </div>`;
    const panel = root.firstElementChild as HTMLElement;
    const controls = {
        logout: panel.querySelector<HTMLButtonElement>('#logout')!,
        sessionMessage: panel.querySelector<HTMLParagraphElement>('#session-message')!,
        username: panel.querySelector<HTMLSpanElement>('#username')!,
        selectedNetwork: panel.querySelector<HTMLSpanElement>('#selected-network')!,
        refresh: panel.querySelector<HTMLButtonElement>('#refresh')!,
        refreshIcon: panel.querySelector<SVGElement>('#refresh .theme-icon')!,
        liveIcon: panel.querySelector<HTMLElement>('#refresh .live-icon')!,
        activity: panel.querySelector<HTMLSpanElement>('#activity')!,
        activityText: panel.querySelector<HTMLSpanElement>('#activity-text')!,
        terminalLink: panel.querySelector<HTMLAnchorElement>('#terminal-link')!,
        cpuLink: panel.querySelector<HTMLAnchorElement>('#cpu-link')!,
        historyLink: panel.querySelector<HTMLAnchorElement>('#history-link')!,
        settingsLink: panel.querySelector<HTMLAnchorElement>('#settings-link')!,
        updated: panel.querySelector<HTMLSpanElement>('#updated')!
    };
    const navigation = panel.querySelector<HTMLElement>('.view-tabs')!;
    const links = navigation.querySelectorAll<HTMLAnchorElement>('[data-view]');
    controls.username.textContent = user.username;
    let state: ThemeState;
    let locale: Translator;
    /** The live connection of what the current view shows. */
    const currentStream = () => (state.route.view === 'cpus' ? state.cpus.stream : state.stream);
    bindTooltip(controls.selectedNetwork, () => {
        const grid = state.grids.find((grid) => grid.key === state.route.gridKey);
        const lines = [element('strong', locale.common('currentNetwork'))];
        if (grid?.name) lines.push(element('span', grid.name));
        lines.push(element('code', state.route.gridKey || ''));
        return lines;
    });

    bindTooltip(controls.logout, () => [element('span', locale.common('logout'))]);
    bindTooltip(controls.refresh, () => {
        const stream = currentStream();
        if (stream?.status === 'live' && stream.lastEventAt !== null) {
            const timestamp = element('time', locale.time(stream.lastEventAt));
            timestamp.dateTime = new Date(stream.lastEventAt).toISOString();
            const event = element('span', `${locale.t('liveLastEvent')}: `);
            event.append(timestamp);
            return [element('strong', locale.t('liveUpdates')), event, element('span', locale.t('liveResync'))];
        }
        const lines = [element('span', locale.common('refresh'))];
        if (state.updatedAt)
            lines.push(element('span', locale.common('updated', { time: locale.time(state.updatedAt) })));
        return lines;
    });
    controls.refresh.addEventListener('click', () => {
        void application.refresh({ reloadDetail: true });
    });
    controls.logout.addEventListener('click', async () => {
        controls.logout.disabled = true;
        controls.sessionMessage.hidden = true;
        try {
            await logout();
        } catch (error) {
            controls.sessionMessage.textContent = locale.common((error as ApiError).status);
            controls.sessionMessage.hidden = false;
            controls.logout.disabled = false;
        }
    });

    function renderNetwork(state: ThemeState, locale: Translator) {
        const gridKey = state.route.gridKey;
        const grid = state.grids.find((grid) => grid.key === gridKey);
        const selectedNetwork = controls.selectedNetwork;
        selectedNetwork.hidden = !gridKey;
        const label = grid ? networkLabel(grid) : gridKey || '';
        if (selectedNetwork.textContent !== label) selectedNetwork.textContent = label;
        selectedNetwork.setAttribute('aria-label', `${locale.common('currentNetwork')}: ${label}`);
    }

    function renderNavigation(state: ThemeState) {
        links.forEach((link) => {
            if (
                link.dataset.view === state.route.view ||
                (link.dataset.view === 'items' && (state.route.view === 'plan' || state.route.view === 'tracking'))
            )
                link.setAttribute('aria-current', 'page');
            else link.removeAttribute('aria-current');
        });
        const navigationGridKey = state.route.gridKey || state.selectedGridKey;
        controls.cpuLink.hidden = !navigationGridKey;
        controls.terminalLink.hidden = !navigationGridKey;
        controls.terminalLink.href = `#/grids/${encodeURIComponent(String(navigationGridKey))}/items`;
        controls.cpuLink.href = cpuHref(navigationGridKey);
        controls.historyLink.hidden = !navigationGridKey;
        controls.historyLink.href = historyHref(navigationGridKey);
        controls.settingsLink.hidden = !navigationGridKey;
        controls.settingsLink.href = `#/grids/${encodeURIComponent(String(navigationGridKey))}/settings`;
    }

    function renderActivity(state: ThemeState, locale: Translator) {
        const cpuMutations =
            state.route.view === 'cpus' ? Object.values(state.cpus.outcomes).filter((outcome) => outcome.mutation) : [];
        const craftingVisible = state.route.view === 'items' || state.route.view === 'plan';
        const actionPending =
            updateNotice.pending ||
            cpuMutations.length > 0 ||
            (craftingVisible && !!state.crafting.mutation) ||
            (state.route.view === 'settings' && state.settings.saving);
        const viewLoading =
            (state.route.view === 'cpus' && (state.cpus.reading || state.cpus.status === 'loading')) ||
            (craftingVisible && ['loading', 'calculating'].includes(state.crafting.status)) ||
            (state.route.view === 'history' && (state.history.reading || state.history.status === 'loading')) ||
            (state.route.view === 'settings' && state.settings.status === 'loading');
        const active =
            state.refreshingView || state.refreshing || state.gridStatus === 'loading' || viewLoading || actionPending;
        const stream = currentStream();
        const live = stream?.status === 'live';
        controls.liveIcon.hidden = !live;
        controls.refreshIcon.style.display = live ? 'none' : '';
        controls.refresh.setAttribute('aria-label', live ? locale.t('liveUpdates') : locale.common('refresh'));
        controls.refresh.setAttribute('aria-busy', String(!live && (active || stream?.status === 'connecting')));
        controls.activity.hidden = !active;
        controls.activity.setAttribute('aria-label', locale.common('activity'));
        controls.activity.setAttribute('aria-live', actionPending ? 'polite' : 'off');
        let activityLabel = 'loading';
        if (cpuMutations.some((outcome) => outcome.mutation === 'cancel')) activityLabel = 'cpuCancelling';
        else if (cpuMutations.length) activityLabel = 'cpuUpdating';
        else if (state.route.view === 'cpus') activityLabel = 'cpuRefreshing';
        controls.activityText.textContent = active ? locale.common(activityLabel) : '';
    }

    function render(next: ThemeState, translator: Translator) {
        state = next;
        locale = translator;
        renderNetwork(state, locale);
        renderNavigation(state);
        renderActivity(state, locale);
        controls.updated.textContent = state.updatedAt
            ? locale.common('updated', { time: locale.time(state.updatedAt) })
            : '';
    }
    return { element: panel, navigation, render, focusRefresh: () => controls.refresh.focus() };
}
