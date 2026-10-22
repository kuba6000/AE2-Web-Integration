import type { PlanItem, ResourceDescription, StoredResource } from './api-types.js';
import { plainMinecraftText } from './minecraft-text.js';
export {
    planExportLayout,
    planExportFits,
    planExportRatio,
    readPlanExportSettings,
    defaultPlanExportSettings,
    smallerPlanExportLayout
} from './plan-export-layout.js';

type ResourceIdentity = Readonly<Pick<ResourceDescription, 'registryNamespace' | 'registryPath'>>;
type SearchableResource = ResourceIdentity & Readonly<Pick<ResourceDescription, 'displayName'>>;
type FilterableResource = SearchableResource &
    Readonly<Pick<StoredResource, 'resourceType' | 'quantity' | 'craftable'>>;
type SortableResource = SearchableResource & Readonly<Pick<StoredResource, 'quantity' | 'itemKey'>>;
type SelectableResource = FilterableResource & SortableResource;

export type ResourceFilterOptions = Readonly<{
    search: string;
    language: string;
    filter: 'all' | 'stored' | 'craftable';
    showItems: boolean;
    showFluids: boolean;
}>;
export type ResourceSortOptions = Readonly<{
    language: string;
    sort: 'name' | 'quantity' | 'id';
    sortOrder: 'ascending' | 'descending';
}>;
export type ResourceSelectionOptions = ResourceFilterOptions & ResourceSortOptions;
export type PlanResourceOptions = Readonly<{
    groupByState?: boolean;
    search: string;
    language: string;
    sort: 'name' | 'quantity' | 'steps';
    sortOrder: 'ascending' | 'descending';
}>;

/** Registry identity for display or search; unknown native resource types can have no registry. */
export function registryId(resource: ResourceIdentity): string {
    return resource.registryNamespace !== null && resource.registryPath !== null
        ? `${resource.registryNamespace}:${resource.registryPath}`
        : '';
}

function searchTerms(search: string, language: string): string[] {
    return search.trim().toLocaleLowerCase(language).split(/\s+/);
}

function matchesSearch(resource: SearchableResource, name: string, terms: readonly string[], language: string) {
    const text = `${name} ${registryId(resource)}`.toLocaleLowerCase(language);
    const mod = (resource.registryNamespace ?? '').toLocaleLowerCase(language);
    return terms.every((term) => (term.startsWith('@') ? mod.includes(term.slice(1)) : text.includes(term)));
}

function prepare<T extends SearchableResource>(resources: readonly T[]) {
    return resources.map((item) => ({ item, name: plainMinecraftText(item.displayName) }));
}

type NamedResource<T> = { item: T; name: string };

function filterNamed<T extends FilterableResource>(
    resources: NamedResource<T>[],
    options: ResourceFilterOptions
): NamedResource<T>[] {
    const terms = searchTerms(options.search, options.language);
    return resources.filter(({ item, name }) => {
        if (!matchesSearch(item, name, terms, options.language)) return false;
        if (item.resourceType === 'ITEM' && !options.showItems) return false;
        if (item.resourceType === 'FLUID' && !options.showFluids) return false;
        if (options.filter === 'stored') return item.quantity > 0;
        if (options.filter === 'craftable') return item.craftable;
        return true;
    });
}

function sortNamed<T extends SortableResource>(resources: NamedResource<T>[], options: ResourceSortOptions) {
    return resources.sort((a, b) => {
        let primary: number;
        if (options.sort === 'quantity') primary = a.item.quantity - b.item.quantity;
        else if (options.sort === 'id')
            primary = registryId(a.item).localeCompare(registryId(b.item), options.language);
        else primary = a.name.localeCompare(b.name, options.language);
        return (
            primary * (options.sortOrder === 'ascending' ? 1 : -1) ||
            a.name.localeCompare(b.name, options.language) ||
            registryId(a.item).localeCompare(registryId(b.item), options.language) ||
            (a.item.itemKey || '').localeCompare(b.item.itemKey || '')
        );
    });
}

/** Match all whitespace-separated terms; @terms search the registry namespace. OTHER remains visible. */
export function filterResources<T extends FilterableResource>(
    resources: readonly T[],
    options: ResourceFilterOptions
): T[] {
    return filterNamed(prepare(resources), options).map(({ item }) => item);
}

/** Sort a new array, retaining original resource objects. Ties use ascending name, registry and key. */
export function sortResources<T extends SortableResource>(resources: readonly T[], options: ResourceSortOptions): T[] {
    return sortNamed(prepare(resources), options).map(({ item }) => item);
}

/** Optional default terminal rules, independent of list, table or grid presentation. */
export function selectResources<T extends SelectableResource>(
    resources: readonly T[],
    options: ResourceSelectionOptions
): T[] {
    return sortNamed(filterNamed(prepare(resources), options), options).map(({ item }) => item);
}

/** Missing, requested, then storage-only groups; direction affects ordering within each group only. */
export function selectPlanResources<
    T extends SearchableResource & Readonly<Pick<PlanItem, 'stored' | 'requested' | 'missing' | 'steps'>>
>(resources: readonly T[], options: PlanResourceOptions): T[] {
    const terms = searchTerms(options.search, options.language);
    return prepare(resources)
        .filter(({ item, name }) => matchesSearch(item, name, terms, options.language))
        .sort((a, b) => {
            const groupA = planGroup(a.item);
            const groupB = planGroup(b.item);
            if (options.groupByState !== false && groupA !== groupB) return groupA - groupB;
            const names = a.name.localeCompare(b.name, options.language);
            let primary: number;
            if (options.sort === 'name') primary = names;
            else if (options.sort === 'quantity') {
                primary =
                    a.item.stored +
                    a.item.requested +
                    a.item.missing -
                    (b.item.stored + b.item.requested + b.item.missing);
            } else primary = a.item.steps - b.item.steps;
            return primary * (options.sortOrder === 'descending' ? -1 : 1) || names;
        })
        .map(({ item }) => item);
}

function planGroup(resource: Readonly<Pick<PlanItem, 'missing' | 'requested'>>): number {
    if (resource.missing > 0) return 0;
    if (resource.requested > 0) return 1;
    return 2;
}
