import type { ThemeState, Translator } from '../../app/theme-api.js';
import { element } from './dom.js';

type Pin = ThemeState['pins']['items'][number];
type History = ThemeState['resourceHistory'];

/** Exact counts and coverage for the two independently configured Home flow summaries. */
export function createHomePinFlow(host: HTMLElement) {
    function section(className: string) {
        const root = element('section', '', className);
        const line = element('div', '', 'home-pin-flow-line');
        const heading = element('span', '', 'home-pin-flow-label');
        const values = element('span', '', 'home-pin-flow-values');
        const note = element('p', '', 'hint recent-note');
        line.append(heading, values);
        root.append(line, note);
        host.append(root);
        return { root, line, heading, values, note };
    }
    const recent = section('home-pin-recent'),
        historical = section('home-pin-historical');
    function counts(
        target: ReturnType<typeof section>,
        input: bigint | null,
        output: bigint | null,
        locale: Translator
    ) {
        target.values.replaceChildren();
        const number = new Intl.NumberFormat(document.documentElement.lang);
        const compact = new Intl.NumberFormat(document.documentElement.lang, {
            notation: 'compact',
            maximumFractionDigits: 1
        });
        const net = input === null || output === null ? null : input - output;
        for (const [kind, label, value, sign] of [
            ['input', locale.common('resourceHistoryInput'), input, input],
            ['output', locale.common('resourceHistoryOutput'), output, output === null ? null : -output],
            ['net', locale.t('trackingNet'), net, net]
        ] as const) {
            let color = 'tracking-neutral';
            let prefix = '';
            if (sign !== null && sign > 0n) {
                color = 'tracking-positive';
                prefix = '+';
            } else if (sign !== null && sign < 0n) {
                color = 'tracking-negative';
                prefix = '−';
            }
            const magnitude = value === null ? null : value < 0n ? -value : value;
            const shown = magnitude === null ? '—' : prefix + compact.format(magnitude);
            const exact = magnitude === null ? '—' : prefix + number.format(magnitude);
            const count = element('span', shown, color);
            count.dataset.flow = kind;
            count.setAttribute('aria-label', `${label}: ${exact}`);
            count.title = `${label}: ${exact}`;
            if (target.values.childNodes.length) target.values.append(document.createTextNode(' / '));
            target.values.append(count);
        }
    }
    return {
        render(pin: Pin, history: History | undefined, locale: Translator) {
            recent.root.hidden = !pin.available || !pin.homeConfig.showRecentFlow;
            historical.root.hidden = !pin.available || !pin.homeConfig.showFlow;
            for (const section of [recent, historical])
                if (section.root.hidden) {
                    section.values.replaceChildren();
                    section.note.textContent = '';
                    section.line.title = '';
                }
            if (!recent.root.hidden) {
                const sample = pin.recent;
                const measured = sample && pin.itemKey ? sample.resources[pin.itemKey] : undefined;
                recent.heading.textContent = `${locale.t('trackingFlow')} ${locale.duration(sample?.flowWindowMillis ?? 120000)}:`;
                counts(
                    recent,
                    measured && measured.input !== null ? BigInt(measured.input) : null,
                    measured && measured.output !== null ? BigInt(measured.output) : null,
                    locale
                );
                const notes = [];
                recent.line.title =
                    sample && sample.flowObservedAt !== null
                        ? `${locale.common('pinSampleTime')}: ${locale.dateTime(sample.flowObservedAt, sample.observedAt)}`
                        : '';
                if (sample && sample.flowSince !== null && sample.flowObservedAt !== null) {
                    const duration = Math.min(
                        sample.flowWindowMillis,
                        Math.max(0, sample.flowObservedAt - sample.flowSince)
                    );
                    if (!sample.flowComplete || duration < sample.flowWindowMillis)
                        notes.push(
                            locale.t(sample.flowComplete ? 'trackingMeasuredPeriod' : 'trackingIncompletePeriod', {
                                period: locale.duration(duration)
                            })
                        );
                }
                recent.note.textContent = notes.join(' · ');
                recent.note.hidden = !notes.length;
            }
            if (!historical.root.hidden) {
                historical.heading.textContent = `${locale.common('pinHistoricalFlow')} ${locale.duration(pin.homeConfig.flowRangeMillis)}:`;
                let input = 0n,
                    output = 0n,
                    observed = false;
                const notes = [];
                if (history?.status === 'ready') {
                    for (const point of history.points) {
                        if (point.observedAt <= history.from || point.observedAt > history.until) continue;
                        if (point.kind === 'FLOW' || point.kind === 'FLOW_OBSERVATION') observed = true;
                        if (point.kind === 'FLOW') {
                            input += BigInt(point.input);
                            output += BigInt(point.output);
                        }
                    }
                    if (!history.enabled) notes.push(locale.common('resourceHistoryDisabled'));
                    if (history.truncated) notes.push(locale.common('resourceHistoryTruncated'));
                } else if (history?.error) notes.push(locale.common(history.error));
                else if (history?.status === 'loading') notes.push(locale.common('loading'));
                counts(historical, observed ? input : null, observed ? output : null, locale);
                historical.note.textContent = notes.join(' · ');
                historical.note.hidden = !notes.length;
            }
        }
    };
}
