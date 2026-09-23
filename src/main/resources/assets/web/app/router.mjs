export function readRoute() {
    const hash = location.hash.replace(/^#/, '');
    if (!hash || hash === '/' || hash === '/home') return { view: 'home', gridKey: null };
    const plan = /^\/grids\/([^/]+)\/plans\/([1-9]\d*)$/.exec(hash);
    if (plan) {
        try { return { view: 'plan', gridKey: decodeURIComponent(plan[1]), planId: plan[2] }; } catch {}
    }
    const match = /^\/grids\/([^/]+)\/items$/.exec(hash);
    if (match) {
        try { return { view: 'items', gridKey: decodeURIComponent(match[1]) }; } catch {}
    }
    return { view: 'missing', gridKey: null };
}

export function navigateToPlan(gridKey, planId) {
    location.hash = `/grids/${encodeURIComponent(gridKey)}/plans/${planId}`;
}

export function navigateToGrid(key) {
    location.hash = key ? `/grids/${encodeURIComponent(key)}/items` : '/';
}
