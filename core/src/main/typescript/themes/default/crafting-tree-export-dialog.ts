import { element } from './dom.js';
import type { TerminalDisplay } from './web-settings.js';
import { craftingTreeContent, craftingTreeExportNotes } from './crafting-tree-content.js';
import { layoutCraftingTree } from './crafting-tree-layout.js';
import {
    renderTreePng,
    renderTreeSvg,
    treeSvgSize,
    treePngSize,
    type TreeExportScene,
    type TreeExportSettings,
    type TreeExportStyle
} from './crafting-tree-export.js';
import { readPlanExportSettings } from '../../app/plan-export-layout.js';
import { plainMinecraftText } from '../../app/minecraft-text.js';
import type { ThemeApplication, ThemeSettings } from '../../app/theme-api.js';
import type { Translator } from '../../app/i18n.js';
import { svgFonts } from './svg-fonts.js';

export function createTreeExportDialog(
    application: ThemeApplication,
    store: ThemeSettings,
    beforeDialog: (source: HTMLElement) => HTMLElement
) {
    const dialog = element('dialog', '', 'window-frame plan-export-dialog');
    const title = element('h2');
    title.id = 'tree-export-title';
    dialog.setAttribute('aria-labelledby', title.id);
    const closeButton = element('button', '×', 'close-dialog');
    closeButton.type = 'button';
    const heading = element('div', '', 'craft-dialog-heading');
    heading.append(title, closeButton);
    const fields = element('div', '', 'plan-export-fields');
    const scope = element('p');
    const readout = element('p');
    const message = element('p');
    message.role = 'status';
    const download = element('button');
    download.type = 'button';
    dialog.append(heading, scope, fields, readout, message, download);
    document.body.append(dialog);
    let operation: AbortController | null = null;
    let scene: TreeExportScene | null = null;
    let settings: TreeExportSettings;
    let style: TreeExportStyle;
    let locale: Translator;
    let display: TerminalDisplay = 'names';
    let opener: HTMLElement | null = null;
    let origin: { route: string; captureId: string | null } | null = null;
    const routeKey = () => {
        const route = application.state.route;
        return route.view === 'plan' ? `${route.gridKey}/${route.planId}` : '';
    };
    const current = () =>
        !!origin &&
        routeKey() === origin.route &&
        application.state.crafting.tree.captureId === origin.captureId &&
        application.state.crafting.tree.visible &&
        application.state.crafting.tree.status === 'READY' &&
        !application.state.crafting.mutation;
    function stop() {
        operation?.abort();
        operation = null;
        fields.inert = false;
    }
    function close() {
        stop();
        scene = null;
        origin = null;
        if (dialog.open) dialog.close();
    }
    closeButton.onclick = close;
    dialog.addEventListener('cancel', close);
    dialog.addEventListener('close', () => {
        stop();
        scene = null;
        origin = null;
        opener?.focus({ preventScroll: true });
    });
    function refresh() {
        const size = scene
            ? settings.format === 'svg'
                ? treeSvgSize(scene, settings.scale, settings.caption)
                : treePngSize(scene, settings.scale, settings.caption)
            : null;
        readout.textContent = size ? `${size.width} × ${size.height} px` : locale.t('exportTooLarge');
        download.disabled = !size || !!operation;
        download.textContent = `${locale.t('exportDownload')} ${settings.format.toUpperCase()}`;
    }
    function toggle(key: 'icon' | 'name' | 'colors' | 'caption', label: string) {
        const input = element('input');
        input.type = 'checkbox';
        input.checked = settings[key];
        const row = element('label', '', 'checkbox');
        row.append(input, element('span', label));
        fields.append(row);
        input.onchange = () => {
            settings[key] = input.checked;
            refresh();
        };
    }
    download.onclick = () => {
        if (!scene || operation || !current()) return;
        const snapshot = scene;
        const captured = { ...settings };
        const controller = new AbortController();
        operation = controller;
        fields.inert = true;
        download.disabled = true;
        message.textContent = locale.t('exportProgress');
        void (captured.format === 'svg' ? renderTreeSvg : renderTreePng)(
            snapshot,
            captured,
            style,
            application.icons,
            controller.signal
        )
            .then((result) => {
                if (controller.signal.aborted || !current()) return;
                const url = URL.createObjectURL(result.blob);
                const link = element('a');
                link.href = url;
                const name =
                    plainMinecraftText(snapshot.title)
                        .replace(/[<>:"/\\|?*\x00-\x1f]/g, '_')
                        .slice(0, 80)
                        .replace(/[. ]+$/, '') || 'crafting';
                link.download = `${name}-tree-${Date.now()}.${captured.format}`;
                document.body.append(link);
                link.click();
                link.remove();
                setTimeout(() => URL.revokeObjectURL(url), 1000);
                const { caption, ...sharedSettings } = captured;
                store.set('treeExport.caption', caption);
                for (const [key, value] of Object.entries(sharedSettings)) store.set(`planExport.${key}`, value);
                message.textContent =
                    result.scale < captured.scale
                        ? locale.t('exportScaleReduced', { scale: result.scale })
                        : locale.t('exportDone');
            })
            .catch((error) => {
                if (!controller.signal.aborted)
                    message.textContent = locale.t(
                        error instanceof Error && error.message === 'too-large' ? 'exportTooLarge' : 'exportFailed'
                    );
            })
            .finally(() => {
                if (operation === controller) {
                    operation = null;
                    fields.inert = false;
                    refresh();
                }
            });
    };
    return {
        setDisplay(value: TerminalDisplay) {
            display = value;
        },
        open(source: HTMLElement, translator: Translator) {
            const state = application.state.crafting;
            if (state.tree.status !== 'READY' || !state.tree.rows.length) return;
            stop();
            locale = translator;
            const t = locale.common;
            const tree = state.tree;
            settings = {
                ...readPlanExportSettings((key) => store.get(key)),
                caption: store.get('treeExport.caption') !== false
            };
            origin = { route: routeKey(), captureId: tree.captureId };
            const notes = craftingTreeExportNotes(tree, locale);
            scene = {
                layout: layoutCraftingTree(tree.rows, display),
                display,
                cards: tree.rows.map((row) => {
                    const card = craftingTreeContent(row, tree, locale, document.documentElement.lang);
                    return { ...card, icon: card.icon ? { ...card.icon } : null };
                }),
                // Both export formats are still images; animation sequences are not part of the snapshot.
                icons: tree.icons
                    ? {
                          packId: tree.icons.packId,
                          width: tree.icons.width,
                          height: tree.icons.height,
                          pages: tree.icons.pages.map((page) => ({ ...page }))
                      }
                    : null,
                title: state.plan?.finalOutput.displayName ?? t('craftingPlan'),
                ...(state.plan?.finalOutput.icon
                    ? {
                          header: {
                              icon: { ...state.plan.finalOutput.icon },
                              icons: state.icons
                                  ? {
                                        packId: state.icons.packId,
                                        width: state.icons.width,
                                        height: state.icons.height,
                                        pages: state.icons.pages.map((page) => ({ ...page }))
                                    }
                                  : null
                          }
                      }
                    : {}),
                scope: notes
            };
            const computed = getComputedStyle(dialog);
            const value = (name: string) => computed.getPropertyValue(name).trim();
            style = {
                fontFamily: computed.fontFamily,
                fonts: svgFonts(computed.fontFamily),
                foreground: value('--surface-text'),
                background: value('--panel'),
                tile: value('--slot'),
                border: value('--slot-edge-light'),
                missing: value('--tree-missing'),
                missingBorder: value('--tree-missing-border'),
                missingText: value('--tree-missing-text'),
                blocked: value('--tree-blocked'),
                recipe: value('--tree-recipe'),
                recipeBorder: value('--tree-recipe-border'),
                textColors: {}
            };
            fields.replaceChildren();
            const formatLabel = element('label');
            const format = element('select');
            for (const value of ['png', 'svg']) {
                const option = element('option', value.toUpperCase());
                option.value = value;
                format.append(option);
            }
            format.value = settings.format;
            formatLabel.append(element('span', locale.t('exportFormat')), format);
            fields.append(formatLabel);
            format.onchange = () => {
                settings.format = format.value as 'png' | 'svg';
                refresh();
            };
            const sizeLabel = element('label');
            const size = element('select');
            for (const amount of [0.5, 1, 1.5, 2, 3, 4]) {
                const option = element('option', `${amount}×`);
                option.value = String(amount);
                size.append(option);
            }
            size.value = String(settings.scale);
            sizeLabel.append(element('span', t('treeExportScale')), size);
            fields.append(sizeLabel);
            size.onchange = () => {
                settings.scale = Number(size.value);
                refresh();
            };
            toggle('caption', t('treeExportCaption'));
            toggle('icon', t('treeExportIcons'));
            if (display !== 'icons') toggle('name', t('treeExportResourceNames'));
            toggle('colors', t('treeExportColors'));
            const background = element('input');
            background.type = 'checkbox';
            background.checked = settings.background === 'theme';
            const backgroundLabel = element('label', '', 'checkbox');
            backgroundLabel.append(background, element('span', t('treeExportBackground')));
            fields.append(backgroundLabel);
            background.onchange = () => {
                settings.background = background.checked ? 'theme' : 'transparent';
                refresh();
            };
            title.textContent = t('treeExportTitle');
            scope.textContent = notes.join(' ');
            closeButton.setAttribute('aria-label', t('close'));
            download.textContent = locale.t('exportDownload');
            message.textContent = '';
            opener = beforeDialog(source);
            dialog.showModal();
            refresh();
        },
        close,
        check() {
            if (dialog.open && !current()) close();
        },
        dispose() {
            close();
            dialog.remove();
        }
    };
}
