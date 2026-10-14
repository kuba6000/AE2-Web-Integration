import type { ThemeState } from '../../app/theme-api.js';
import type { Translator } from '../../app/i18n.js';
import { element } from './dom.js';

export function createResourceHistoryView(host: HTMLElement) {
    const section = element('details', '', 'resource-history');
    const title = element('summary');
    const status = element('p', '', 'hint');
    const settings = element('a');
    const baseline = element('p', '', 'resource-history-baseline');
    const rows = element('ol');
    rows.className = 'resource-history-points';
    section.append(title, status, settings, baseline, rows);
    host.append(section);
    let previousPoints: ThemeState['resourceHistory']['points'] | undefined;
    let previousBaseline: ThemeState['resourceHistory']['baseline'] | undefined;
    let previousSignature = '';
    return {
        render(state: ThemeState, locale: Translator, language: string) {
            const history = state.resourceHistory;
            section.hidden = !state.selected?.itemKey;
            const signature = [
                language,
                state.route.gridKey,
                history.status,
                history.enabled,
                history.complete,
                history.truncated,
                history.error
            ].join('\0');
            if (
                previousPoints === history.points &&
                previousBaseline === history.baseline &&
                previousSignature === signature
            )
                return;
            previousPoints = history.points;
            previousBaseline = history.baseline;
            previousSignature = signature;
            title.textContent = locale.common('resourceHistory');
            rows.replaceChildren();
            settings.textContent = locale.common('gridSettings');
            settings.href = `#/grids/${encodeURIComponent(state.route.gridKey ?? '')}/settings`;
            settings.hidden = history.enabled || history.status !== 'ready';
            let message = '';
            if (history.status === 'loading') message = locale.common('loading');
            else if (history.error) message = locale.common(history.error);
            else if (!history.enabled) message = locale.common('resourceHistoryDisabled');
            else if (!history.points.length && !history.baseline?.resource)
                message = locale.common('resourceHistoryEmpty');
            if (history.status === 'ready' && !history.complete) message += ` ${locale.common('resourceHistoryGap')}`;
            if (history.truncated) message += ` ${locale.common('resourceHistoryTruncated')}`;
            status.textContent = message.trim();
            status.hidden = !status.textContent;
            const numbers = new Intl.NumberFormat(language);
            baseline.hidden = !history.baseline?.resource;
            baseline.textContent = history.baseline?.resource
                ? locale.common('resourceHistoryBaseline', {
                      quantity: numbers.format(BigInt(history.baseline.resource.quantity)),
                      time: locale.dateTime(history.baseline.observedAt)
                  })
                : '';
            for (const point of [...history.points].reverse()) {
                const row = element('li');
                row.append(element('time', locale.dateTime(point.observedAt)));
                let text: string;
                if (point.kind === 'STOCK' && point.resource) {
                    text = `${locale.common('stored')}: ${numbers.format(BigInt(point.resource.quantity))}`;
                } else if (point.kind === 'FLOW') {
                    text = `${locale.common('resourceHistoryInput')}: ${numbers.format(BigInt(point.input))} · ${locale.common('resourceHistoryOutput')}: ${numbers.format(BigInt(point.output))}`;
                } else
                    text = locale.common(
                        point.kind === 'GAP'
                            ? 'resourceHistoryGap'
                            : point.kind === 'FLOW_OBSERVATION'
                              ? 'resourceHistoryFlowObservation'
                              : 'resourceHistoryObservation'
                    );
                row.append(element('span', text));
                if (!point.complete && point.kind !== 'GAP')
                    row.append(element('span', locale.common('resourceHistoryGap'), 'hint'));
                rows.append(row);
            }
        }
    };
}
