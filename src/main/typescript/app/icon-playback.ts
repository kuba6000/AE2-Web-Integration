import type { IconSprite } from './icons.js';

export type AtlasDescriptor = Omit<IconSprite, 'url'> & { source: string };
export type AnimationDescriptor = {
    page: AtlasDescriptor;
    key: string;
    frames: { x: number; y: number; durationMillis: number }[];
    duration: number;
};
type Lease = { read(): { url?: string; failed: boolean }; release(): void };
type Playback = { animation: AnimationDescriptor; paint: (sprite: IconSprite | null) => void; frame: number };
type Page = { descriptor: AtlasDescriptor; lease: Lease; users: Set<Playback> };

/** One clock for visible icons; view observers own admission and release of shared atlas pages. */
export function createIconPlayback(acquire: (page: AtlasDescriptor, changed: () => void) => Lease) {
    const pages = new Map<string, Page>();
    const waiting = new Set<Playback>();
    const phases = new Map<string, { start: number | null; users: number }>();
    const failed = new Set<string>();
    let timer: ReturnType<typeof setTimeout> | undefined;
    let frameRequest: number | undefined;
    let enabled = false;
    let disposed = false;

    function scheduleRender() {
        if (disposed || !enabled || frameRequest !== undefined) return;
        frameRequest = requestAnimationFrame(() => {
            frameRequest = undefined;
            render();
        });
    }

    function releasePhase(playback: Playback) {
        const phase = phases.get(playback.animation.key);
        if (phase && --phase.users === 0) phases.delete(playback.animation.key);
    }

    function releasePage(page: Page) {
        pages.delete(page.descriptor.source);
        page.lease.release();
    }

    function render() {
        clearTimeout(timer);
        timer = undefined;
        if (disposed || !enabled) return;
        const now = performance.now();
        let next = Infinity;
        for (const page of pages.values()) {
            const loaded = page.lease.read();
            if (loaded.failed) {
                failed.add(page.descriptor.source);
                for (const playback of page.users) {
                    releasePhase(playback);
                    waiting.add(playback);
                }
                releasePage(page);
                // New admissions can paint on the next browser frame, after this traversal.
                queueMicrotask(admit);
                continue;
            }
            if (!loaded.url) continue;
            for (const playback of page.users) {
                const animation = playback.animation;
                const phase = phases.get(animation.key)!;
                phase.start ??= now;
                const elapsed = (now - phase.start) % animation.duration;
                let end = 0;
                let frame = 0;
                for (; frame < animation.frames.length; frame++) {
                    end += animation.frames[frame].durationMillis;
                    if (elapsed < end) break;
                }
                next = Math.min(next, end - elapsed);
                if (playback.frame === frame) continue;
                playback.frame = frame;
                const crop = animation.frames[frame];
                playback.paint({ ...animation.page, url: loaded.url, x: crop.x, y: crop.y });
            }
        }
        if (Number.isFinite(next)) timer = setTimeout(scheduleRender, Math.max(16, Math.ceil(next)));
    }

    function admit() {
        if (disposed || !enabled) return;
        for (const playback of waiting) {
            const descriptor = playback.animation.page;
            if (failed.has(descriptor.source)) continue;
            let page = pages.get(descriptor.source);
            if (!page) {
                const lease = acquire(descriptor, scheduleRender);
                page = { descriptor, lease, users: new Set() };
                pages.set(descriptor.source, page);
            }
            page.users.add(playback);
            waiting.delete(playback);
            const phase = phases.get(playback.animation.key);
            if (phase) phase.users++;
            else phases.set(playback.animation.key, { start: null, users: 1 });
        }
        scheduleRender();
    }

    return {
        enabled(value: boolean) {
            enabled = value;
            if (!value) {
                clearTimeout(timer);
                if (frameRequest !== undefined) cancelAnimationFrame(frameRequest);
                frameRequest = undefined;
            } else admit();
        },
        watch(animation: AnimationDescriptor, paint: Playback['paint']) {
            const playback = { animation, paint, frame: -1 };
            waiting.add(playback);
            admit();
            return () => {
                waiting.delete(playback);
                const page = pages.get(animation.page.source);
                if (page?.users.delete(playback)) {
                    releasePhase(playback);
                    if (!page.users.size) {
                        releasePage(page);
                    }
                }
                queueMicrotask(admit);
            };
        },
        dispose() {
            disposed = true;
            clearTimeout(timer);
            if (frameRequest !== undefined) cancelAnimationFrame(frameRequest);
            for (const page of pages.values()) page.lease.release();
            pages.clear();
            waiting.clear();
            phases.clear();
            failed.clear();
        }
    };
}
