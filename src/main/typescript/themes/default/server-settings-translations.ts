export const serverSettingsTranslations = {
    en: {
        serverSettingsIntro:
            'Save and apply server configuration. The administrator password and web port can only be changed in config.toml.',
        serverGeneral: 'General and access',
        serverPublicMode: 'Allow player accounts',
        serverPublicModeHelp: 'When disabled, only the administrator account can log in.',
        serverLocalhost: 'Allow localhost without a password',
        serverLocalhostHelp:
            'Loopback clients receive administrator access. Disabling this may require you to log in on your next request.',
        serverUpdates: 'Check for mod updates',
        serverLoginRate: 'Unauthenticated requests per minute',
        serverProxies: 'Trusted proxies',
        serverProxiesHelp:
            'Comma-separated IP addresses or CIDRs. Only trust proxies you control: forwarded addresses affect administrator access. Loopback proxies are trusted automatically.',
        serverDefaultTheme: 'Default theme',
        serverDefaultThemeHelp:
            'Use builtin:default or server:<theme id>. Applies on the next page load for browsers following the server default. Theme ZIPs are discovered at server startup.',
        serverNotifications: 'Notifications',
        serverNotificationsHelp: 'Both minimums must be met. Zero disables a filter.',
        serverMinimumDuration: 'Minimum crafting duration (seconds)',
        serverMinimumAmount: 'Minimum crafted amount',
        serverWebAddress: 'Public web address',
        serverWebAddressHelp: 'Address opened when an ntfy notification is clicked. Leave empty to omit the link.',
        serverDiscord: 'Discord',
        serverDiscordHelp: 'An empty webhook disables Discord notifications. Player accounts must also be disabled.',
        serverWebhook: 'Webhook URL',
        serverRole: 'Role ID to mention on errors',
        serverNtfy: 'ntfy',
        serverNtfyHelp:
            'An empty host or topic disables ntfy notifications. Leave credentials empty for topics without authentication.',
        serverNtfyHost: 'Host',
        serverNtfyTopic: 'Topic',
        serverNtfyUser: 'Username',
        serverNtfyPassword: 'Password',
        serverResourceHistory: 'Resource history',
        serverHistoryHelp:
            'Each network chooses its own values within these bounds. A record cap may shorten retention. Cleanup runs in the background.',
        serverStockInterval: 'Stock sample interval (seconds)',
        serverFlowInterval: 'Flow save interval (seconds)',
        serverRetention: 'Retention (days)',
        serverBoundmin: 'Minimum',
        serverBounddefaultValue: 'Default',
        serverBoundmax: 'Maximum',
        serverBoundsInvalid: 'Minimum must not exceed default, and default must not exceed maximum.',
        serverRecordCap: 'Maximum history records per network',
        serverRecordCapHelp:
            'Approximate flow rows: days × 86400 × active resources ÷ flow interval in seconds. Add stock and coverage records. Lowering this cap may delete older history.',
        serverCraftingHistory: 'Crafting history',
        serverCraftingHistoryHelp:
            'Completed and cancelled jobs. The cap removes oldest jobs in full and may shorten retention.',
        serverJobCap: 'Maximum jobs per network',
        serverAdvanced: 'Live observation and capacity',
        serverAdvancedHelp:
            'These limits control observation cost and background queues. Queue overflow records a gap in history.',
        serverActiveInterval: 'Minimum live observation interval (ms)',
        serverReconciliation: 'Full storage reconciliation interval (seconds)',
        serverResourceCap: 'Maximum tracked resources per network',
        serverQueueCap: 'Maximum queued history rows',
        serverSave: 'Save and apply',
        serverReload: 'Reload from server',
        serverDiscardReload: 'Discard changes and reload',
        serverSaved: 'Configuration saved and applied.',
        serverAccessDenied: 'Only the administrator can access server settings.',
        serverConflict:
            'Configuration changed on the server. Your edits have been kept. Reload the latest settings before saving again.',
        serverSaveUnconfirmed:
            'The save could not be confirmed. Your edits have been kept. Reload the server configuration to check what was applied.'
    },
    pl: {
        serverSettingsIntro:
            'Zapisz i zastosuj konfigurację serwera. Hasło administratora i port WWW można zmienić tylko w config.toml.',
        serverGeneral: 'Ogólne i dostęp',
        serverPublicMode: 'Zezwalaj na konta graczy',
        serverPublicModeHelp: 'Po wyłączeniu logować może się tylko administrator.',
        serverLocalhost: 'Zezwalaj na localhost bez hasła',
        serverLocalhostHelp:
            'Klienci lokalni otrzymują dostęp administratora. Wyłączenie może wymagać zalogowania przy następnym żądaniu.',
        serverUpdates: 'Sprawdzaj aktualizacje moda',
        serverLoginRate: 'Żądania bez logowania na minutę',
        serverProxies: 'Zaufane proxy',
        serverProxiesHelp:
            'Adresy IP lub zakresy CIDR oddzielone przecinkami. Ufaj tylko własnym proxy: przekazane adresy wpływają na dostęp administratora. Lokalne proxy są zaufane automatycznie.',
        serverDefaultTheme: 'Domyślny motyw',
        serverDefaultThemeHelp:
            'Wpisz builtin:default lub server:<id motywu>. Zmiana działa po ponownym załadowaniu strony w przeglądarkach używających domyślnego motywu serwera. Paczki ZIP są wykrywane przy uruchamianiu serwera.',
        serverNotifications: 'Powiadomienia',
        serverNotificationsHelp: 'Oba minima muszą być spełnione. Zero wyłącza dany filtr.',
        serverMinimumDuration: 'Minimalny czas craftingu (sekundy)',
        serverMinimumAmount: 'Minimalna wyprodukowana ilość',
        serverWebAddress: 'Publiczny adres strony',
        serverWebAddressHelp: 'Adres otwierany po kliknięciu powiadomienia ntfy. Puste pole wyłącza link.',
        serverDiscord: 'Discord',
        serverDiscordHelp: 'Pusty webhook wyłącza powiadomienia Discord. Konta graczy również muszą być wyłączone.',
        serverWebhook: 'Adres webhooka',
        serverRole: 'ID roli oznaczanej przy błędach',
        serverNtfy: 'ntfy',
        serverNtfyHelp:
            'Pusty host lub temat wyłącza powiadomienia ntfy. Dla tematów bez uwierzytelniania pozostaw dane logowania puste.',
        serverNtfyHost: 'Host',
        serverNtfyTopic: 'Temat',
        serverNtfyUser: 'Użytkownik',
        serverNtfyPassword: 'Hasło',
        serverResourceHistory: 'Historia zasobów',
        serverHistoryHelp:
            'Każda sieć wybiera wartości w tych granicach. Limit rekordów może skrócić retencję. Czyszczenie działa w tle.',
        serverStockInterval: 'Interwał próbkowania stanu (sekundy)',
        serverFlowInterval: 'Interwał zapisu przepływu (sekundy)',
        serverRetention: 'Retencja (dni)',
        serverBoundmin: 'Minimum',
        serverBounddefaultValue: 'Domyślnie',
        serverBoundmax: 'Maksimum',
        serverBoundsInvalid: 'Minimum nie może przekraczać wartości domyślnej, a wartość domyślna maksimum.',
        serverRecordCap: 'Maksymalna liczba rekordów na sieć',
        serverRecordCapHelp:
            'Przybliżona liczba rekordów przepływu: dni × 86400 × aktywne zasoby ÷ interwał przepływu w sekundach. Dodaj rekordy stanu i pokrycia. Obniżenie limitu może usunąć starszą historię.',
        serverCraftingHistory: 'Historia craftingu',
        serverCraftingHistoryHelp:
            'Zakończone i anulowane zadania. Limit usuwa najstarsze zadania w całości i może skrócić retencję.',
        serverJobCap: 'Maksymalna liczba zadań na sieć',
        serverAdvanced: 'Obserwacja na żywo i pojemność',
        serverAdvancedHelp:
            'Limity sterują kosztem obserwacji i kolejkami w tle. Przepełnienie kolejki zapisuje lukę w historii.',
        serverActiveInterval: 'Minimalny interwał obserwacji na żywo (ms)',
        serverReconciliation: 'Interwał pełnego odczytu magazynu (sekundy)',
        serverResourceCap: 'Maksymalna liczba śledzonych zasobów na sieć',
        serverQueueCap: 'Maksymalna liczba rekordów w kolejce',
        serverSave: 'Zapisz i zastosuj',
        serverReload: 'Wczytaj ponownie z serwera',
        serverDiscardReload: 'Odrzuć zmiany i wczytaj ponownie',
        serverSaved: 'Konfiguracja zapisana i zastosowana.',
        serverAccessDenied: 'Tylko administrator ma dostęp do ustawień serwera.',
        serverConflict:
            'Konfiguracja na serwerze uległa zmianie. Twoje zmiany zostały zachowane. Wczytaj aktualne ustawienia przed kolejnym zapisem.',
        serverSaveUnconfirmed:
            'Nie udało się potwierdzić zapisu. Twoje zmiany zostały zachowane. Wczytaj konfigurację serwera, aby sprawdzić, co zostało zastosowane.'
    }
};
