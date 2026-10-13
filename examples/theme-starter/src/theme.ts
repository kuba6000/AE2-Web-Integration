import './style.css';
import type { ThemeDefinition, ThemeState } from '@ae2wi/theme-api';

/** A complete small theme: a network chooser and resource list, without default-theme markup. */
const theme: ThemeDefinition = {
    id: 'resource-list',
    name: 'Resource list example',
    supportedModVersion: '1.2.x',
    views: ['home', 'items'],
    mount(root, application, context) {
        root.classList.add('list-theme');
        context.lifetime.onCleanup(() => {
            root.classList.remove('list-theme');
            root.replaceChildren();
        });
        root.innerHTML = `<header><h1>Resource list</h1><p class="account"></p>
            <button type="button" class="themes">Themes</button><button type="button" class="refresh">Refresh</button><button type="button" class="logout">Log out</button></header>
            <nav aria-label="Views"><a href="#/">Networks</a></nav>
            <p class="version"></p><p class="status" role="status"></p>
            <section class="networks"><h2>Networks</h2><ul></ul></section>
            <section class="resources" hidden><h2>Resources</h2>
                <label>Search <input type="search"></label>
                <label>Order <select><option value="name">Name</option><option value="quantity">Quantity</option><option value="id">Identifier</option></select></label>
                <label><input type="checkbox" checked> Use standard search and ordering</label>
                <ul class="items" aria-label="Resources"></ul>
                <p class="selection" aria-live="polite"></p></section>
            <section class="unsupported" hidden><h2>View unavailable in this theme</h2>
                <p>This example implements Networks and Resources.</p><a href="#/">Choose a network</a></section>`;
        const networks = root.querySelector<HTMLElement>('.networks')!;
        const resources = root.querySelector<HTMLElement>('.resources')!;
        const unsupported = root.querySelector<HTMLElement>('.unsupported')!;
        const status = root.querySelector<HTMLElement>('.status')!;
        const search = root.querySelector<HTMLInputElement>('input[type="search"]')!;
        const sort = root.querySelector<HTMLSelectElement>('select')!;
        const standard = root.querySelector<HTMLInputElement>('input[type="checkbox"]')!;
        const list = root.querySelector<HTMLUListElement>('.items')!;
        const selection = root.querySelector<HTMLElement>('.selection')!;
        const refresh = root.querySelector<HTMLButtonElement>('.refresh')!;
        const logout = root.querySelector<HTMLButtonElement>('.logout')!;
        const themes = root.querySelector<HTMLButtonElement>('.themes')!;
        themes.hidden = !context.themes;
        themes.addEventListener('click', () => context.themes?.open(), { signal: context.lifetime.signal });
        const signal = context.lifetime.signal;
        root.querySelector('.account')!.textContent = context.user.username;
        root.querySelector('.version')!.textContent =
            `Theme targets ${theme.supportedModVersion}; server ${context.modVersion ?? 'unknown'}.`;
        standard.checked = context.settings.get('standardOrder', true) !== false;
        search.addEventListener('input', () => application.search(search.value), { signal });
        sort.addEventListener(
            'change',
            () =>
                application.preference('sort', sort.value === 'quantity' || sort.value === 'id' ? sort.value : 'name'),
            { signal }
        );
        standard.addEventListener(
            'change',
            () => {
                context.settings.set('standardOrder', standard.checked);
                render(application.state);
            },
            { signal }
        );
        refresh.addEventListener('click', () => void application.refresh({ reloadDetail: true }), { signal });
        logout.addEventListener(
            'click',
            async () => {
                logout.disabled = true;
                try {
                    await context.logout();
                } catch {
                    if (signal.aborted) return;
                    status.textContent = 'Logout failed. Try again.';
                    logout.disabled = false;
                }
            },
            { signal }
        );

        function render(state: ThemeState) {
            const supported = theme.views.some((view) => view === state.route.view);
            networks.hidden = state.route.view !== 'home';
            resources.hidden = state.route.view !== 'items';
            unsupported.hidden = supported;
            refresh.setAttribute('aria-busy', String(state.refreshingView || state.refreshing));
            status.textContent =
                state.gridError ?? state.itemError ?? (state.gridStatus === 'loading' ? 'Loading networks…' : '');
            const links = state.grids.map((grid) => {
                const li = document.createElement('li');
                const link = document.createElement('a');
                link.textContent = grid.name || grid.key;
                link.href = `#/grids/${encodeURIComponent(grid.key)}/items`;
                li.append(link);
                return li;
            });
            networks.querySelector('ul')!.replaceChildren(...links);
            if (state.route.view !== 'items') return;
            const locale = context.i18n.forLanguage(state.preferences.language);
            search.value = state.search;
            sort.value = state.preferences.sort;
            search.disabled = !standard.checked;
            sort.disabled = !standard.checked;
            // Optional helper. The other branch deliberately uses the unmodified server order.
            const visible = standard.checked
                ? context.helpers.selectResources(state.items, {
                      search: state.search,
                      language: state.preferences.language,
                      filter: state.preferences.filter,
                      showItems: state.preferences.showItems,
                      showFluids: state.preferences.showFluids,
                      sort: state.preferences.sort,
                      sortOrder: state.preferences.sortOrder
                  })
                : state.items;
            // Bound the DOM in this teaching example; a production list can paginate or virtualize.
            const rows = visible.slice(0, 100).map((resource) => {
                const li = document.createElement('li');
                const button = document.createElement('button');
                button.type = 'button';
                button.textContent = `${resource.displayName} — ${locale.number(resource.quantity)}`;
                button.setAttribute(
                    'aria-pressed',
                    String(state.selected?.itemKey === resource.itemKey && resource.itemKey !== null)
                );
                button.addEventListener('click', () => application.select(resource));
                li.append(button);
                return li;
            });
            list.replaceChildren(...rows);
            selection.textContent = state.selected ? `Selected: ${state.selected.displayName}` : '';
            if (!status.textContent) status.textContent = `Showing ${rows.length} of ${visible.length} resources.`;
        }
        application.displayIcons(false);
        application.subscribe(render);
    }
};

export default theme;
