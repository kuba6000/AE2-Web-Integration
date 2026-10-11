import type { ThemeContext, ThemeState } from '../../app/theme-api.js';
import type { Translator } from '../../app/i18n.js';
import { infoCircle } from './icons/hackernoon/info-circle.js';
import { element } from './dom.js';
import type { TooltipBindings } from './tooltip-bindings.js';

type NoticeActions = {
    enableIcons(): void;
    focusSearch(): void;
    focusRefresh(): void;
    refresh(): void;
};

export function createNotices(
    { settings, updateNotice, lifetime }: Pick<ThemeContext, 'settings' | 'updateNotice' | 'lifetime'>,
    actions: NoticeActions,
    { bind: bindTooltip, hide: hideTooltip }: TooltipBindings
) {
    const container = document.createElement('div');
    container.innerHTML = `
        <div class="info-notices" id="info-notices" role="region" data-label="notices" tabindex="0" hidden>
            <section class="window-frame info-notice" id="update-notice" aria-labelledby="update-notice-text" hidden>
                ${infoCircle}
                <p><span id="update-notice-text"></span> <a id="update-releases" data-theme-text="viewReleases"></a></p>
                <button type="button" id="dismiss-update">×</button>
                <div class="update-notice-actions">
                    <button type="button" id="snooze-update" data-theme-text="remindLater"></button>
                    <button type="button" id="skip-update" data-theme-text="skipUpdate"></button>
                </div>
                <p id="update-notice-error" role="alert" hidden></p>
            </section>
            <section class="window-frame info-notice" id="icon-notice" aria-labelledby="icon-notice-text" hidden>
                ${infoCircle}
                <p id="icon-notice-text" data-theme-text="iconsOffer"></p>
                <button type="button" id="enable-icons" data-theme-text="enableIcons"></button>
                <button type="button" id="dismiss-icons">×</button>
            </section>
            <section class="window-frame info-notice" id="tracking-notice" aria-labelledby="tracking-notice-text" hidden>
                ${infoCircle}
                <p><span id="tracking-notice-text"></span> <a id="tracking-settings-link"></a></p>
            </section>
        </div>`;
    const root = container.firstElementChild as HTMLElement;
    const controls = {
        iconNotice: root.querySelector<HTMLElement>('#icon-notice')!,
        updateNotice: root.querySelector<HTMLElement>('#update-notice')!,
        updateReleases: root.querySelector<HTMLAnchorElement>('#update-releases')!,
        snoozeUpdate: root.querySelector<HTMLButtonElement>('#snooze-update')!,
        dismissUpdate: root.querySelector<HTMLButtonElement>('#dismiss-update')!,
        skipUpdate: root.querySelector<HTMLButtonElement>('#skip-update')!,
        updateNoticeText: root.querySelector<HTMLSpanElement>('#update-notice-text')!,
        updateNoticeError: root.querySelector<HTMLParagraphElement>('#update-notice-error')!,
        infoNotices: root,
        trackingNotice: root.querySelector<HTMLElement>('#tracking-notice')!,
        trackingNoticeText: root.querySelector<HTMLElement>('#tracking-notice-text')!,
        trackingSettingsLink: root.querySelector<HTMLAnchorElement>('#tracking-settings-link')!,
        enableIcons: root.querySelector<HTMLButtonElement>('#enable-icons')!,
        dismissIcons: root.querySelector<HTMLButtonElement>('#dismiss-icons')!
    };
    let locale: Translator;
    let iconSuggestionDismissed = settings.get('iconSuggestionDismissed') === true;
    let disposed = false;
    lifetime.onCleanup(() => {
        disposed = true;
    });
    if (updateNotice.update) controls.updateReleases.href = updateNotice.update.releaseUrl;
    function acknowledgeIcons() {
        iconSuggestionDismissed = true;
        settings.set('iconSuggestionDismissed', true);
    }
    controls.enableIcons.addEventListener('click', () => {
        actions.enableIcons();
        actions.focusSearch();
    });
    bindTooltip(controls.snoozeUpdate, () => [element('span', locale.t('remindLaterHelp'))]);
    bindTooltip(controls.skipUpdate, () => [element('span', locale.t('skipUpdateHelp'))]);
    controls.skipUpdate.addEventListener('click', async () => {
        hideTooltip();
        await updateNotice.skip();
        if (!updateNotice.visible && !disposed) actions.focusRefresh();
    });
    function dismissUpdate(snooze: boolean) {
        updateNotice.dismiss(snooze);
        hideTooltip();
        actions.refresh();
        actions.focusRefresh();
    }
    controls.dismissUpdate.addEventListener('click', () => dismissUpdate(false));
    controls.snoozeUpdate.addEventListener('click', () => dismissUpdate(true));
    controls.dismissIcons.addEventListener('click', () => {
        acknowledgeIcons();
        actions.refresh();
        actions.focusSearch();
    });

    function renderTrackingNotice(state: ThemeState) {
        const routeGrid =
            state.gridStatus === 'ready' ? state.grids.find((grid) => grid.key === state.route.gridKey) : undefined;
        const trackingUnavailable =
            routeGrid?.isTrackingEnabled === false &&
            ((state.route.view === 'items' && state.itemStatus === 'ready') ||
                (state.route.view === 'cpus' &&
                    (state.cpus.status === 'ready' ||
                        (state.cpus.status === 'loading' && state.cpus.cpus.length > 0))));
        controls.trackingNotice.hidden = !trackingUnavailable;
        controls.trackingNoticeText.textContent = locale.common('trackingDisabledTip');
        controls.trackingSettingsLink.textContent = locale.common('enableTracking');
        controls.trackingSettingsLink.href = routeGrid ? `#/grids/${encodeURIComponent(routeGrid.key)}/settings` : '#/';
    }

    function renderUpdateNotice() {
        controls.updateNotice.hidden = !updateNotice.visible;
        controls.updateNoticeText.textContent = locale.t('updateAvailable', {
            version: updateNotice.update?.version || ''
        });
        controls.skipUpdate.disabled = updateNotice.pending;
        controls.snoozeUpdate.disabled = updateNotice.pending;
        controls.updateNoticeError.hidden = !updateNotice.error;
        controls.updateNoticeError.textContent = updateNotice.error ? locale.t(updateNotice.error) : '';
    }

    function render(state: ThemeState, translator: Translator, namesOnly: boolean) {
        locale = translator;
        const available = state.iconPack.available;
        controls.dismissIcons.setAttribute('aria-label', locale.t('dismissIcons'));
        controls.dismissUpdate.setAttribute('aria-label', locale.t('dismissUpdate'));
        controls.snoozeUpdate.setAttribute('aria-description', locale.t('remindLaterHelp'));
        controls.iconNotice.hidden =
            state.route.view !== 'items' || !namesOnly || available !== true || iconSuggestionDismissed;
        renderTrackingNotice(state);
        renderUpdateNotice();
        controls.infoNotices.hidden =
            controls.iconNotice.hidden && controls.trackingNotice.hidden && controls.updateNotice.hidden;
    }
    return { element: root, render, acknowledgeIcons };
}
