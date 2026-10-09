/** Decorative cells share CSS tracks with resources; only the fixed viewport determines spare rows. */
export function createSlotGrid(grid: HTMLUListElement, viewport: HTMLElement, terminal: HTMLElement) {
    const empty: HTMLLIElement[] = [];
    let resources = 0;
    let frame = 0;
    let disposed = false;
    function paint() {
        frame = 0;
        const bounds = grid.getBoundingClientRect();
        // These scroll viewports have no vertical border/padding; retain fractional CSS height.
        const availableHeight = viewport.getBoundingClientRect().height;
        if (disposed || bounds.width === 0 || availableHeight === 0) return;
        const style = getComputedStyle(grid);
        const columns = style.gridTemplateColumns.split(' ').map(Number.parseFloat);
        // Auto rows contain square cells; fixed rows retain the terminal/CPU height from CSS.
        const rowHeight = Number.parseFloat(style.gridAutoRows) || Math.max(...columns);
        const rows = Math.max(Math.ceil(resources / columns.length), Math.floor(availableHeight / rowHeight));
        const needed = rows * columns.length - resources;
        while (empty.length > needed) empty.pop()!.remove();
        while (empty.length < needed) {
            const cell = document.createElement('li');
            cell.className = 'slot-frame empty-slot';
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
