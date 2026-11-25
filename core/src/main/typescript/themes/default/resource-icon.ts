import type { IconSprite } from '../../app/icons.js';
import type { Translator } from '../../app/i18n.js';
import { renderMinecraftText } from './minecraft-text.js';

export function inlineResourceName(displayName: string): HTMLElement {
    const label = document.createElement('span');
    label.className = 'inline-resource-name';
    label.append(createResourceIcon(), renderMinecraftText(displayName));
    return label;
}

/** Substitute nodes in translated sentences without treating names or other values as markup. */
export function resourceMessage(locale: Translator, key: string, values: Record<string, string | number | Node>) {
    const template = locale.common(key, Object.fromEntries(Object.keys(values).map((name) => [name, `{${name}}`])));
    const fragment = document.createDocumentFragment();
    for (const token of template.split(/(\{\w+\})/)) {
        const name = token.slice(1, -1);
        const value = token.startsWith('{') && Object.hasOwn(values, name) ? values[name] : token;
        fragment.append(typeof value === 'number' ? locale.number(value) : value);
    }
    return fragment;
}

const paintedSprites = new WeakMap<HTMLElement, IconSprite | null>();

export function createResourceIcon() {
    const icon = document.createElement('span');
    icon.className = 'resource-icon';
    icon.setAttribute('aria-hidden', 'true');
    return icon;
}

/** Scale the atlas crop with the theme's icon size; names remain the accessible content. */
export function paintResourceIcon(target: HTMLElement, sprite: IconSprite | null) {
    const icon = target.querySelector<HTMLElement>('.resource-icon');
    if (!icon) return;
    const previous = paintedSprites.get(icon);
    if (previous?.url !== sprite?.url || previous === undefined) {
        icon.style.backgroundImage = sprite ? `url("${sprite.url}")` : '';
        icon.classList.toggle('resource-icon-ready', !!sprite);
    }
    if (sprite) {
        const size = 'var(--resource-icon-size, 32px)';
        if (
            !previous ||
            previous.pageWidth / previous.width !== sprite.pageWidth / sprite.width ||
            previous.pageHeight / previous.height !== sprite.pageHeight / sprite.height
        ) {
            icon.style.backgroundSize = `calc(${sprite.pageWidth / sprite.width} * ${size}) calc(${sprite.pageHeight / sprite.height} * ${size})`;
        }
        if (
            !previous ||
            previous.x / previous.width !== sprite.x / sprite.width ||
            previous.y / previous.height !== sprite.y / sprite.height
        ) {
            icon.style.backgroundPosition = `calc(${-sprite.x / sprite.width} * ${size}) calc(${-sprite.y / sprite.height} * ${size})`;
        }
    }
    paintedSprites.set(icon, sprite);
}
