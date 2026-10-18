import type { ThemeState as TerminalState, ThemeApplication as Terminal } from '../../app/theme-api.js';
import { createHomePins } from './home-pins.js';
import type { ThemeSettings } from '../../app/theme-api.js';
import type { Translator } from '../../app/i18n.js';
import { homeIcon } from './icons/hackernoon/home.js';
import { networkIcon } from './icons/hackernoon/chart-network.js';
import { networkLabel as gridLabel } from './network.js';

function element<Tag extends keyof HTMLElementTagNameMap>(tag: Tag, className = '') {
    const node = document.createElement(tag);
    node.className = className;
    return node;
}

/** Home presentation and chooser lifetime; network discovery belongs to the application. */
export function createHomeView(
    root: HTMLElement,
    application: Terminal,
    beforeDialog: (source: HTMLElement) => HTMLElement,
    settings: ThemeSettings
) {
    const view = element('section', 'window-frame home-dashboard');
    view.id = 'home';
    view.innerHTML = `<div class="terminal-heading"><h2>${homeIcon}<span></span></h2></div>
        <div class="terminal-scroll home-scroll" tabindex="0">
        <p class="home-scope"></p><p class="home-status" role="status"></p><button type="button" class="home-retry"></button></div>`;
    const pins = createHomePins(view.querySelector<HTMLElement>('.home-scroll')!, application, settings);
    const panel = element('aside', 'home-network-panel');
    panel.id = 'home-network-panel';
    panel.innerHTML = `<h3>${networkIcon}<span></span></h3><h4 class="home-selected-network"></h4><dl class="home-selected-identity"><dt></dt><dd><code></code></dd></dl><a class="home-network-details button-link"></a><button type="button" class="choose-network"></button>`;
    const title = view.querySelector('h2 span')!;
    const description = view.querySelector('.home-scope')!;
    const status = view.querySelector<HTMLElement>('.home-status')!;
    const networkLabel = panel.querySelector('h3 span')!;
    const selectedNetwork = panel.querySelector('.home-selected-network')!;
    const selectedIdentity = panel.querySelector<HTMLElement>('.home-selected-identity')!;
    const details = panel.querySelector<HTMLAnchorElement>('.home-network-details')!;
    const choose = panel.querySelector<HTMLButtonElement>('.choose-network')!;
    const retry = view.querySelector<HTMLButtonElement>('.home-retry')!;
    const dialog = element('dialog', 'window-frame network-dialog');
    dialog.setAttribute('aria-labelledby', 'network-dialog-title');
    dialog.innerHTML = `<div class="home-section-heading"><h2 id="network-dialog-title"></h2><button type="button" class="close-dialog">×</button></div><p class="network-dialog-help"></p><p class="network-dialog-status" role="status"></p><div class="network-options"></div><button type="button" class="network-dialog-retry"></button>`;
    const dialogTitle = dialog.querySelector('h2')!;
    const dialogHelp = dialog.querySelector('.network-dialog-help')!;
    const close = dialog.querySelector<HTMLButtonElement>('.close-dialog')!;
    const dialogStatus = dialog.querySelector<HTMLElement>('.network-dialog-status')!;
    const dialogRetry = dialog.querySelector<HTMLButtonElement>('.network-dialog-retry')!;
    dialogRetry.addEventListener('click', () => void application.refresh());
    const options = dialog.querySelector<HTMLElement>('.network-options')!;
    root.append(view, panel);
    root.after(dialog);
    let opener: HTMLElement = choose;
    choose.addEventListener('click', () => {
        opener = beforeDialog(choose);
        dialog.showModal();
    });
    dialog.addEventListener('close', () => {
        if (opener.isConnected && opener.getClientRects().length) opener.focus({ preventScroll: true });
    });
    close.addEventListener('click', () => dialog.close());
    retry.addEventListener('click', () => void application.refresh());
    const choices = new Map<string, ReturnType<typeof createChoice>>();

    function createChoice() {
        const card = element('div', 'inset-frame network-choice');
        const link = element('a', 'network-choice-link');
        const name = element('strong');
        const count = element('span');
        link.append(name, count);
        const disclosure = element('details');
        const summary = element('summary');
        const identity = element('code');
        disclosure.append(summary, identity);
        card.append(link, disclosure);
        return { card, link, name, count, summary, identity, disclosure };
    }
    return {
        render(state: TerminalState, locale: Translator) {
            view.hidden = state.route.view !== 'home';
            pins.render(state, locale);
            panel.hidden = view.hidden;
            if (view.hidden) {
                if (dialog.open) dialog.close();
                return;
            }
            title.textContent = locale.common('home');
            description.textContent = locale.common('homeHelp');
            networkLabel.textContent = locale.common('network');
            const selected = state.grids.find((grid) => grid.key === state.selectedGridKey);
            selectedNetwork.textContent = selected ? gridLabel(selected) : locale.common('noNetworkSelected');
            selectedIdentity.hidden = !selected?.name;
            details.hidden = !selected;
            selectedIdentity.querySelector('dt')!.textContent = locale.common('networkIdentifier');
            selectedIdentity.querySelector('code')!.textContent = selected?.name ? selected.key : '';
            details.textContent = locale.common('networkDetails');
            details.href = selected ? `#/grids/${encodeURIComponent(selected.key)}/settings` : '#/';
            choose.textContent = locale.common('chooseNetwork');
            choose.disabled = state.gridStatus !== 'ready' || !state.grids.length;
            retry.textContent = locale.common('retry');
            retry.hidden = state.gridStatus !== 'error' && (state.gridStatus !== 'ready' || !!state.grids.length);
            dialogTitle.textContent = locale.common('chooseNetwork');
            dialogHelp.textContent = locale.common('homeHelp');
            dialogStatus.textContent = '';
            if (state.gridError) dialogStatus.textContent = locale.common(state.gridError);
            else if (state.gridStatus === 'loading') dialogStatus.textContent = locale.common('loading');
            else if (!state.grids.length)
                dialogStatus.textContent = `${locale.common('noNetworks')}. ${locale.common('noNetworksHelp')}`;
            dialogStatus.hidden = !dialogStatus.textContent;
            dialogRetry.hidden = state.gridStatus !== 'error' && !!state.grids.length;
            dialogRetry.textContent = locale.common('retry');
            close.setAttribute('aria-label', locale.common('close'));
            status.textContent = '';
            if (state.gridError) status.textContent = locale.common(state.gridError);
            else if (state.gridStatus === 'loading') status.textContent = locale.common('loading');
            else if (!state.grids.length)
                status.textContent = `${locale.common('noNetworks')}. ${locale.common('noNetworksHelp')}`;
            status.hidden = !status.textContent;
            status.classList.toggle('api-error', !!state.gridError);
            const focused = options.contains(document.activeElement) ? (document.activeElement as HTMLElement) : null;
            for (const [key, choice] of choices)
                if (!state.grids.some((grid) => grid.key === key)) {
                    choice.card.remove();
                    choices.delete(key);
                }
            state.grids.forEach((grid, index) => {
                const choice = choices.get(grid.key) || createChoice();
                choices.set(grid.key, choice);
                choice.link.href = `#/grids/${encodeURIComponent(grid.key)}/items`;
                choice.name.textContent = gridLabel(grid);
                choice.disclosure.hidden = !grid.name;
                choice.count.textContent = locale.common('cpuCount', { count: grid.cpuCount });
                choice.summary.textContent = locale.common('networkIdentifier');
                choice.identity.textContent = grid.name ? grid.key : '';
                if (options.children[index] !== choice.card)
                    options.insertBefore(choice.card, options.children[index] || null);
            });
            if (focused?.isConnected && document.activeElement !== focused) focused.focus({ preventScroll: true });
        },
        dispose() {
            pins.dispose();
            dialog.close();
            dialog.remove();
            view.remove();
            panel.remove();
        }
    };
}
