type Card = { symbol: string | null; missing: boolean; blocked: boolean };

export type TreePalette = {
    foreground: string;
    tile: string;
    border: string;
    recipe: string;
    recipeBorder: string;
    missing: string;
    missingBorder: string;
    missingText: string;
    blocked: string;
};

/** Type paint stays distinct; shortage outlines take priority over the ordinary frame. */
export function treeCardPaint(card: Card, palette: TreePalette, colors: boolean) {
    const recipe = card.symbol === 'craft';
    const missing = colors && card.missing;
    return {
        fill: missing ? palette.missing : recipe ? palette.recipe : palette.tile,
        border: missing
            ? palette.missingBorder
            : colors && card.blocked
              ? palette.blocked
              : recipe
                ? palette.recipeBorder
                : palette.border,
        foreground: missing ? palette.missingText : palette.foreground
    };
}

export function treeConnectionColor(card: Card, palette: TreePalette, colors: boolean) {
    return colors && card.missing ? palette.missingBorder : colors && card.blocked ? palette.blocked : palette.border;
}

/** Shared trunks must not be repainted by a healthy sibling after a shortage connection. */
export function treeConnectionOrder(cards: readonly Card[]) {
    const layers: number[][] = [[], [], []];
    cards.forEach((card, index) => layers[card.missing ? 2 : card.blocked ? 1 : 0].push(index));
    return layers.flat();
}
