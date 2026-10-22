const settings = () => ({
    general: {
        trustedProxies: '',
        maxRequestsBeforeLoggedInPerMinute: 20,
        checkForUpdates: true,
        defaultTheme: 'builtin:default'
    },
    notifications: { minimumCraftingDurationSeconds: 0, minimumCraftingAmount: 0, fullDomain: '' },
    discord: { webhook: '', roleId: '' },
    ntfy: { host: '', topic: '', user: '', password: '' },
    resourceHistory: {
        stockSampleIntervalSeconds: { min: 60, max: 900, defaultValue: 300 },
        flowFlushIntervalSeconds: { min: 60, max: 900, defaultValue: 60 },
        historyRetentionDays: { min: 1, max: 15, defaultValue: 7 },
        historyMaxRowsPerGrid: 250000
    },
    craftingHistory: { historyRetentionDays: { min: 1, max: 15, defaultValue: 7 }, historyMaxJobsPerGrid: 1000 },
    resourceTracking: {
        activeIntervalMillis: 250,
        reconciliationIntervalSeconds: 300,
        maxResourcesPerGrid: 262144,
        maxQueuedRows: 1048576
    }
});

const readOnly = () => ({
    port: 2324,
    publicMode: true,
    allowNoPasswordOnLocalhost: true,
    adminPasswordConfigured: true
});

module.exports = { settings, readOnly };
