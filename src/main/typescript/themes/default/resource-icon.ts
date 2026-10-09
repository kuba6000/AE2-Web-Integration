import type { IconSprite } from '../../app/icons.js';

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
    icon.style.backgroundImage = sprite ? `url("${sprite.url}")` : '';
    icon.classList.toggle('resource-icon-ready', !!sprite);
    if (sprite) {
        const size = 'var(--resource-icon-size, 32px)';
        icon.style.backgroundSize = `calc(${sprite.pageWidth / sprite.width} * ${size}) calc(${sprite.pageHeight / sprite.height} * ${size})`;
        icon.style.backgroundPosition = `calc(${-sprite.x / sprite.width} * ${size}) calc(${-sprite.y / sprite.height} * ${size})`;
    }
}
