import { parseMinecraftText } from '../../app/minecraft-text.mjs';

/** Render game text using this theme's palette, without interpreting it as HTML.
 * @param {string} text
 * @returns {DocumentFragment}
 */
export function renderMinecraftText(text) {
    const fragment = document.createDocumentFragment();
    for (const segment of parseMinecraftText(text)) {
        const span = document.createElement('span');
        if (segment.color) span.classList.add(`mc-${segment.color}`);
        if (segment.bold) span.classList.add('mc-bold');
        if (segment.italic) span.classList.add('mc-italic');
        if (segment.underline) span.classList.add('mc-underline');
        if (segment.strikethrough) span.classList.add('mc-strikethrough');
        if (segment.obfuscated) {
            const readable = document.createElement('span');
            readable.className = 'sr-only';
            readable.textContent = segment.text;
            const masked = document.createElement('span');
            masked.setAttribute('aria-hidden', 'true');
            masked.textContent = segment.text.replace(/\S/gu, '▒');
            span.append(readable, masked);
        } else span.textContent = segment.text;
        fragment.append(span);
    }
    return fragment;
}
