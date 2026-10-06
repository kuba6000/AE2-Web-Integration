import type { TerminalState, createTerminal } from '../../app/terminal.js';
import type { Grid } from '../../app/api-types.js';
import type { Translator } from '../../app/i18n.js';
import { cpuHref } from '../../app/router.js';
import { renderMinecraftText } from './minecraft-text.js';
import { createResourceIcon, paintResourceIcon } from './resource-icon.js';

function element<Tag extends keyof HTMLElementTagNameMap>(tag: Tag, className = '') {
    const node = document.createElement(tag);
    node.className = className;
    return node;
}

/** Home presentation and chooser lifetime; discovery and CPU reads belong to the application. */
export function createHomeView(root: HTMLElement, application: ReturnType<typeof createTerminal>) {
    const view = element('section', 'window-frame home-dashboard');
    view.id = 'home';
    view.innerHTML = `<div class="terminal-heading"><h2></h2></div>
        <div class="terminal-scroll home-scroll" tabindex="0"><section class="home-work"><div class="home-section-heading"><h3></h3><span class="home-work-count"></span></div>
        <p class="home-scope"></p><p class="home-status" role="status"></p><div class="home-crafting"></div><button type="button" class="home-retry"></button></section></div>`;
    const panel = element('aside', 'home-network-panel');
    panel.id = 'home-network-panel';
    panel.innerHTML = `<h3></h3><p class="home-selected-network"></p><button type="button" class="choose-network"></button>`;
    const title = view.querySelector('h2')!;
    const description = view.querySelector('.home-scope')!;
    const workTitle = view.querySelector('.home-work h3')!;
    const workCount = view.querySelector('.home-work-count')!;
    const status = view.querySelector<HTMLElement>('.home-status')!;
    const work = view.querySelector<HTMLElement>('.home-crafting')!;
    const networkLabel = panel.querySelector('h3')!;
    const selectedNetwork = panel.querySelector('.home-selected-network')!;
    const scroll = view.querySelector<HTMLElement>('.home-scroll')!;
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
    choose.addEventListener('click', () => dialog.showModal());
    close.addEventListener('click', () => dialog.close());
    retry.addEventListener('click', () => void application.refresh());
    const choices = new Map<string, ReturnType<typeof createChoice>>();
    const groups = new Map<string, ReturnType<typeof createGroup>>();

    function gridLabel(grid: Grid, locale: Translator) {
        return locale.common('gridOwner', { owner: grid.owner || locale.common('unknownOwner') });
    }
    function gridLocation(grid: Grid, locale: Translator) {
        const position = Object.values(grid.accessSources).flat()[0]?.position;
        return position ? locale.common('position', { dimension: position.dimid, ...position }) : grid.key.slice(0, 8);
    }
    function createChoice() {
        const card = element('div', 'inset-frame network-choice');
        const link = element('a', 'network-choice-link');
        const name = element('strong');
        const location = element('span');
        const count = element('span');
        link.append(name, location, count);
        const disclosure = element('details');
        const summary = element('summary');
        const identity = element('code');
        disclosure.append(summary, identity);
        card.append(link, disclosure);
        return { card, link, name, location, count, summary, identity };
    }
    function createGroup() {
        const section = element('section', 'home-crafting-network');
        const message = element('p', 'hint');
        const list = element('ul', 'home-work-list');
        section.append(message, list);
        const icons = application.icons.observe(scroll, paintResourceIcon);
        return {
            section,
            message,
            list,
            icons,
            rows: new Map<string, ReturnType<typeof createWork>>()
        };
    }
    function createWork() {
        const li = element('li');
        const link = element('a', 'home-work-link');
        const product = element('span', 'home-product');
        const icon = createResourceIcon();
        const name = element('strong');
        product.append(icon, name);
        const detail = element('span', 'home-work-detail');
        const cpu = element('span');
        const source = element('span', 'home-work-source');
        const status = element('span', 'home-cpu-state');
        detail.append(source, cpu);
        link.append(product, status, detail);
        li.append(link);
        return { li, link, product, name, cpu, source, status };
    }
    return {
        render(state: TerminalState, locale: Translator) {
            view.hidden = state.route.view !== 'home';
            panel.hidden = view.hidden;
            if (view.hidden) {
                if (dialog.open) dialog.close();
                for (const group of groups.values()) {
                    group.icons.dispose();
                    group.section.remove();
                }
                groups.clear();
                return;
            }
            title.textContent = locale.common('home');
            description.textContent = locale.common('homeOverview');
            workTitle.textContent = locale.common('cpuOutput');
            networkLabel.textContent = locale.common('network');
            const selected = state.grids.find((grid) => grid.key === state.selectedGridKey);
            selectedNetwork.textContent = selected
                ? `${selected.owner || locale.common('unknownOwner')} · ${gridLocation(selected, locale)}`
                : locale.common('noNetworkSelected');
            choose.textContent = locale.common('chooseNetwork');
            choose.disabled = state.gridStatus !== 'ready' || !state.grids.length;
            retry.textContent = locale.common('retry');
            retry.hidden =
                state.gridStatus !== 'error' &&
                (state.gridStatus !== 'ready' || !!state.grids.length) &&
                !state.home.networks.some((network) => network.error);
            dialogTitle.textContent = locale.common('chooseNetwork');
            dialogHelp.textContent = locale.common('homeHelp');
            dialogStatus.textContent = state.gridError
                ? locale.common(state.gridError)
                : state.gridStatus === 'loading'
                  ? locale.common('loading')
                  : !state.grids.length
                    ? `${locale.common('noNetworks')}. ${locale.common('noNetworksHelp')}`
                    : '';
            dialogStatus.hidden = !dialogStatus.textContent;
            dialogRetry.hidden = state.gridStatus !== 'error' && !!state.grids.length;
            dialogRetry.textContent = locale.common('retry');
            close.setAttribute('aria-label', locale.common('close'));
            const busy = state.home.networks.reduce(
                (count, network) => count + network.cpus.filter((cpu) => cpu.isBusy).length,
                0
            );
            const loading =
                state.gridStatus === 'loading' || state.home.networks.some((network) => network.status === 'loading');
            const failed =
                state.gridStatus === 'error' || state.home.networks.some((network) => network.status === 'error');
            workCount.textContent = busy ? locale.number(busy) : '';
            status.textContent = state.gridError
                ? locale.common(state.gridError)
                : loading
                  ? locale.common('loading')
                  : !state.grids.length
                    ? `${locale.common('noNetworks')}. ${locale.common('noNetworksHelp')}`
                    : failed
                      ? locale.common('homePartial')
                      : !busy
                        ? locale.common('homeIdle')
                        : '';
            status.hidden = !status.textContent;
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
                choice.name.textContent = gridLabel(grid, locale);
                choice.location.textContent = gridLocation(grid, locale);
                choice.count.textContent = locale.common('cpuCount', { count: grid.cpuCount });
                choice.summary.textContent = locale.common('networkIdentifier');
                choice.identity.textContent = grid.key;
                if (options.children[index] !== choice.card)
                    options.insertBefore(choice.card, options.children[index] || null);
            });
            if (focused?.isConnected && document.activeElement !== focused) focused.focus({ preventScroll: true });
            for (const [key, group] of groups)
                if (!state.home.networks.some((network) => network.grid.key === key)) {
                    group.icons.dispose();
                    group.section.remove();
                    groups.delete(key);
                }
            for (const network of state.home.networks) {
                const group = groups.get(network.grid.key) || createGroup();
                groups.set(network.grid.key, group);
                group.message.textContent = network.error
                    ? `${gridLabel(network.grid, locale)} · ${gridLocation(network.grid, locale)}: ${locale.common(network.error)}`
                    : '';
                group.message.hidden = !network.error;
                const cpus = network.cpus.filter((cpu) => cpu.isBusy);
                group.section.hidden = !cpus.length && network.status !== 'error';
                for (const [key, row] of group.rows)
                    if (!cpus.some((cpu) => cpu.key === key)) {
                        row.li.remove();
                        group.rows.delete(key);
                    }
                for (const cpu of cpus) {
                    const row = group.rows.get(cpu.key) || createWork();
                    group.rows.set(cpu.key, row);
                    row.link.href = cpuHref(network.grid.key, cpu.key);
                    row.name.replaceChildren(
                        renderMinecraftText(cpu.finalOutput?.itemname || locale.common('cpuOutputUnknown'))
                    );
                    if (cpu.finalOutput) row.name.append(` × ${locale.number(cpu.finalOutput.quantity)}`);
                    row.cpu.replaceChildren(renderMinecraftText(cpu.name || locale.common('cpuUnnamed')));
                    row.source.textContent = network.grid.owner || locale.common('unknownOwner');
                    row.source.title = `${gridLabel(network.grid, locale)} · ${gridLocation(network.grid, locale)}`;
                    row.status.textContent = locale.common(cpu.isPaused ? 'cpuPausedState' : 'cpuBusy');
                    row.product.classList.toggle('has-resource-icon', !!network.icons && !!cpu.finalOutput);
                    if (!row.li.parentNode) group.list.append(row.li);
                }
                group.icons.update(
                    cpus
                        .filter((cpu) => cpu.finalOutput)
                        .map((cpu) => ({ element: group.rows.get(cpu.key)!.product, icon: cpu.icon })),
                    network.icons
                );
                if (!group.section.parentNode) work.append(group.section);
            }
        },
        dispose() {
            for (const group of groups.values()) group.icons.dispose();
            dialog.close();
            dialog.remove();
            view.remove();
            panel.remove();
        }
    };
}
