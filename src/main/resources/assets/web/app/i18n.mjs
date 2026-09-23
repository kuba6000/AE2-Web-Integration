const translations = {
    en: {
        title: 'AE2 Web Integration', home: 'Home', terminal: 'Terminal', network: 'Network',
        chooseNetwork: 'Choose a network', noNetworks: 'No accessible networks',
        noNetworksHelp: 'Check that your network is loaded and you have access, then refresh.',
        homeHelp: 'Choose a network to browse its resources.', openNetwork: 'Open {owner} network',
        gridOwner: 'Owner: {owner}', cpuCount: { one: '{count} CPU', other: '{count} CPUs' },
        search: 'Search resources', searchHint: 'Name or registry ID', resources: 'Resources',
        all: 'All', stored: 'In storage', craftable: 'Craftable', sort: 'Sort by',
        name: 'Name', quantity: 'Quantity', id: 'Registry ID', refresh: 'Refresh',
        autoRefresh: 'Refresh automatically', loading: 'Loading…', refreshing: 'Refreshing…',
        retry: 'Try again', previousPage: 'Previous page', nextPage: 'Next page', empty: 'No resources in this network', noMatches: 'No matching resources',
        resetSearch: 'Clear search and filters', details: 'Resource details', selectItem: 'Select a resource to see its details.',
        craftableYes: 'Crafting available', craftableNo: 'Not craftable', identityUnavailable: 'This resource currently has no usable web identity.',
        resourceCount: { one: '{count} resource', other: '{count} resources' },
        language: 'Language', appearance: 'Appearance', light: 'Light', dark: 'Dark', system: 'System',
        logout: 'Log out', previous: 'Crafting, CPUs and history', previousHelp: 'Open these features in the previous interface.',
        updated: 'Updated {time}', unknownOwner: 'Unknown', invalidRoute: 'This view does not exist.',
        error: 'Could not load data.', NETWORK_ERROR: 'Cannot connect to the server.',
        NO_PERMISSIONS: 'You no longer have access to this network.', GRID_NOT_FOUND: 'This network is not available.',
        UNAUTHORIZED: 'Your session has expired. Returning to login…', TOO_MANY_REQUESTS: 'Too many requests. Please wait before retrying.',
        SERVER_BUSY: 'The server is busy. Please try again.', SERVER_STOPPING: 'The server is stopping.',
        TIMEOUT: 'The request timed out.', INVALID_RESPONSE: 'The server returned an unreadable response.'
    },
    pl: {
        title: 'AE2 Web Integration', home: 'Pulpit', terminal: 'Terminal', network: 'Sieć',
        chooseNetwork: 'Wybierz sieć', noNetworks: 'Brak dostępnych sieci',
        noNetworksHelp: 'Sprawdź, czy sieć jest załadowana i masz do niej dostęp, a następnie odśwież.',
        homeHelp: 'Wybierz sieć, aby przeglądać jej zasoby.', openNetwork: 'Otwórz sieć: {owner}',
        gridOwner: 'Właściciel: {owner}', cpuCount: { one: '{count} procesor CPU', few: '{count} procesory CPU', many: '{count} procesorów CPU', other: '{count} procesora CPU' },
        search: 'Szukaj zasobów', searchHint: 'Nazwa lub identyfikator', resources: 'Zasoby',
        all: 'Wszystkie', stored: 'W magazynie', craftable: 'Craftowalne', sort: 'Sortuj według',
        name: 'Nazwy', quantity: 'Ilości', id: 'Identyfikatora', refresh: 'Odśwież',
        autoRefresh: 'Odświeżaj automatycznie', loading: 'Ładowanie…', refreshing: 'Odświeżanie…',
        retry: 'Spróbuj ponownie', previousPage: 'Poprzednia strona', nextPage: 'Następna strona', empty: 'Brak zasobów w tej sieci', noMatches: 'Brak pasujących zasobów',
        resetSearch: 'Wyczyść wyszukiwanie i filtry', details: 'Szczegóły zasobu', selectItem: 'Wybierz zasób, aby zobaczyć jego szczegóły.',
        craftableYes: 'Dostępny crafting', craftableNo: 'Brak craftingu', identityUnavailable: 'Ten zasób nie ma obecnie dostępnego identyfikatora webowego.',
        resourceCount: { one: '{count} zasób', few: '{count} zasoby', many: '{count} zasobów', other: '{count} zasobu' },
        language: 'Język', appearance: 'Wygląd', light: 'Jasny', dark: 'Ciemny', system: 'Systemowy',
        logout: 'Wyloguj', previous: 'Crafting, CPU i historia', previousHelp: 'Otwórz te funkcje w poprzednim interfejsie.',
        updated: 'Aktualizacja: {time}', unknownOwner: 'Nieznany', invalidRoute: 'Ten widok nie istnieje.',
        error: 'Nie udało się pobrać danych.', NETWORK_ERROR: 'Brak połączenia z serwerem.',
        NO_PERMISSIONS: 'Nie masz już dostępu do tej sieci.', GRID_NOT_FOUND: 'Ta sieć jest niedostępna.',
        UNAUTHORIZED: 'Sesja wygasła. Powrót do logowania…', TOO_MANY_REQUESTS: 'Zbyt wiele zapytań. Poczekaj przed ponowieniem.',
        SERVER_BUSY: 'Serwer jest zajęty. Spróbuj ponownie.', SERVER_STOPPING: 'Serwer jest zatrzymywany.',
        TIMEOUT: 'Upłynął czas oczekiwania.', INVALID_RESPONSE: 'Serwer zwrócił nieczytelną odpowiedź.'
    }
};

export function createTranslator(language) {
    const locale = translations[language] || translations.en;
    const numbers = new Intl.NumberFormat(language);
    const plural = new Intl.PluralRules(language);
    function t(key, values = {}) {
        let message = locale[key] || translations.en[key] || locale.error;
        if (typeof message === 'object') message = message[plural.select(values.count)] || message.other;
        return message.replace(/\{(\w+)\}/g, (_, name) => typeof values[name] === 'number' ? numbers.format(values[name]) : String(values[name] ?? ''));
    }
    return { t, number: value => numbers.format(value), time: value => new Intl.DateTimeFormat(language, { timeStyle: 'medium' }).format(value) };
}
