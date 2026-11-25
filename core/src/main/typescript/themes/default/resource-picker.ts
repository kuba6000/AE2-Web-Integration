import type { ThemeState } from '../../app/theme-api.js';
import type { Translator } from '../../app/i18n.js';
import { plainMinecraftText } from '../../app/minecraft-text.js';
import { registryId, resourceSearch } from '../../app/resource-helpers.js';
import type { IconTarget } from '../../app/icons.js';
import { element } from './dom.js';
import { createResourceIcon } from './resource-icon.js';
import { renderMinecraftText } from './minecraft-text.js';

type Item = ThemeState['items'][number];
const maxResults = 50;

export type ResourceChoice = {
    /** The network's current resources; only these can be chosen. */
    items: ThemeState['items'];
    itemsReady: boolean;
    catalogRevision: number;
    /** The message key of a failed read of the resources, which the picker offers to retry. */
    itemsError: string | null;
    /** Resources by identifier, for naming a chosen resource. */
    byKey: ReadonlyMap<string, Item>;
    /** Names for chosen resources the network no longer lists, such as a stored order's. */
    known: ThemeState['standingOrders']['resources'];
    locale: Translator;
    language: string;
};

/**
 * A searchable list of the network's resources, as in the terminal, shown on demand next to the chosen resource.
 * `craftableOnly` limits it to what the network can craft.
 */
export function createResourcePicker(
    choose: (item: Item) => void,
    { craftableOnly, retry, changed }: { craftableOnly: boolean; retry: () => void; changed: () => void }
) {
    const root = element('div', '', 'resource-picker');
    const choice = element('button', '', 'resource-choice');
    choice.type = 'button';
    choice.setAttribute('aria-expanded', 'false');
    const choiceIcon = createResourceIcon();
    const choiceName = element('span', '', 'resource-choice-name');
    choice.append(choiceIcon, choiceName);
    const panel = element('div', '', 'resource-picker-panel');
    panel.hidden = true;
    const search = element('input');
    search.type = 'search';
    search.autocomplete = 'off';
    const status = element('p', '', 'hint');
    status.role = 'status';
    const retryButton = element('button');
    retryButton.type = 'button';
    retryButton.hidden = true;
    retryButton.addEventListener('click', retry);
    const list = element('ul', '', 'resource-picker-list');
    panel.append(search, status, retryButton, list);
    root.append(choice, panel);
    let context: ResourceChoice | undefined;
    let selected: string | null = null;
    let shownSignature = '';
    let searchIndex: ReturnType<typeof resourceSearch<Item>> | undefined;
    let resultRows: { item: Item; button: HTMLButtonElement }[] = [];
    let renderedState = '';
    /** Not focus: a click on a result must not close the panel in browsers that do not focus buttons. */
    const outside = (event: PointerEvent) => {
        if (!root.contains(event.target as Node | null)) close(false);
    };

    function close(restoreFocus: boolean) {
        if (panel.hidden) return;
        panel.hidden = true;
        document.removeEventListener('pointerdown', outside, true);
        choice.setAttribute('aria-expanded', 'false');
        searchIndex = undefined;
        resultRows = [];
        list.replaceChildren();
        changed();
        if (restoreFocus) choice.focus();
    }
    const resultSignature = (state: ResourceChoice) =>
        JSON.stringify([state.catalogRevision, state.itemsReady, state.itemsError, state.language]);
    function renderResults() {
        if (!context) return;
        const { items, itemsReady, itemsError, locale, language } = context;
        const signature = resultSignature(context);
        if (signature !== renderedState) searchIndex = undefined;
        renderedState = signature;
        if (itemsReady) searchIndex ??= resourceSearch(items, language, craftableOnly);
        const results = searchIndex?.(search.value, maxResults) ?? [];
        let message = locale.common('standingOrderResourcesLoading');
        if (itemsError) message = locale.common(itemsError);
        else if (itemsReady) message = results.length ? '' : locale.common('standingOrderNoResources');
        status.textContent = message;
        retryButton.hidden = !itemsError;
        retryButton.textContent = locale.common('retry');
        resultRows = results.map((item) => {
            const button = element('button', '', 'resource-result');
            button.type = 'button';
            const name = element('strong');
            name.append(renderMinecraftText(item.displayName));
            button.append(createResourceIcon(), name, element('code', registryId(item)));
            button.setAttribute('aria-label', plainMinecraftText(item.displayName));
            button.addEventListener('click', () => {
                close(true);
                choose(item);
            });
            return { item, button };
        });
        list.replaceChildren(
            ...resultRows.map((row) => {
                const item = element('li');
                item.append(row.button);
                return item;
            })
        );
    }
    choice.addEventListener('click', () => {
        if (!panel.hidden) return close(false);
        panel.hidden = false;
        document.addEventListener('pointerdown', outside, true);
        choice.setAttribute('aria-expanded', 'true');
        search.value = '';
        renderResults();
        changed();
        search.focus();
    });
    search.addEventListener('input', () => {
        renderResults();
        changed();
    });
    // Enter confirms the search; it must never reach the editor form's submit.
    search.addEventListener('keydown', (event) => {
        if (event.key === 'Enter') event.preventDefault();
    });
    root.addEventListener('keydown', (event) => {
        if (event.key !== 'Escape' || panel.hidden) return;
        event.preventDefault();
        event.stopPropagation();
        close(true);
    });
    root.addEventListener('focusout', (event) => {
        // Tabbing away; a null target is a pointer press, which `outside` handles.
        if (event.relatedTarget && !root.contains(event.relatedTarget as Node)) close(false);
    });
    return {
        element: root,
        choice,
        /** Icon targets of what is currently shown, for the caller's icon observer. */
        targets(saved: boolean): IconTarget[] {
            const found = selected === null ? undefined : context?.byKey.get(selected);
            const stored = selected && context?.known[selected]?.icon;
            if (saved) return !found?.icon && stored ? [{ element: choice, icon: stored }] : [];
            return [
                ...(found?.icon ? [{ element: choice, icon: found.icon }] : []),
                ...(panel.hidden ? [] : resultRows.map((row) => ({ element: row.button, icon: row.item.icon })))
            ];
        },
        render(next: ResourceChoice, key: string | null, invalid: boolean, label: string) {
            context = next;
            selected = key;
            const found = key === null ? undefined : next.byKey.get(key);
            const description = key === null ? undefined : (found ?? next.known[key]);
            let name = description?.displayName ?? '';
            if (key === null) name = next.locale.common('standingOrderChooseResource');
            else if (!description)
                name = next.locale.common(next.itemsReady ? 'standingOrderUnknownResource' : 'loading');
            const signature = `${key}\u0000${name}\u0000${label}`;
            if (shownSignature !== signature) {
                shownSignature = signature;
                choiceName.replaceChildren(renderMinecraftText(name));
                choice.setAttribute('aria-label', `${label}: ${plainMinecraftText(name)}`);
            }
            choice.setAttribute('aria-invalid', String(invalid));
            search.setAttribute('aria-label', next.locale.common('standingOrderSearchResources'));
            search.placeholder = next.locale.common('searchHint');
            if (!panel.hidden && renderedState !== resultSignature(next)) renderResults();
        },
        reset() {
            context = undefined;
            selected = null;
            close(false);
        },
        close: () => close(false)
    };
}
