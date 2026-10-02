import type { IconSprite } from '../../app/icons.js';

export function createResourceIcon() {
    const icon = document.createElement('span');
    icon.className = 'resource-icon';
    icon.setAttribute('aria-hidden', 'true');
    return icon;
}

/** Keep the atlas crop in the theme's 32px slot; item names remain the accessible content. */
export function paintResourceIcon(target: HTMLElement, sprite: IconSprite | null) {
    const icon = target.querySelector<HTMLElement>('.resource-icon');
    if (!icon) return;
    icon.style.backgroundImage = sprite ? `url("${sprite.url}")` : '';
    icon.classList.toggle('resource-icon-ready', !!sprite);
    if (sprite) {
        const scale = 32 / sprite.width;
        icon.style.backgroundSize = `${sprite.pageWidth * scale}px ${sprite.pageHeight * scale}px`;
        icon.style.backgroundPosition = `${-sprite.x * scale}px ${-sprite.y * scale}px`;
    }
}
