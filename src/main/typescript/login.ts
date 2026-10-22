import { createApi } from './app/api.js';
import { createSettings } from './app/storage.js';

function element<T extends HTMLElement>(id: string): T {
    const value = document.getElementById(id);
    if (!value) throw new Error(`Missing login element: ${id}`);
    return value as T;
}

const login = element<HTMLFormElement>('formlogin');
const register = element<HTMLFormElement>('formregister');
const confirmation = element('confirmation');
const formSwitch = element('formSwitch');
const loginSwitch = element<HTMLButtonElement>('loginSwitch');
const registerSwitch = element<HTMLButtonElement>('registerSwitch');
const status = element('bootstrapStatus');
const retry = element<HTMLButtonElement>('bootstrapRetry');
const command = element<HTMLTextAreaElement>('registrationCommand');
const controller = new AbortController();
let disposed = false;

const appearanceButtons = document.querySelectorAll<HTMLButtonElement>('[data-appearance]');
const settings = createSettings(new URL('./', location.href), 'login');
const savedAppearance = settings.get('appearance');
let appearance = savedAppearance === 'light' || savedAppearance === 'dark' ? savedAppearance : 'system';
function applyAppearance(): void {
    document.documentElement.style.colorScheme = appearance === 'system' ? 'light dark' : appearance;
    for (const button of appearanceButtons)
        button.setAttribute('aria-pressed', String(button.dataset.appearance === appearance));
}
applyAppearance();
for (const button of appearanceButtons)
    button.addEventListener('click', () => {
        const choice = button.dataset.appearance;
        if (choice !== 'light' && choice !== 'dark' && choice !== 'system') return;
        appearance = choice;
        applyAppearance();
        settings.set('appearance', appearance);
    });

function showForm(registration: boolean): void {
    login.hidden = registration;
    register.hidden = !registration;
    confirmation.hidden = true;
    loginSwitch.setAttribute('aria-pressed', String(!registration));
    registerSwitch.setAttribute('aria-pressed', String(registration));
}

function readStatus(): boolean {
    const url = new URL(location.href);
    const errors: Record<string, string> = {
        NOT_ONLINE: 'Join the Minecraft server before registering.',
        INVALID_PASSWORD: 'Invalid password. Please try again.',
        INVALID_USER: 'Invalid username. Check your in-game name.',
        NO_PERMISSIONS: 'Registration is not available on this server.',
        SERVER_BUSY: 'The server is busy. Please try again shortly.',
        SERVER_STOPPING: 'The server is stopping. Please try again later.',
        TIMEOUT: 'The server took too long to respond. Please try again.',
        INTERNAL_ERROR: 'The server could not complete this request. Please try again.'
    };
    const error = Object.keys(errors).find((key) => url.searchParams.has(key));
    const token = url.searchParams.get('token');
    const confirming = url.searchParams.has('confirmregistration') && !!token;
    if (error) {
        element('maininfo').textContent = errors[error] ?? '';
        element('maininfo').hidden = false;
    } else if (confirming) {
        command.value = `/ae2webintegration auth ${token}`;
    }
    if (error || url.searchParams.has('confirmregistration')) {
        history.replaceState(null, document.title, url.pathname + url.hash);
    }
    return !error && confirming;
}

const confirming = readStatus();

async function loadSettings(): Promise<void> {
    retry.hidden = true;
    status.hidden = false;
    status.setAttribute('role', 'status');
    status.textContent = 'Loading server settings…';
    try {
        const metadata = await createApi(new URL('./', location.href), () => location.reload()).context(
            controller.signal
        );
        if (disposed) return;
        formSwitch.hidden = !metadata.publicMode;
        if (!metadata.publicMode) {
            const username = element<HTMLInputElement>('username');
            username.value = 'Admin';
            username.readOnly = true;
        }
        showForm(false);
        if (confirming && metadata.publicMode) {
            login.hidden = true;
            confirmation.hidden = false;
        }
        element('mainrow').hidden = false;
        status.hidden = true;
    } catch {
        if (disposed) return;
        status.setAttribute('role', 'alert');
        status.textContent = 'Could not load server settings. Check the connection and try again.';
        retry.hidden = false;
    }
}

loginSwitch.addEventListener('click', () => showForm(false));
registerSwitch.addEventListener('click', () => showForm(true));
element('confirmationDone').addEventListener('click', () => showForm(false));
retry.addEventListener('click', () => void loadSettings());

for (const button of document.querySelectorAll<HTMLButtonElement>('[data-password]')) {
    const input = element<HTMLInputElement>(button.dataset.password ?? '');
    button.addEventListener('click', () => {
        const reveal = input.type === 'password';
        input.type = reveal ? 'text' : 'password';
        button.setAttribute('aria-label', reveal ? 'Hide password' : 'Show password');
        button.setAttribute('aria-pressed', String(reveal));
    });
}

element('copyCommand').addEventListener('click', async () => {
    const copyStatus = element('copyStatus');
    try {
        await navigator.clipboard.writeText(command.value);
        copyStatus.textContent = 'Command copied.';
    } catch {
        command.focus();
        command.select();
        copyStatus.textContent = 'Copy the selected command using your browser’s copy menu.';
    }
});

window.addEventListener(
    'pagehide',
    () => {
        disposed = true;
        controller.abort();
    },
    { once: true }
);
window.addEventListener('pageshow', (event) => {
    if (event.persisted) location.reload();
});
void loadSettings();
