import type { IconMetadata, IconReference } from './api-types.js';

export type IconSprite = {
    url: string;
    x: number;
    y: number;
    width: number;
    height: number;
    pageWidth: number;
    pageHeight: number;
};
type Descriptor = Omit<IconSprite, 'url'> & { source: string };
type Page = {
    source: string;
    width: number;
    height: number;
    users: Set<() => void>;
    status: 'queued' | 'loading' | 'ready' | 'failed';
    controller: AbortController;
    url?: string;
    blob?: Blob;
};
export type IconTarget = { element: HTMLElement; icon?: IconReference | null };
export type DrawableIcon = { image: CanvasImageSource; x: number; y: number; width: number; height: number };
export type ResolvedIcons = { icons: ReadonlyMap<IconReference, DrawableIcon>; dispose(): void };

const digest = /^[a-f0-9]{64}$/;
const dimension = (value: number) => Number.isInteger(value) && value > 0 && value <= 2048;

/** One deployment's authenticated atlas pages, shared by all theme views. */
export function createIconLoader(base: URL, unauthorized: () => void, stalePack: (packId: string) => Promise<void>) {
    const pages = new Map<string, Page>();
    const views = new Set<() => void>();
    let enabled = false;
    let disposed = false;
    let active = 0;
    let checkedPack: string | null = null;

    function describe(metadata: IconMetadata | null, icon: IconReference | null | undefined): Descriptor | null {
        if (
            !metadata ||
            !icon ||
            !digest.test(metadata.packId) ||
            metadata.width !== 64 ||
            metadata.height !== 64 ||
            !Array.isArray(metadata.pages)
        )
            return null;
        if (
            !Number.isInteger(icon.page) ||
            !Number.isInteger(icon.x) ||
            !Number.isInteger(icon.y) ||
            icon.x < 0 ||
            icon.y < 0
        )
            return null;
        const page = metadata.pages[icon.page];
        if (
            !page ||
            !digest.test(page.digest) ||
            !dimension(page.width) ||
            !dimension(page.height) ||
            icon.x + metadata.width > page.width ||
            icon.y + metadata.height > page.height
        )
            return null;
        return {
            source: new URL(`api/icon-packs/${metadata.packId}/pages/${page.digest}`, base).href,
            x: icon.x,
            y: icon.y,
            width: metadata.width,
            height: metadata.height,
            pageWidth: page.width,
            pageHeight: page.height
        };
    }

    function remove(page: Page) {
        page.controller.abort();
        if (page.url) URL.revokeObjectURL(page.url);
        pages.delete(page.source);
        for (const update of page.users) update();
    }

    function trim() {
        // Keep a small warm set across polling, filtering and switching between inventory and CPU.
        const unused = [...pages.values()].filter((page) => !page.users.size);
        for (const page of unused) if (page.status === 'loading' || page.status === 'queued') remove(page);
        const settled = unused.filter((page) => page.status === 'ready' || page.status === 'failed');
        for (const page of settled.slice(0, Math.max(0, settled.length - 4))) remove(page);
    }

    async function load(page: Page) {
        active++;
        page.status = 'loading';
        try {
            const response = await fetch(page.source, { credentials: 'same-origin', signal: page.controller.signal });
            if (response.status === 401) unauthorized();
            if (response.status === 404) {
                const packId = new URL(page.source).pathname.split('/').at(-3) as string;
                if (checkedPack !== packId) {
                    checkedPack = packId;
                    // The ordinary resource refresh owns mappings; no separate per-item lookup exists.
                    void stalePack(packId).catch(() => {});
                }
            }
            if (!response.ok || response.headers.get('Content-Type')?.split(';')[0] !== 'image/png')
                throw new Error('Invalid atlas response');
            const blob = await response.blob();
            const bitmap = await createImageBitmap(blob);
            const valid = bitmap.width === page.width && bitmap.height === page.height;
            bitmap.close();
            if (!valid) throw new Error('Invalid atlas dimensions');
            if (page.controller.signal.aborted || disposed) return;
            page.url = URL.createObjectURL(blob);
            page.blob = blob;
            page.status = 'ready';
        } catch {
            if (page.controller.signal.aborted) return;
            // A failed visible page stays failed; polling quantities must not cause retry storms.
            page.status = 'failed';
        } finally {
            active--;
            if (!page.controller.signal.aborted) for (const update of page.users) update();
            pump();
        }
    }

    function pump() {
        if (!enabled || disposed) return;
        for (const page of pages.values()) {
            if (active >= 4) break;
            if (page.status === 'queued' && page.users.size) void load(page);
        }
    }

    return {
        async resolve(
            references: readonly IconReference[],
            metadata: IconMetadata | null,
            signal: AbortSignal
        ): Promise<ResolvedIcons> {
            signal.throwIfAborted();
            const icons = new Map<IconReference, DrawableIcon>();
            const bitmaps: ImageBitmap[] = [];
            const dispose = () => {
                for (const bitmap of bitmaps.splice(0)) bitmap.close();
                icons.clear();
            };
            const grouped = new Map<string, { descriptor: Descriptor; references: IconReference[] }>();
            if (!enabled || disposed) return { icons, dispose };
            for (const reference of references) {
                const descriptor = describe(metadata, reference);
                if (!descriptor) continue;
                const group = grouped.get(descriptor.source);
                if (group) group.references.push(reference);
                else grouped.set(descriptor.source, { descriptor, references: [reference] });
            }
            try {
                for (const { descriptor, references: targets } of grouped.values()) {
                    signal.throwIfAborted();
                    if (!enabled || disposed) break;
                    let page = pages.get(descriptor.source);
                    if (!page) {
                        page = {
                            source: descriptor.source,
                            width: descriptor.pageWidth,
                            height: descriptor.pageHeight,
                            users: new Set(),
                            status: 'queued',
                            controller: new AbortController()
                        };
                        pages.set(page.source, page);
                    }
                    const current = page;
                    const blob = await new Promise<Blob | undefined>((resolve) => {
                        const update = () => {
                            if (
                                !signal.aborted &&
                                !current.controller.signal.aborted &&
                                current.status !== 'ready' &&
                                current.status !== 'failed'
                            )
                                return;
                            current.users.delete(update);
                            signal.removeEventListener('abort', update);
                            resolve(signal.aborted ? undefined : current.blob);
                        };
                        current.users.add(update);
                        signal.addEventListener('abort', update, { once: true });
                        update();
                        pump();
                    });
                    signal.throwIfAborted();
                    if (!blob) continue;
                    // Retain only requested sprites, not whole sparse atlas pages, for the export's lifetime.
                    const bitmap = await createImageBitmap(blob).catch(() => null);
                    if (!bitmap) continue;
                    try {
                        const crops = new Map<string, ImageBitmap | null>();
                        for (const reference of targets) {
                            signal.throwIfAborted();
                            const key = `${reference.x}:${reference.y}`;
                            let crop = crops.get(key);
                            if (crop === undefined) {
                                crop = await createImageBitmap(
                                    bitmap,
                                    reference.x,
                                    reference.y,
                                    descriptor.width,
                                    descriptor.height
                                ).catch(() => null);
                                crops.set(key, crop);
                                if (crop) bitmaps.push(crop);
                            }
                            if (crop)
                                icons.set(reference, {
                                    image: crop,
                                    x: 0,
                                    y: 0,
                                    width: descriptor.width,
                                    height: descriptor.height
                                });
                        }
                    } finally {
                        bitmap.close();
                        trim();
                    }
                }
                signal.throwIfAborted();
                return { icons, dispose };
            } catch (error) {
                dispose();
                throw error;
            } finally {
                trim();
            }
        },
        enabled(value: boolean) {
            enabled = value;
            for (const update of views) update();
            if (!enabled) for (const page of pages.values()) remove(page);
        },
        observe(root: HTMLElement, paint: (target: HTMLElement, sprite: IconSprite | null) => void) {
            type Binding = { descriptor: Descriptor | null; visible: boolean; page?: Page; update: () => void };
            const bindings = new Map<HTMLElement, Binding>();
            function release(binding: Binding) {
                binding.page?.users.delete(binding.update);
                binding.page = undefined;
            }
            function refresh(target: HTMLElement, binding: Binding) {
                const descriptor = enabled && !disposed && binding.visible ? binding.descriptor : null;
                if (!descriptor || binding.page?.source !== descriptor.source) release(binding);
                if (!descriptor) {
                    paint(target, null);
                    return;
                }
                let page = pages.get(descriptor.source);
                if (!page) {
                    page = {
                        source: descriptor.source,
                        width: descriptor.pageWidth,
                        height: descriptor.pageHeight,
                        users: new Set(),
                        status: 'queued',
                        controller: new AbortController()
                    };
                    pages.set(page.source, page);
                }
                binding.page = page;
                page.users.add(binding.update);
                paint(target, page.url ? { ...descriptor, url: page.url } : null);
            }
            const update = () => {
                for (const [target, binding] of bindings) refresh(target, binding);
                trim();
                pump();
            };
            const observer = new IntersectionObserver(
                (entries) => {
                    for (const entry of entries) {
                        const binding = bindings.get(entry.target as HTMLElement);
                        if (binding) binding.visible = entry.isIntersecting;
                    }
                    update();
                },
                { root, rootMargin: '96px 0px' }
            );
            views.add(update);
            return {
                update(targets: IconTarget[], metadata: IconMetadata | null) {
                    const retained = new Set(targets.map((target) => target.element));
                    for (const [target, binding] of bindings)
                        if (!retained.has(target)) {
                            release(binding);
                            paint(target, null);
                            observer.unobserve(target);
                            bindings.delete(target);
                        }
                    for (const { element, icon } of targets) {
                        let binding = bindings.get(element);
                        if (!binding) {
                            binding = {
                                descriptor: null,
                                visible: false,
                                update: () => {
                                    const current = bindings.get(element);
                                    if (current) refresh(element, current);
                                }
                            };
                            bindings.set(element, binding);
                            observer.observe(element);
                        }
                        binding.descriptor = describe(metadata, icon);
                    }
                    update();
                },
                dispose() {
                    observer.disconnect();
                    for (const [target, binding] of bindings) {
                        release(binding);
                        paint(target, null);
                    }
                    bindings.clear();
                    views.delete(update);
                    trim();
                }
            };
        },
        dispose() {
            disposed = true;
            for (const page of pages.values()) remove(page);
            views.clear();
        }
    };
}
