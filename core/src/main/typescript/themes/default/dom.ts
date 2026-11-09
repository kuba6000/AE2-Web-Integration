export function element<Tag extends keyof HTMLElementTagNameMap>(
    tag: Tag,
    text = '',
    className = ''
): HTMLElementTagNameMap[Tag] {
    const node = document.createElement(tag);
    node.textContent = text;
    if (className) node.className = className;
    return node;
}
