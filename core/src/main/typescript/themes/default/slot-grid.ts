/** Decorative cells share CSS tracks with resources; only the fixed viewport determines spare rows. */
export function createSlotGrid(
    grid: HTMLUListElement,
    viewport: HTMLElement,
    terminal: HTMLElement,
    fillViewport = true,
    emptyIcon = ''
) {
    const empty: HTMLLIElement[] = [];
    let resources = 0;
    let frame = 0;
    let disposed = false;
    function paint() {
        frame = 0;
        const bounds = grid.getBoundingClientRect();
        // These scroll viewports have no vertical border/padding; retain fractional CSS height.
        const viewportBounds = viewport.getBoundingClientRect();
        const precedingHeight = Math.max(0, bounds.top - viewportBounds.top + viewport.scrollTop);
        const availableHeight = Math.max(0, viewportBounds.height - precedingHeight);
        if (disposed || bounds.width === 0 || viewportBounds.height === 0) return;
        const style = getComputedStyle(grid);
        const columns = style.gridTemplateColumns.split(' ').map(Number.parseFloat);
        // Auto rows contain square cells; fixed rows retain the terminal/CPU height from CSS.
        const rowHeight = Number.parseFloat(style.gridAutoRows) || Math.max(...columns);
        const rows = Math.max(
            Math.ceil(resources / columns.length),
            fillViewport ? Math.floor(availableHeight / rowHeight) : 0
        );
        const needed = rows * columns.length - resources;
        while (empty.length > needed) empty.pop()!.remove();
        while (empty.length < needed) {
            const cell = document.createElement('li');
            cell.className = 'slot-frame empty-slot';
            cell.innerHTML = emptyIcon;
            cell.setAttribute('aria-hidden', 'true');
            cell.inert = true;
            grid.append(cell);
            empty.push(cell);
        }
        terminal.style.setProperty('--grid-inset', `${bounds.left - terminal.getBoundingClientRect().left}px`);
    }
    function schedule() {
        if (!disposed && !frame) frame = requestAnimationFrame(paint);
    }
    const observer = new ResizeObserver(schedule);
    observer.observe(viewport);
    return {
        update(count: number) {
            resources = count;
            schedule();
        },
        dispose() {
            disposed = true;
            observer.disconnect();
            cancelAnimationFrame(frame);
            for (const cell of empty) cell.remove();
            empty.length = 0;
        }
    };
}
