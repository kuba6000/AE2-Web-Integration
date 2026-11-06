import type { SvgFont } from '../../app/svg-export.js';

/** The active default-theme family and its redistributed font assets. */
export function svgFonts(fontFamily: string): SvgFont[] {
    const family = /monocraft/i.test(fontFamily) ? 'Monocraft' : 'Lato';
    const directory = new URL(`./fonts/${family.toLowerCase()}/`, import.meta.url);
    return (['normal', 'bold'] as const).map((weight) => ({
        family,
        weight,
        asset: new URL(`${family}-${weight === 'bold' ? 'Bold' : 'Regular'}.woff2`, directory).href,
        license: new URL('LICENSE', directory).href
    }));
}
