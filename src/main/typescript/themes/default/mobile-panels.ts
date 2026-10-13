export type MobilePane = { content: HTMLElement; title: string; icon: string };

/** Moves existing theme controls into one native modal, restoring their original DOM on close. */
export function createMobilePanels(
    root: HTMLElement,
    workspace: HTMLElement,
    navigation: HTMLElement,
    hideTips: () => void
) {
    const media = matchMedia('(max-width: 600px)');
    const menu = document.createElement('button');
    menu.type = 'button';
    menu.className = 'mobile-navigation-toggle';
    menu.textContent = '☰';
    menu.setAttribute('aria-haspopup', 'dialog');
    root.querySelector('.site-header')!.prepend(menu);
    const toolbar = document.createElement('div');
    toolbar.className = 'mobile-toolbar';
    const dialog = document.createElement('dialog');
    dialog.className = 'window-frame mobile-panel';
    const heading = document.createElement('div');
    heading.className = 'mobile-panel-heading';
    const title = document.createElement('h2');
    title.id = 'mobile-panel-title';
    dialog.setAttribute('aria-labelledby', title.id);
    const closeButton = document.createElement('button');
    closeButton.type = 'button';
    closeButton.textContent = '×';
    heading.append(title, closeButton);
    const body = document.createElement('div');
    body.className = 'mobile-panel-body';
    dialog.append(heading, body);
    workspace.append(dialog);
    let opener: HTMLElement | null = null;
    let active: HTMLElement | null = null;
    let route = '';
    let navigationTitle = '';
    const moved: { node: HTMLElement; marker: Comment }[] = [];
    const buttons = new Map<HTMLElement, HTMLButtonElement>();
    function move(node: HTMLElement, destination: HTMLElement) {
        const marker = document.createComment('mobile pane position');
        node.before(marker);
        moved.push({ node, marker });
        destination.append(node);
    }
    function close(restore = true) {
        hideTips();
        const source = opener;
        active = null;
        opener = null;
        for (const { node, marker } of moved) marker.replaceWith(node);
        moved.length = 0;
        dialog.close();
        if (restore && source?.isConnected && source.getClientRects().length) source.focus({ preventScroll: true });
        return source;
    }
    function open(content: HTMLElement, label: string, source: HTMLElement) {
        if (!media.matches || content.hidden) return;
        close(false);
        active = content;
        opener = source;
        title.textContent = label;
        dialog.classList.toggle('mobile-navigation', content === navigation);
        move(content, body);
        body.scrollTop = 0;
        for (const tooltip of root.querySelectorAll<HTMLElement>('[role="tooltip"]')) move(tooltip, dialog);
        hideTips();
        dialog.showModal();
        closeButton.focus();
    }
    const navigate = (event: MouseEvent) => {
        if (active === navigation && (event.target as Element).closest('a[href]')) close(false);
    };
    navigation.addEventListener('click', navigate);
    menu.addEventListener('click', () => open(navigation, navigationTitle, menu));
    closeButton.addEventListener('click', () => close());
    dialog.addEventListener('cancel', (event) => {
        event.preventDefault();
        close();
    });
    dialog.addEventListener('click', (event) => {
        if (event.target !== dialog) return;
        const rect = dialog.getBoundingClientRect();
        if (
            event.clientX < rect.left ||
            event.clientX > rect.right ||
            event.clientY < rect.top ||
            event.clientY > rect.bottom
        )
            close();
    });
    const resize = () => {
        if (!media.matches) close(false);
    };
    media.addEventListener('change', resize);
    return {
        open,
        beforeDialog(source: HTMLElement) {
            return active ? close(false) || source : source;
        },
        render(
            routeKey: string,
            panes: MobilePane[],
            labels: { navigation: string; close: string },
            host: HTMLElement | null
        ) {
            if (routeKey !== route) close(false);
            route = routeKey;
            navigationTitle = labels.navigation;
            menu.setAttribute('aria-label', labels.navigation);
            closeButton.setAttribute('aria-label', labels.close);
            for (const [content, button] of buttons) {
                if (!panes.some((pane) => pane.content === content)) {
                    button.remove();
                    buttons.delete(content);
                    if (active === content) close(false);
                }
            }
            for (const pane of panes) {
                let button = buttons.get(pane.content);
                if (!button) {
                    button = document.createElement('button');
                    button.type = 'button';
                    button.setAttribute('aria-haspopup', 'dialog');
                    button.innerHTML = pane.icon;
                    button.addEventListener('click', () =>
                        open(pane.content, button!.getAttribute('aria-label') || '', button!)
                    );
                    buttons.set(pane.content, button);
                    toolbar.append(button);
                }
                button.setAttribute('aria-label', pane.title);
                if (active === pane.content) title.textContent = pane.title;
            }
            toolbar.hidden = !panes.length;
            if (host && toolbar.parentElement !== host) host.append(toolbar);
            else if (!host) toolbar.remove();
        },
        dispose() {
            close(false);
            media.removeEventListener('change', resize);
            navigation.removeEventListener('click', navigate);
            dialog.remove();
            toolbar.remove();
            menu.remove();
        }
    };
}
