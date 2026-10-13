import type { ThemeManager } from './theme-manager.js';

const messages = {
    en: {
        title: 'Themes',
        close: 'Close',
        selection: 'Choose a theme',
        serverDefault: 'Use server default',
        builtin: 'Built-in',
        server: 'Server',
        apply: 'Apply',
        safe: 'Safe mode is active. Custom themes have not been started.',
        failure: 'Could not complete this operation.',
        active: 'Active theme',
        loading: 'Working…',
        loadingTheme: 'Loading theme…',
        recover: 'Open default UI in safe mode',
        exitSafe: 'Exit safe mode'
    },
    pl: {
        title: 'Motywy',
        close: 'Zamknij',
        selection: 'Wybierz motyw',
        serverDefault: 'Używaj domyślnego motywu serwera',
        builtin: 'Wbudowany',
        server: 'Serwerowy',
        apply: 'Zastosuj',
        safe: 'Tryb awaryjny jest aktywny. Nie uruchomiono własnych motywów.',
        failure: 'Nie udało się wykonać operacji.',
        active: 'Aktywny motyw',
        loading: 'Przetwarzanie…',
        loadingTheme: 'Ładowanie motywu…',
        recover: 'Otwórz domyślny UI w trybie awaryjnym',
        exitSafe: 'Wyjdź z trybu awaryjnego'
    }
};

const styles = `
:host { all: initial; color-scheme: light; }
* { box-sizing: border-box; }
[hidden] { display: none !important; }
button, select { font: inherit; color: inherit; }
button { border: 1px solid #8a9098; border-radius: 4px; background: #f3f4f6; padding: 8px 12px; cursor: pointer; }
button:hover:not(:disabled) { background: #e4e7eb; }
button:disabled { opacity: .55; cursor: default; }
:focus-visible { outline: 2px solid #4d647d; outline-offset: 2px; }
.recovery-notice { max-width: 720px; margin: 12px auto; padding: 12px; border: 1px solid #7c858f; border-radius: 4px; background: #fff; color: #20252a; font: 14px/1.5 system-ui, sans-serif; overflow-wrap: anywhere; }
.recovery-notice .actions { margin-top: 8px; align-items: center; }
dialog { margin: auto; width: min(520px, calc(100vw - 24px)); max-height: calc(100dvh - 24px); overflow: auto; padding: 20px; border: 1px solid #7c858f; border-radius: 8px; background: #fff; color: #20252a; font: 14px/1.5 system-ui, sans-serif; }
dialog::backdrop { background: #0008; }
header { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
h2, p { margin: 0; }
h2 { font-size: 20px; }
.close { padding: 4px 10px; font-size: 20px; }
section { display: grid; gap: 12px; padding-top: 20px; }
label { display: grid; gap: 6px; }
select { width: 100%; min-width: 0; padding: 8px; border: 1px solid #8a9098; border-radius: 4px; background: #fff; }
.actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.secondary { color: #505963; font-size: 13px; overflow-wrap: anywhere; }
.notice { margin-top: 12px; overflow-wrap: anywhere; }
.error { color: #a12525; }
`;

/** Kept outside theme-owned DOM and styles so a broken presentation cannot strand its selection. */
export function createThemeDialog(manager: ThemeManager, language: () => string, root: HTMLElement) {
    const host = document.createElement('div');
    const shadow = host.attachShadow({ mode: 'open' });
    shadow.innerHTML = `<style>${styles}</style>
        <aside class="recovery-notice" hidden>
            <p id="recovery-message"></p>
            <div class="actions"><a id="notice-recovery"></a></div>
        </aside>
        <dialog aria-labelledby="theme-title">
            <header><h2 id="theme-title"></h2><button type="button" class="close">×</button></header>
            <p class="notice secondary" id="safe" hidden></p>
            <p class="notice error" id="error" role="alert" hidden></p>
            <p class="notice" id="warning" role="status" hidden></p>
            <section>
                <label><span id="selection-label"></span><select id="selection"></select></label>
                <p class="secondary" id="active"></p>
                <div class="actions"><button type="button" id="apply"></button></div>
            </section>
            <p class="notice secondary" id="progress" role="status" hidden></p><p class="notice"><a id="recovery"></a></p>
        </dialog>`;
    root.before(host);
    const controller = new AbortController();
    const eventOptions = { signal: controller.signal };
    const find = <T extends HTMLElement>(selector: string) => shadow.querySelector<T>(selector)!;
    const dialog = find<HTMLDialogElement>('dialog');
    const notice = find<HTMLElement>('.recovery-notice');
    const close = find<HTMLButtonElement>('.close');
    const selection = find<HTMLSelectElement>('#selection');
    const apply = find<HTMLButtonElement>('#apply');
    let returnFocus: HTMLElement | null = null;
    let localError: string | null = null;
    let operation = false;
    let disposed = false;
    let loadingTimer: ReturnType<typeof setTimeout> | undefined;
    let loadingVisible = false;
    let shownError: string | null = null;
    let selected = manager.state.selected;
    let draft = selected ?? '';

    function text() {
        return language().startsWith('pl') ? messages.pl : messages.en;
    }

    function render() {
        if (disposed) return;
        const words = text();
        const state = manager.state;
        const busy = operation || state.busy;
        if (state.busy) {
            if (loadingTimer === undefined && !loadingVisible) {
                loadingTimer = setTimeout(() => {
                    loadingTimer = undefined;
                    loadingVisible = true;
                    render();
                }, 1000);
            }
        } else {
            clearTimeout(loadingTimer);
            loadingTimer = undefined;
            loadingVisible = false;
        }
        notice.hidden = !loadingVisible || dialog.open;
        const noticeMessage = find('#recovery-message');
        noticeMessage.textContent = words.loadingTheme;
        noticeMessage.setAttribute('role', 'status');
        const recovery = find<HTMLAnchorElement>('#recovery');
        const target = new URL(location.href);
        if (state.safe) target.searchParams.delete('safe');
        else target.searchParams.set('safe', '1');
        recovery.href = target.href;
        recovery.textContent = state.safe ? words.exitSafe : words.recover;
        const noticeRecovery = find<HTMLAnchorElement>('#notice-recovery');
        noticeRecovery.href = target.href;
        noticeRecovery.textContent = recovery.textContent;
        host.lang = language();
        find('#theme-title').textContent = words.title;
        close.setAttribute('aria-label', words.close);
        find('#selection-label').textContent = words.selection;
        if (state.selected !== selected) {
            selected = state.selected;
            draft = selected ?? '';
        }
        const defaultName = state.choices.find((entry) => entry.key === state.serverDefault)?.name;
        selection.replaceChildren(new Option(`${words.serverDefault}${defaultName ? ` (${defaultName})` : ''}`, ''));
        for (const entry of state.choices) {
            selection.add(new Option(`${entry.name} — ${words[entry.source]}`, entry.key));
        }
        selection.value = state.choices.some((entry) => entry.key === draft) ? draft : '';
        const active = state.choices.find((entry) => entry.key === state.active);
        find('#active').textContent = `${words.active}: ${active?.name ?? state.active}`;
        selection.disabled = busy;
        apply.textContent = words.apply;
        apply.disabled = busy;
        const error = localError ?? state.error;
        find('#error').textContent = error;
        find('#error').hidden = !error;
        find('#warning').textContent = state.warning;
        find('#warning').hidden = !state.warning;
        find('#safe').textContent = words.safe;
        find('#safe').hidden = !state.safe;
        find('#progress').textContent = words.loading;
        find('#progress').hidden = !busy;
        const newError = state.error && state.error !== shownError;
        shownError = state.error;
        if (newError && !dialog.open) open();
    }

    async function run(action: () => Promise<void>) {
        if (operation || manager.state.busy) return;
        operation = true;
        localError = null;
        render();
        try {
            await action();
        } catch (error) {
            localError = error instanceof Error ? error.message : text().failure;
        } finally {
            operation = false;
            render();
        }
    }

    function open() {
        if (disposed || dialog.open) return;
        returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
        draft = manager.state.selected ?? '';
        localError = null;
        render();
        dialog.showModal();
        render();
        selection.focus();
    }

    close.addEventListener('click', () => dialog.close(), eventOptions);
    dialog.addEventListener(
        'close',
        () => {
            render();
            if (returnFocus?.isConnected) returnFocus.focus();
        },
        eventOptions
    );
    selection.addEventListener(
        'change',
        () => {
            draft = selection.value;
            render();
        },
        eventOptions
    );
    apply.addEventListener('click', () => void run(() => manager.select(selection.value || null)), eventOptions);
    const unsubscribe = manager.subscribe(render);
    render();
    return {
        open,
        dispose() {
            if (disposed) return;
            disposed = true;
            clearTimeout(loadingTimer);
            unsubscribe();
            controller.abort();
            dialog.close();
            host.remove();
        }
    };
}
