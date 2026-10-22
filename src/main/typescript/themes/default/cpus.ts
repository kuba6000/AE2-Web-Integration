import type { ThemeState, ThemeApplication, ThemeLifetime, ThemeSettings } from '../../app/theme-api.js';
import type { Translator } from '../../app/i18n.js';
import { element } from './dom.js';
import { createResourceIcon, paintResourceIcon } from './resource-icon.js';
import { renderMinecraftText } from './minecraft-text.js';
import { createCpuOverview } from './cpu-overview.js';
import { createCpuSelector } from './cpu-selector.js';
import { createCpuResources } from './cpu-resources.js';
import { createCpuTooltips } from './cpu-tooltips.js';
import { renderCpuState, renderCpuActions, cpuOutcomeText } from './cpu-presentation.js';

export function createCpuView(
    root: HTMLElement,
    application: ThemeApplication,
    {
        workspace,
        settings,
        lifetime: themeLifetime
    }: { lifetime: ThemeLifetime; workspace: HTMLElement; settings: ThemeSettings }
) {
    const lifetime = new AbortController();
    themeLifetime.onCleanup(() => lifetime.abort());
    const view = element('section', '', 'cpu-view');
    view.hidden = true;
    const tips = createCpuTooltips(
        workspace,
        application.state.capabilities.cpuSelectionMode === true,
        lifetime.signal
    );
    themeLifetime.onCleanup(tips.dispose);
    const overview = createCpuOverview(application, tips);
    themeLifetime.onCleanup(overview.dispose);
    const resources = createCpuResources(application, tips, themeLifetime, settings);
    themeLifetime.onCleanup(resources.dispose);
    const selector = createCpuSelector(application, settings, tips);
    themeLifetime.onCleanup(selector.dispose);
    view.append(overview.element, resources.element);
    root.append(view);
    workspace.insertBefore(resources.tools, root);
    workspace.append(selector.element);
    const status = element('p', '', 'cpu-status');
    status.role = 'status';
    const panelState = element('span', '', 'sr-only');
    panelState.role = 'status';
    const work = element('section', '', 'cpu-detail-work');
    work.role = 'region';
    work.tabIndex = 0;
    const output = element('p', '', 'cpu-detail-output');
    const outputIcon = createResourceIcon();
    const timing = element('p', '', 'cpu-detail-note');
    const tracking = element('p', '', 'cpu-detail-note');
    const requesters = element('p', '', 'cpu-detail-note');
    work.append(panelState, output, requesters, timing, tracking);
    const actions = element('div');
    const footer = element('div', '', 'cpu-footer');
    footer.append(work, actions, status);

    resources.element.append(footer);
    const outputIcons = application.icons.observe(footer, paintResourceIcon);
    let selectedCpu = '';
    tips.bindCpu(work, () => selectedCpu);

    function renderElapsed(state: ThemeState['cpus'], locale: Translator) {
        const detail = state.error ? null : state.detail;
        const value =
            detail?.isBusy && detail.hasTrackingInfo
                ? `${locale.common('cpuElapsedLabel')}: ${locale.duration(detail.timeElapsed)}`
                : '';
        if (timing.textContent !== value) timing.textContent = value;
        if (timing.hidden !== !value) timing.hidden = !value;
    }

    function renderWork(route: ThemeState['route'], state: ThemeState['cpus'], locale: Translator) {
        if (route.view !== 'cpus' || !route.cpuKey) {
            outputIcons.update([], null);
            return;
        }
        const t = locale.common;
        const detail = state.error ? null : state.detail;
        const selectedOutcome = state.outcomes[route.cpuKey];
        work.setAttribute('aria-label', t('cpuCurrentJob'));
        let message = '';
        if (selectedOutcome?.uncertain) message = cpuOutcomeText(selectedOutcome, state.status, locale);
        else if (state.error) message = t(state.error);
        status.textContent = message;
        const selectedNotice = selectedOutcome?.notice ? cpuOutcomeText(selectedOutcome, state.status, locale) : '';
        if (selectedNotice && selectedOutcome?.notice !== state.error) status.textContent += ` ${selectedNotice}`;
        if (selectedOutcome?.uncertain && state.error) status.textContent += ` ${t(state.error)}`;
        status.hidden = !status.textContent;
        status.classList.toggle(
            'api-error',
            !!state.error ||
                !!selectedOutcome?.uncertain ||
                (!!selectedOutcome?.notice && selectedOutcome.notice !== 'cpuCancelled')
        );
        panelState.hidden = !detail;
        if (detail) renderCpuState(panelState, detail.isBusy, detail.isPaused, locale);
        else panelState.replaceChildren();
        work.hidden = !detail;
        output.replaceChildren();
        if (detail?.isBusy) {
            if (detail.finalOutput) {
                const product = element('span', '', 'cpu-job-name');
                product.append(renderMinecraftText(detail.finalOutput.displayName));
                output.append(
                    outputIcon,
                    product,
                    element('span', `× ${locale.number(detail.finalOutput.quantity)}`, 'cpu-detail-quantity')
                );
            } else output.textContent = t('cpuOutputUnknown');
        } else if (detail) output.textContent = t('cpuIdleMessage');
        requesters.textContent =
            detail?.isBusy && detail.requesters.length
                ? `${t('craftingRequestersLabel')}: ${detail.requesters.map((player) => player.name).join(', ')}`
                : '';
        requesters.hidden = !requesters.textContent;
        renderElapsed(state, locale);
        tracking.textContent = detail?.isBusy && !detail.hasTrackingInfo ? t('cpuTrackingUnavailable') : '';
        tracking.hidden = !tracking.textContent;
        outputIcons.update(
            detail?.isBusy && detail.finalOutput ? [{ element: output, icon: detail.finalOutput.icon }] : [],
            state.icons
        );
        renderCpuActions(actions, route.cpuKey, detail, state, locale, application.cpus);
    }
    return {
        timing(state: ThemeState['cpus'], locale: Translator) {
            tips.update(state, locale, selectedCpu);
            renderElapsed(state, locale);
            resources.timing();
            tips.refreshCpu();
        },
        render(route: ThemeState['route'], state: ThemeState['cpus'], locale: Translator) {
            selectedCpu = route.view === 'cpus' ? route.cpuKey || '' : '';
            view.hidden = route.view !== 'cpus';
            tips.update(state, locale, selectedCpu);
            overview.render(route, state, locale);
            resources.render(route, state, locale);
            selector.render(route, state, locale);
            renderWork(route, state, locale);
            if (route.view === 'cpus' && selectedCpu) tips.refreshCpu();
        },
        dispose() {
            lifetime.abort();
            overview.dispose();
            resources.dispose();
            selector.dispose();
            outputIcons.dispose();
            tips.dispose();
            view.remove();
        }
    };
}
