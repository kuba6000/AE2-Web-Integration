export function readRoute() {
    const hash = location.hash.replace(/^#/, '');
    if (!hash || hash === '/' || hash === '/home') return { view: 'home', gridKey: null };
    const match = /^\/grids\/([^/]+)\/items$/.exec(hash);
    if (match) {
        try { return { view: 'items', gridKey: decodeURIComponent(match[1]) }; } catch {}
    }
    return { view: 'missing', gridKey: null };
}

export function navigateToGrid(key) {
    location.hash = key ? `/grids/${encodeURIComponent(key)}/items` : '/';
}
