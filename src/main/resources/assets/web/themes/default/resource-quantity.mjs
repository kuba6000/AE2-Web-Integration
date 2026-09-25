/** @typedef {import('../../app/i18n.mjs').Translator} Locale */

/** @param {number} quantity @param {Locale} locale */
export function slotQuantity(quantity, locale) {
    if (quantity < 10000) return locale.number(quantity);
    // AE2's wide slot format: four characters, SI suffixes, rounded down.
    const suffixes = 'kMGTPE';
    let divisor = 1000;
    let index = 0;
    while (quantity / divisor >= 1000 && index < suffixes.length - 1) {
        divisor *= 1000;
        index++;
    }
    const whole = Math.floor(quantity / divisor);
    const value = whole < 10 ? Math.floor(quantity / (divisor / 10)) / 10 : whole;
    return `${value}${suffixes[index]}`;
}
