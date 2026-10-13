import type { ThemeState, ThemeApplication } from '../../app/theme-api.js';
import type { Translator } from '../../app/i18n.js';
import { element } from './dom.js';
import { createResourceIcon, paintResourceIcon } from './resource-icon.js';
import { plainMinecraftText } from '../../app/minecraft-text.js';
import { renderMinecraftText } from './minecraft-text.js';
import { cpuHref } from '../../app/router.js';
import { renderCpuActions, renderCpuState, cpuOutcomeText, renderCpuSelectionMode } from './cpu-presentation.js';
import type { CpuTooltips } from './cpu-tooltips.js';

export function createCpuOverview(application: ThemeApplication, tips: CpuTooltips) {
    const overview = element('div', '', 'cpu-overview');
    const overviewHeader = element('div', '', 'terminal-heading');
    const title = element('h2');
    overviewHeader.append(title);
    const overviewBody = element('div', '', 'terminal-body');
    const listStatus = element('p', '', 'cpu-list-status');
    listStatus.role = 'status';
    const listScroll = element('div', '', 'terminal-scroll');
    listScroll.role = 'region';
    listScroll.tabIndex = 0;
    const list = element('ul', '', 'cpu-list');
    listScroll.append(list);
    const overviewIcons = application.icons.observe(listScroll, paintResourceIcon);
    overviewBody.append(listStatus, listScroll);
    overview.append(overviewHeader, overviewBody);
    const overviewSize = new ResizeObserver(() => {
        const bounds = list.getBoundingClientRect();
        if (bounds.width === 0) return;
        overview.style.setProperty('--grid-inset', `${bounds.left - overview.getBoundingClientRect().left}px`);
    });
    overviewSize.observe(listScroll);

    type CpuRow = ReturnType<typeof createOverviewRow>;
    let rows = new Map<string, CpuRow>();
    let state: ThemeState['cpus'];
    let locale: Translator;
    function createOverviewRow() {
        const li = element('li', '', 'inset-frame cpu-card');
        const header = element('div', '', 'cpu-card-heading');
        const heading = element('h3');
        const link = element('a', '', 'cpu-card-link');
        const badge = element('span', '', 'cpu-card-state');
        const mode = element('span');
        tips.bindTool(mode);
        header.append(heading, mode, badge);
        const work = element('div', '', 'cpu-card-output');
        const outputLabel = element('p', '', 'cpu-card-label');
        const output = element('p', '', 'cpu-card-output-value');
        const icon = createResourceIcon();
        work.append(outputLabel, output);
        const metrics = element('dl', '', 'cpu-card-metrics');
        const fields = ['cpuCapacityLabel', 'cpuUsedStorageLabel', 'cpuCoprocessorsLabel'].map((label) => {
            const group = element('div');
            const term = element('dt');
            const value = element('dd');
            group.append(term, value);
            metrics.append(group);
            return { label, term, value };
        });
        const identity = element('details', '', 'cpu-card-identity');
        const identityLabel = element('summary');
        const key = element('code');
        identity.append(identityLabel, key);
        const actions = element('div');
        const notice = element('p', '', 'cpu-card-notice');
        notice.role = 'status';
        link.append(header, work, metrics);
        li.append(link, identity, actions, notice);
        return {
            li,
            link,
            heading,
            badge,
            mode,
            outputLabel,
            output,
            icon,
            fields,
            identityLabel,
            key,
            actions,
            notice
        };
    }

    function renderOverviewRow(entry: CpuRow, cpu: ThemeState['cpus']['cpus'][number], gridKey: string) {
        const t = locale.common;
        const displayName = plainMinecraftText(cpu.name).trim() ? cpu.name : t('cpuUnnamed');
        const identity = `${plainMinecraftText(displayName)} · ${cpu.key}`;
        entry.link.href = cpuHref(gridKey, cpu.key);
        entry.heading.replaceChildren(renderMinecraftText(displayName));
        entry.link.setAttribute('aria-label', identity);
        entry.link.title = cpu.key;
        entry.li.dataset.state = cpu.isBusy ? (cpu.isPaused ? 'paused' : 'busy') : 'idle';
        renderCpuState(entry.badge, cpu.isBusy, cpu.isPaused, locale);
        renderCpuSelectionMode(
            entry.mode,
            application.state.capabilities.cpuSelectionMode ? cpu.selectionMode : null,
            locale
        );
        tips.refreshTool(entry.mode);
        entry.outputLabel.textContent = t('cpuOutput');
        entry.output.replaceChildren();
        if (cpu.isBusy && cpu.finalOutput) {
            const name = element('strong');
            name.append(renderMinecraftText(cpu.finalOutput.displayName));
            entry.output.append(
                entry.icon,
                name,
                element('span', `× ${locale.number(cpu.finalOutput.quantity)}`, 'cpu-card-quantity')
            );
        } else {
            entry.output.textContent = t(cpu.isBusy ? 'cpuOutputUnknown' : 'cpuIdleMessage');
        }
        const values = [
            locale.bytes(cpu.availableStorage),
            cpu.usedStorage >= 0 ? locale.bytes(cpu.usedStorage) : t('cpuValueUnavailable'),
            locale.number(cpu.coProcessors)
        ];
        entry.fields.forEach((field, index) => {
            field.term.textContent = t(field.label);
            field.value.textContent = values[index];
        });
        entry.identityLabel.textContent = t('cpuIdentifier');
        entry.key.textContent = cpu.key;
        renderCpuActions(entry.actions, cpu.key, cpu, state, locale, application.cpus, identity);
        entry.notice.textContent = cpuOutcomeText(state.outcomes[cpu.key], state.status, locale);
        entry.notice.hidden = !entry.notice.textContent;
        entry.notice.classList.toggle(
            'api-error',
            !!state.outcomes[cpu.key]?.uncertain ||
                (!!state.outcomes[cpu.key]?.notice && state.outcomes[cpu.key].notice !== 'cpuCancelled')
        );
    }

    return {
        element: overview,
        render(route: ThemeState['route'], next: ThemeState['cpus'], translator: Translator) {
            state = next;
            locale = translator;
            const selected = route.view === 'cpus' && !!route.cpuKey;
            overview.hidden = selected;
            if (route.view !== 'cpus' || selected) overviewIcons.update([], null);
            if (route.view !== 'cpus') return;
            if (selected) {
                list.replaceChildren();
                rows.clear();
                return;
            }
            const t = locale.common;
            title.textContent = t('cpus');
            listScroll.setAttribute('aria-label', t('cpus'));
            let message = '';
            if (state.error) message = t(state.error);
            else if (!state.cpus.length) message = t(state.status === 'loading' ? 'loading' : 'noCpus');
            listStatus.textContent = message;
            listStatus.hidden = !message;
            const current: Map<string, CpuRow> = new Map();
            for (const cpu of state.cpus) {
                const entry = rows.get(cpu.key) || createOverviewRow();
                renderOverviewRow(entry, cpu, route.gridKey);
                if (!entry.li.parentNode) list.append(entry.li);
                current.set(cpu.key, entry);
            }
            for (const [key, entry] of rows) if (!current.has(key)) entry.li.remove();
            rows = current;
            overviewIcons.update(
                state.cpus
                    .filter((cpu) => cpu.isBusy && cpu.finalOutput)
                    .map((cpu) => ({ element: current.get(cpu.key)!.output, icon: cpu.finalOutput?.icon })),
                state.overviewIcons
            );
        },
        dispose() {
            overviewSize.disconnect();
            overviewIcons.dispose();
            overview.remove();
        }
    };
}
