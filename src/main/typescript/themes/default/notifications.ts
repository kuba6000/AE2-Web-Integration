import type { Notification, ReadonlyData, ThemeApplication, ThemeLifetime, Translator } from '../../app/theme-api.js';
import { plainMinecraftText } from '../../app/minecraft-text.js';
import { element } from './dom.js';

export function createNotifications(
    application: ThemeApplication,
    lifetime: ThemeLifetime,
    header: HTMLElement,
    workspace: HTMLElement
) {
    const root = element('div', '', 'notifications');
    root.setAttribute('role', 'region');
    root.hidden = true;
    let locale: Translator | undefined;
    let items: readonly ReadonlyData<Notification>[] = [];
    const rows = new Map<number, { row: HTMLButtonElement; title: HTMLElement; detail: HTMLElement }>();
    function position() {
        if (root.hidden) return;
        const bounds = header.getBoundingClientRect();
        const availableWidth = window.innerWidth - bounds.right - 24;
        const beside = window.innerWidth > 600 && availableWidth >= 220;
        const top = Math.round(beside ? bounds.top : bounds.bottom + 8);
        let height = window.innerHeight - top - 12;
        root.style.top = `${top}px`;
        root.style.left = beside ? `${Math.ceil(bounds.right + 12)}px` : '';
        root.style.right = beside ? 'auto' : '';
        root.style.width = beside ? `${Math.floor(Math.min(360, availableWidth))}px` : '';
        // The empty area beside the header can end at the selected view's attachment.
        if (beside) {
            for (const panel of workspace.querySelectorAll<HTMLElement>(':scope > aside')) {
                const box = panel.getBoundingClientRect();
                if (box.width && box.right > bounds.right + 12 && box.top > top)
                    height = Math.min(height, box.top - top - 12);
            }
        }
        root.style.maxHeight = `${Math.max(0, height)}px`;
    }
    window.addEventListener('resize', position, { signal: lifetime.signal });
    const observer = new ResizeObserver(position);
    observer.observe(header);
    observer.observe(workspace);
    lifetime.onCleanup(() => observer.disconnect());
    function render() {
        if (!locale) return;
        root.setAttribute('aria-label', locale.common('notifications'));
        for (const [id, entry] of rows) {
            if (items.some((item) => item.id === id)) continue;
            entry.row.remove();
            rows.delete(id);
        }
        root.hidden = items.length === 0;
        if (items.length) position();
        for (const item of items) {
            let entry = rows.get(item.id);
            if (!entry) {
                const row = element('button', '', `toast toast-${item.kind}`);
                row.type = 'button';
                row.addEventListener('click', () => application.notifications.dismiss(item.id));
                const symbol = element('span', item.kind === 'success' ? '✓' : '!', 'toast-symbol');
                symbol.setAttribute('aria-hidden', 'true');
                const content = element('span', '', 'toast-content');
                content.id = `notification-text-${item.id}`;
                row.setAttribute('aria-describedby', content.id);
                content.role = 'status';
                const title = element('strong');
                const detail = element('span', '', 'toast-detail');
                content.append(title, detail);
                const close = element('span', '×', 'toast-close');
                close.setAttribute('aria-hidden', 'true');
                const updatePause = () =>
                    application.notifications.pause(
                        item.id,
                        row.matches(':hover') || row.contains(document.activeElement)
                    );
                for (const event of ['pointerenter', 'pointerleave', 'focusin'])
                    row.addEventListener(event, updatePause);
                row.addEventListener('focusout', (event) =>
                    application.notifications.pause(
                        item.id,
                        row.matches(':hover') || row.contains(event.relatedTarget as Node | null)
                    )
                );
                row.append(symbol, content, close);
                root.append(row);
                entry = { row, title, detail };
                rows.set(item.id, entry);
            }
            entry.title.textContent = locale.common(item.message);
            entry.detail.textContent = item.subject
                ? `${item.quantity === undefined ? '' : `${locale.number(item.quantity)} × `}${plainMinecraftText(item.subject)}`
                : '';
            entry.detail.hidden = !entry.detail.textContent;
            entry.row.setAttribute('aria-label', locale.common('dismissNotification'));
        }
    }
    application.notifications.subscribe((next) => {
        items = next;
        render();
    });
    lifetime.onCleanup(() => {
        for (const id of rows.keys()) application.notifications.pause(id, false);
        root.remove();
    });
    return {
        element: root,
        render(translator: Translator) {
            locale = translator;
            render();
        }
    };
}
