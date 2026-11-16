const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const modules = Promise.all(
    ['standing-orders', 'i18n'].map(
        (name) =>
            import(pathToFileURL(path.resolve(__dirname, `../../build/generated/frontend/assets/web/app/${name}.js`)))
    )
);
const reasons = [
    'WAITING_FIRST_READ',
    'READ_FAILED',
    'WAITING_FRESH_READ_AFTER_JOB',
    'WAITING_READ_FOR_NEW_RESOURCES',
    'MISSING_INGREDIENTS',
    'CALCULATION_FAILED',
    'CALCULATION_TIMED_OUT',
    'SUBMIT_REJECTED',
    'SUBMIT_ERROR',
    'UNEXPECTED_ERROR',
    'INVALID_DEFINITION',
    'CAPABILITY_UNAVAILABLE',
    'UNKNOWN_RESOURCE',
    'AMBIGUOUS_RESOURCE',
    'NOT_CRAFTABLE',
    'EMITTER_BACKED',
    'AMOUNT_OVERFLOW',
    'NOTHING_TO_ORDER',
    'NO_IDLE_CPU',
    'SELECTED_CPU_UNAVAILABLE',
    'CALCULATION_SLOTS_FULL'
];
const runtime = (extra) => ({
    status: 'IDLE',
    reason: null,
    resource: null,
    path: null,
    submitError: null,
    missing: [],
    changedAt: 0,
    lastSubmittedAmount: 0,
    nextCheckAt: 0,
    craftingSince: 0,
    ...extra
});

for (const language of ['en', 'pl']) {
    test(`every status reason has its own text with its values filled in (${language})`, async () => {
        const [{ statusExplanation }, { createI18n }] = await modules;
        const locale = createI18n().forLanguage(language);
        const resources = { quartz: { displayName: '§bCertus Quartz' } };
        const texts = reasons.map(
            (reason) =>
                statusExplanation(
                    runtime({ reason, resource: 'quartz', path: 'action.options.lightMode' }),
                    resources,
                    locale
                ).reason
        );
        for (const [index, text] of texts.entries())
            assert.ok(text && !text.includes(reasons[index]) && !text.includes('{'), `${reasons[index]}: ${text}`);
        assert.equal(new Set(texts).size, reasons.length, 'no two reasons read the same');
        const ambiguous = texts[reasons.indexOf('AMBIGUOUS_RESOURCE')];
        assert.ok(ambiguous.includes('Certus Quartz') && !ambiguous.includes('§'), ambiguous);
        assert.ok(texts[reasons.indexOf('CAPABILITY_UNAVAILABLE')].includes(locale.common('craftingLightMode')));
    });

    test(`missing ingredients are listed by name and amount (${language})`, async () => {
        const [{ statusExplanation }, { createI18n }] = await modules;
        const locale = createI18n().forLanguage(language);
        const { missing } = statusExplanation(
            runtime({
                status: 'BLOCKED_MISSING',
                reason: 'MISSING_INGREDIENTS',
                missing: [
                    { resource: 'redstone', displayName: 'Redstone', amount: 12000 },
                    { resource: null, displayName: 'Mystery Dust', amount: 3 }
                ]
            }),
            {},
            locale
        );
        assert.equal(missing.length, 2);
        assert.ok(missing[0].includes('Redstone') && missing[0].includes(locale.number(12000)), missing[0]);
        assert.ok(missing[1].includes('Mystery Dust') && missing[1].includes('3'), missing[1]);
    });
}

test('the crafting system error is passed on as it came, and an unknown or absent reason adds no text', async () => {
    const [{ statusExplanation }, { createI18n }] = await modules;
    const locale = createI18n().forLanguage('pl');
    const rejected = statusExplanation(
        runtime({ status: 'SUBMIT_FAILED', reason: 'SUBMIT_REJECTED', submitError: 'No suitable CPU found.' }),
        {},
        locale
    );
    assert.equal(rejected.submitError, 'No suitable CPU found.');
    assert.equal(statusExplanation(runtime({ reason: 'SOMETHING_NEW' }), {}, locale).reason, null);
    assert.equal(statusExplanation(runtime({}), {}, locale).reason, null);
});
