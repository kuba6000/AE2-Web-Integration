export function readRoute() {
    const hash = location.hash.replace(/^#/, '');
    if (!hash || hash === '/' || hash === '/home') return { view: 'home', gridKey: null };
    const cpu = /^\/grids\/([^/]+)\/cpus(?:\/([^/]+))?$/.exec(hash);
    if (cpu) {
        try { return {view: 'cpus', gridKey: decodeURIComponent(cpu[1]), cpuKey: cpu[2] ? decodeURIComponent(cpu[2]) : null}; } catch {}
    }
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

export function cpuHref(gridKey, cpuKey = null) {
    return `#/grids/${encodeURIComponent(gridKey)}/cpus${cpuKey ? '/' + encodeURIComponent(cpuKey) : ''}`;
}

export function navigateToGrid(key) {
    location.hash = key ? `/grids/${encodeURIComponent(key)}/items` : '/';
}
