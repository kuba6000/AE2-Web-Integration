import type { ThemeContext, ThemeApplication, ThemeState, ThemeSettings } from '../../app/theme-api.js';
import type { Translator } from '../../app/i18n.js';
import { element } from './dom.js';

export type TerminalDisplay = 'names' | 'both' | 'icons';

const PAGE_SIZES = [50, 100, 250, 500, 1000, 'all'] as const;

export function terminalPageSize(settings: ThemeSettings) {
    const saved = settings.get('terminalPageSize');
    return PAGE_SIZES.find((size) => size === saved) ?? 500;
}

export function createWebSettings(
    root: HTMLElement,
    workspace: HTMLElement,
    application: ThemeApplication,
    { settings, themes, lifetime }: Pick<ThemeContext, 'settings' | 'themes' | 'lifetime'>,
    onDisplayChange: () => void
) {
    root.innerHTML = `
        <div class="terminal-heading"><h2 id="web-settings-title" data-text="webSettings"></h2></div>
        <div class="terminal-body">
        <div class="terminal-scroll page-scroll" role="region" aria-labelledby="web-settings-title" tabindex="0">
        <div class="web-preferences">
            <section class="web-preferences-section" aria-labelledby="general-options-title">
                <h3 id="general-options-title" data-theme-text="generalOptions"></h3>
                <label>
                    <span data-text="language"></span>
                    <select id="language"><option value="en">English</option><option value="pl">Polski</option></select>
                </label>
                <label class="web-preference-toggle">
                    <span data-text="autoRefresh"></span><input type="checkbox" id="auto-refresh">
                </label>
            </section>
            <section class="web-preferences-section" aria-labelledby="appearance-options-title">
                <h3 id="appearance-options-title" data-theme-text="appearance"></h3>
                <div id="theme-settings">
                    <button type="button" id="manage-themes" data-theme-text="manageThemes"></button>
                </div>
                <label>
                    <span data-theme-text="font"></span>
                    <select id="font">
                        <option value="monocraft">Monocraft</option>
                        <option value="lato">Lato</option>
                    </select>
                </label>
                <label>
                    <span data-theme-text="resourceColumns"></span>
                    <select id="resource-columns"><option value="auto" data-theme-text="automaticColumns"></option></select>
                </label>
                <label>
                    <span data-theme-text="appearance"></span>
                    <select id="appearance">
                        <option value="system" data-theme-text="system"></option>
                        <option value="light" data-theme-text="light"></option>
                        <option value="dark" data-theme-text="dark"></option>
                    </select>
                </label>
            </section>
            <section class="web-preferences-section" aria-labelledby="terminal-options-title">
                <h3 id="terminal-options-title" data-text="terminal"></h3>
                <div class="web-preference-field">
                    <label>
                        <span data-theme-text="terminalDisplay"></span>
                        <select id="terminal-display" aria-describedby="icon-availability">
                            <option value="names" data-theme-text="namesOnly"></option>
                            <option value="both" data-theme-text="detailedItems"></option>
                            <option value="icons" data-theme-text="compactItems"></option>
                        </select>
                    </label>
                    <p class="hint" id="icon-availability" role="status"></p>
                </div>
                <label>
                    <span data-theme-text="resourcesPerPage"></span>
                    <select id="terminal-page-size"></select>
                </label>
                <label class="web-preference-toggle">
                    <span data-theme-text="animatedIcons"></span><input type="checkbox" id="animated-icons">
                </label>
                <label>
                    <span><span id="animation-interval-label" data-theme-text="animationUpdateInterval"></span> — <output id="animation-interval-value" for="animation-update-interval"></output></span>
                    <input type="range" id="animation-update-interval" min="0" max="1000" step="10" aria-labelledby="animation-interval-label">
                </label>
            </section>
        </div>
        </div>
        </div>`;
    const controls = {
        terminalDisplay: root.querySelector<HTMLSelectElement>('#terminal-display')!,
        pageSize: root.querySelector<HTMLSelectElement>('#terminal-page-size')!,
        animatedIcons: root.querySelector<HTMLInputElement>('#animated-icons')!,
        animationInterval: root.querySelector<HTMLInputElement>('#animation-update-interval')!,
        animationIntervalValue: root.querySelector<HTMLOutputElement>('#animation-interval-value')!,
        iconAvailability: root.querySelector<HTMLParagraphElement>('#icon-availability')!,
        language: root.querySelector<HTMLSelectElement>('#language')!,
        appearance: root.querySelector<HTMLSelectElement>('#appearance')!,
        font: root.querySelector<HTMLSelectElement>('#font')!,
        resourceColumns: root.querySelector<HTMLSelectElement>('#resource-columns')!,
        autoRefresh: root.querySelector<HTMLInputElement>('#auto-refresh')!
    };
    for (let count = 1; count <= 8; count++) {
        const option = element('option', String(count));
        option.value = String(count);
        controls.resourceColumns.append(option);
    }
    for (const size of PAGE_SIZES) {
        const option = element('option', String(size));
        option.value = String(size);
        if (size === 'all') option.dataset.themeText = 'allResources';
        controls.pageSize.append(option);
    }
    controls.pageSize.addEventListener('change', () => {
        const selected = PAGE_SIZES.find((size) => String(size) === controls.pageSize.value);
        if (selected !== undefined) settings.set('terminalPageSize', selected);
    });
    const appearances = ['light', 'dark', 'system'];
    const savedAppearance = settings.get('appearance');
    let appearance =
        typeof savedAppearance === 'string' && appearances.includes(savedAppearance) ? savedAppearance : 'system';
    const savedFont = settings.get('font');
    let font = savedFont === 'lato' || savedFont === 'montserrat' ? 'lato' : 'monocraft';
    const savedColumns = settings.get('resourceColumns');
    let resourceColumns =
        typeof savedColumns === 'number' && Number.isInteger(savedColumns) && savedColumns >= 1 && savedColumns <= 8
            ? savedColumns
            : 0;
    const savedDisplay = settings.get('terminalDisplay');
    let terminalDisplay: TerminalDisplay =
        savedDisplay === 'names' || savedDisplay === 'both' || savedDisplay === 'icons' ? savedDisplay : 'both';
    application.displayIcons(terminalDisplay !== 'names');
    controls.animatedIcons.checked = settings.get('animatedIcons') !== false;
    const savedInterval = settings.get('animationUpdateInterval');
    let animationInterval =
        typeof savedInterval === 'number' &&
        Number.isInteger(savedInterval) &&
        savedInterval >= 0 &&
        savedInterval <= 1000 &&
        savedInterval % 10 === 0
            ? savedInterval
            : 200;
    function updateAnimationInterval() {
        const value = animationInterval === 0 ? 'Instant' : `${animationInterval} ms`;
        controls.animationInterval.value = String(animationInterval);
        controls.animationInterval.setAttribute('aria-valuetext', value);
        controls.animationInterval.disabled = !controls.animatedIcons.checked;
        controls.animationIntervalValue.value = value;
        application.icons.animate(controls.animatedIcons.checked, animationInterval);
    }
    updateAnimationInterval();
    controls.animatedIcons.addEventListener('change', () => {
        settings.set('animatedIcons', controls.animatedIcons.checked);
        updateAnimationInterval();
    });
    controls.animationInterval.addEventListener('input', () => {
        animationInterval = Number(controls.animationInterval.value);
        settings.set('animationUpdateInterval', animationInterval);
        updateAnimationInterval();
    });

    lifetime.onCleanup(() => {
        delete document.documentElement.dataset.appearance;
        delete document.documentElement.dataset.font;
    });
    function renderColumns() {
        controls.resourceColumns.value = resourceColumns ? String(resourceColumns) : 'auto';
        if (resourceColumns) workspace.style.setProperty('--resource-column-width', `calc(100% / ${resourceColumns})`);
        else workspace.style.removeProperty('--resource-column-width');
    }
    renderColumns();

    const languageSelect = controls.language;
    languageSelect.addEventListener('change', () => application.preference('language', languageSelect.value));
    const manageThemes = root.querySelector<HTMLButtonElement>('#manage-themes')!;
    root.querySelector<HTMLElement>('#theme-settings')!.hidden = !themes;
    manageThemes.addEventListener('click', () => themes?.open());
    const appearanceSelect = controls.appearance;
    const fontSelect = controls.font;
    controls.resourceColumns.addEventListener('change', () => {
        const value = controls.resourceColumns.value;
        const count = value === 'auto' ? 0 : Number(value);
        if (!Number.isInteger(count) || count < 0 || count > 8) return;
        resourceColumns = count;
        settings.set('resourceColumns', resourceColumns);
        renderColumns();
    });
    const displaySelect = controls.terminalDisplay;
    function selectDisplay(value: TerminalDisplay) {
        terminalDisplay = value;
        settings.set('terminalDisplay', terminalDisplay);
        application.displayIcons(terminalDisplay !== 'names');
        onDisplayChange();
    }
    displaySelect.addEventListener('change', () => {
        if (!['names', 'both', 'icons'].includes(displaySelect.value)) return;
        if (displaySelect.value !== 'names' && application.state.iconPack.available !== true) return;
        selectDisplay(displaySelect.value as TerminalDisplay);
    });
    fontSelect.addEventListener('change', () => {
        if (fontSelect.value !== 'monocraft' && fontSelect.value !== 'lato') return;
        font = fontSelect.value;
        settings.set('font', font);
        document.documentElement.dataset.font = font;
    });
    appearanceSelect.addEventListener('change', () => {
        if (!appearances.includes(appearanceSelect.value)) return;
        appearance = appearanceSelect.value;
        settings.set('appearance', appearance);
        document.documentElement.dataset.appearance = appearance;
    });

    const autoRefresh = controls.autoRefresh;
    autoRefresh.addEventListener('change', () => application.preference('autoRefresh', autoRefresh.checked));
    function render(state: ThemeState, locale: Translator): TerminalDisplay {
        root.hidden = state.route.view !== 'web-settings';
        const language = state.preferences.language;
        document.documentElement.dataset.appearance = appearance;
        const languageSelect = controls.language;
        if (![...languageSelect.options].some((option) => option.value === language)) {
            const option = element('option', language);
            option.value = language;
            languageSelect.append(option);
        }
        languageSelect.value = language;
        controls.appearance.value = appearance;
        controls.font.value = font;
        controls.pageSize.value = String(terminalPageSize(settings));
        document.documentElement.dataset.font = font;

        autoRefresh.checked = state.preferences.autoRefresh;
        const { available, status } = state.iconPack;
        const effectiveDisplay = available === true ? terminalDisplay : 'names';
        const select = controls.terminalDisplay;
        select.value = effectiveDisplay;
        for (const option of select.options) option.disabled = option.value !== 'names' && available !== true;
        let availabilityMessage = '';
        if (status === 'error') availabilityMessage = locale.t('iconsError');
        else if (available === false) availabilityMessage = locale.t('iconsUnavailable');
        else if (status === 'loading') availabilityMessage = locale.t('iconsLoading');
        controls.iconAvailability.textContent = availabilityMessage;

        return effectiveDisplay;
    }
    return { render, selectDisplay };
}
