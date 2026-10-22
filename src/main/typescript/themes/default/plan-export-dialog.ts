import type {
    ThemeApplication,
    ThemeSettings,
    ThemeState,
    PlanExportStyle,
    PlanExportSettings,
    PlanExportGrid
} from '../../app/theme-api.js';
import type { Translator } from '../../app/i18n.js';
import {
    planExportLayout,
    planExportFits,
    planExportRatio,
    readPlanExportSettings,
    smallerPlanExportLayout
} from '../../app/plan-export-layout.js';
import { element } from './dom.js';
import { slotQuantity } from './resource-quantity.js';
import { planExportStyle } from './plan-export-style.js';

export function createPlanExportDialog(
    application: ThemeApplication,
    settingsStore: ThemeSettings,
    beforeDialog: (source: HTMLElement) => HTMLElement
) {
    const dialog = element('dialog', '', 'window-frame plan-export-dialog');
    const title = element('h2');
    title.id = 'plan-export-title';
    dialog.setAttribute('aria-labelledby', title.id);
    const closeButton = element('button', '×', 'close-dialog');
    closeButton.type = 'button';
    const heading = element('div', '', 'craft-dialog-heading');
    heading.append(title, closeButton);
    const form = element('form');
    const fields = element('div', '', 'plan-export-fields');
    const readout = element('p', '', 'plan-export-readout');
    readout.role = 'status';
    readout.setAttribute('aria-live', 'polite');
    const message = element('p');
    message.role = 'status';
    const submit = element('button');
    submit.type = 'submit';
    form.append(fields, readout, message, submit);
    dialog.append(heading, form);
    document.body.append(dialog);
    let locale: Translator;
    let settings: PlanExportSettings;
    let grid: PlanExportGrid;
    let snapshot: ThemeState['crafting']['plan'];
    let metadata: ThemeState['crafting']['icons'];
    let style: PlanExportStyle;
    let opener: HTMLElement | null = null;
    let operation: AbortController | undefined;
    let axis: HTMLSelectElement;
    let count: HTMLInputElement;
    let gridLabel: HTMLSpanElement;
    const stop = () => {
        operation?.abort();
        operation = undefined;
        fields.inert = false;
    };
    const close = () => {
        stop();
        dialog.close();
    };
    closeButton.addEventListener('click', close);
    dialog.addEventListener('cancel', stop);
    dialog.addEventListener('close', () => {
        stop();
        opener?.focus({ preventScroll: true });
    });

    function currentLayout() {
        const rows = snapshot?.plan?.filter((row) => !settings.onlyMissing || row.missing > 0) ?? [];
        return planExportLayout(rows.length, settings, grid, style.geometry);
    }
    function refresh() {
        const t = locale.t;
        style = planExportStyle(dialog, effectiveSettings(), snapshot!, !!metadata && settings.icon);
        let layout = currentLayout();
        while (layout && !planExportFits(layout)) layout = smallerPlanExportLayout(layout, style.geometry);
        submit.disabled = !layout || !!operation;
        count.disabled = axis.value === 'square';
        if (axis.value === 'square' && layout) count.value = String(layout.columns);
        gridLabel.textContent = t(axis.value === 'rows' ? 'exportRows' : 'exportColumns');
        if (!layout) {
            const empty = !snapshot?.plan?.some((row) => !settings.onlyMissing || row.missing > 0);
            readout.textContent = t(empty ? 'exportEmptyPlan' : 'exportTooLarge');
            return;
        }
        readout.textContent = `${locale.number(layout.width)} × ${locale.number(layout.height)} px · ${locale.number((layout.width * layout.height) / 1_000_000)} MP · ${t('exportRatio')}: ${planExportRatio(layout.width, layout.height)}`;
        message.textContent = layout.scale < settings.scale ? t('exportScaleReduced', { scale: layout.scale }) : '';
    }
    function translate(key: string) {
        return key.startsWith('export') ? locale.t(key) : locale.common(key);
    }
    function fieldset(label: string) {
        const group = element('fieldset');
        group.append(element('legend', translate(label)));
        fields.append(group);
        return group;
    }
    function label(group: HTMLElement, text: string, control: HTMLElement) {
        const node = element('label');
        node.append(element('span', translate(text)), control);
        group.append(node);
        return node;
    }
    function select(
        group: HTMLElement,
        key: keyof PlanExportSettings,
        text: string,
        choices: readonly (string | number)[]
    ) {
        const control = element('select');
        for (const choice of choices) {
            const option = element(
                'option',
                typeof choice === 'number' ? `${locale.number(choice)}×` : locale.t(`exportOption_${choice}`)
            );
            option.value = String(choice);
            control.append(option);
        }
        control.value = choices.includes(settings[key] as string | number) ? String(settings[key]) : String(choices[0]);
        label(group, text, control);
        control.addEventListener('change', () => {
            Object.assign(settings, {
                [key]: typeof settings[key] === 'number' ? Number(control.value) : control.value
            });
            refresh();
        });
    }
    function toggle(group: HTMLElement, key: keyof PlanExportSettings, text: string, disabled = false) {
        const control = element('input');
        control.type = 'checkbox';
        control.checked = Boolean(settings[key]) && !disabled;
        control.disabled = disabled;
        const node = label(group, text, control);
        node.className = 'checkbox';
        control.addEventListener('change', () => {
            Object.assign(settings, { [key]: control.checked });
            refresh();
        });
    }
    function open(source: HTMLElement, currentLocale: Translator) {
        const state = application.state.crafting;
        if (state.status !== 'ready' || !state.plan?.plan?.length) return;
        stop();
        locale = currentLocale;
        snapshot = state.plan;
        metadata = state.icons;
        settings = readPlanExportSettings((key) => settingsStore.get(key));
        const supportsSteps = application.state.capabilities.craftingPlanSteps === true;
        style = planExportStyle(dialog, effectiveSettings(), snapshot, !!metadata && settings.icon);
        const ratio = settings.ratio.split(':').map(Number);
        grid = settings.ratio === 'square' ? { axis: 'square' } : { axis: 'ratio', ratio: ratio[0] / ratio[1] };
        opener = beforeDialog(source);
        title.textContent = locale.t('exportPlan');
        closeButton.setAttribute('aria-label', locale.common('close'));
        submit.textContent = locale.t('exportDownload');
        fields.replaceChildren();
        fields.inert = false;
        const geometry = fieldset('exportGrid');
        axis = element('select');
        for (const value of ['square', 'columns', 'rows']) {
            const option = element('option', locale.t(`export${value[0].toUpperCase()}${value.slice(1)}`));
            option.value = value;
            axis.append(option);
        }
        axis.value = grid.axis === 'ratio' ? 'columns' : 'square';
        label(geometry, 'exportLayout', axis);
        count = element('input');
        count.type = 'number';
        count.min = '1';
        count.step = '1';
        count.max = String(snapshot.plan!.length);
        count.value = String(currentLayout()?.columns ?? 1);
        if (grid.axis === 'ratio') grid = { axis: 'columns', value: Number(count.value) };
        const countLabel = label(geometry, 'exportColumns', count);
        gridLabel = countLabel.querySelector('span')!;
        axis.addEventListener('change', () => {
            const layout = currentLayout();
            count.value = String(axis.value === 'rows' ? (layout?.rows ?? 1) : (layout?.columns ?? 1));
            grid =
                axis.value === 'square'
                    ? { axis: 'square' }
                    : { axis: axis.value as 'rows' | 'columns', value: Number(count.value) };
            refresh();
        });
        count.addEventListener('input', () => {
            grid = { axis: axis.value as 'rows' | 'columns', value: Number(count.value) };
            refresh();
        });
        select(geometry, 'scale', 'exportScale', [0.5, 1, 1.5, 2, 3, 4]);
        const content = fieldset('exportContent');
        toggle(content, 'icon', 'exportIcon', !metadata);
        toggle(content, 'name', 'name');
        for (const key of ['missing', 'requested', 'stored', 'steps', 'usage'] as const)
            if (key !== 'steps' || supportsSteps) toggle(content, key, `exportMetric_${key}`);
        select(content, 'numbers', 'exportNumbers', ['short', 'full']);
        const order = fieldset('sort');
        select(order, 'sort', 'sort', supportsSteps ? ['name', 'quantity', 'steps'] : ['name', 'quantity']);
        select(order, 'direction', 'sortOrder', ['ascending', 'descending']);
        toggle(order, 'group', 'exportGroup');
        toggle(order, 'onlyMissing', 'exportOnlyMissing');
        const appearance = fieldset('exportAppearance');
        toggle(appearance, 'header', 'exportHeader');
        toggle(appearance, 'colors', 'exportColors');
        select(appearance, 'background', 'exportBackground', ['theme', 'transparent']);
        dialog.showModal();
        refresh();
        closeButton.focus();
    }
    function effectiveSettings(): PlanExportSettings {
        const supportsSteps = application.state.capabilities.craftingPlanSteps === true;
        return {
            ...settings,
            icon: settings.icon && !!metadata,
            steps: settings.steps && supportsSteps,
            sort: settings.sort === 'steps' && !supportsSteps ? 'name' : settings.sort
        };
    }
    form.addEventListener('submit', (event) => {
        event.preventDefault();
        if (operation || !snapshot || !currentLayout()) return;
        const controller = new AbortController();
        operation = controller;
        submit.disabled = true;
        fields.inert = true;
        message.textContent = locale.t('exportProgress');
        const captured = effectiveSettings();
        void application
            .exportPlan(
                {
                    plan: snapshot,
                    icons: metadata,
                    settings: captured,
                    grid,
                    language: document.documentElement.lang,
                    style,
                    title: locale.t('exportTitle', {
                        name: snapshot.finalOutput.displayName,
                        quantity: locale.number(snapshot.finalOutput.quantity)
                    }),
                    labels: {
                        missing: locale.common('missingAmount'),
                        requested: locale.common('requested'),
                        stored: locale.common('stored'),
                        steps: locale.common('planSteps'),
                        usage: locale.t('exportMetric_usage')
                    },
                    number: (value) =>
                        captured.numbers === 'full' ? locale.number(value) : slotQuantity(value, locale)
                },
                controller.signal
            )
            .then((layout) => {
                if (controller.signal.aborted) return;
                settings.ratio = grid.axis === 'square' ? 'square' : `${layout.width}:${layout.height}`;
                for (const [key, value] of Object.entries(settings)) settingsStore.set(`planExport.${key}`, value);
                message.textContent =
                    layout.scale < settings.scale
                        ? locale.t('exportScaleReduced', { scale: layout.scale })
                        : locale.t('exportDone');
            })
            .catch((error: unknown) => {
                if (!controller.signal.aborted)
                    message.textContent = locale.t(
                        error instanceof Error && error.message === 'too-large' ? 'exportTooLarge' : 'exportFailed'
                    );
            })
            .finally(() => {
                if (operation === controller) {
                    operation = undefined;
                    fields.inert = false;
                    submit.disabled = !currentLayout();
                }
            });
    });
    return {
        open,
        close,
        dispose() {
            close();
            dialog.remove();
        }
    };
}
