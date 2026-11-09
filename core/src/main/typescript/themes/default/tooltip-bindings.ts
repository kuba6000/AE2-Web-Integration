export type TooltipBindings = {
    bind(target: HTMLElement, content: () => Node[], focusTarget?: HTMLElement): void;
    hide(): void;
};
