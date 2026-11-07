// The timestamp checks depend on the zone, so fix it before any date is created.
process.env.TZ = 'Europe/Warsaw';

const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const web = path.resolve(__dirname, '../../build/generated/frontend/assets/web');
const i18n = import(pathToFileURL(path.join(web, 'app/i18n.js')));
const quantity = import(pathToFileURL(path.join(web, 'themes/default/resource-quantity.js')));
const translators = async () => {
    const { createI18n } = await i18n;
    return { en: createI18n().forLanguage('en'), pl: createI18n().forLanguage('pl') };
};

test('durations keep milliseconds below a minute and whole seconds from a minute, per language', async () => {
    const { en, pl } = await translators();
    const cases = [
        [0, '0 s', '0 s'],
        [59999, '59.999 s', '59,999 s'],
        [60000, '1 min', '1 min'],
        [60001, '1 min', '1 min'],
        [61999, '1 min 1 s', '1 min 1 s'],
        [3599999, '59 min 59 s', '59 min 59 s'],
        [3600000, '1 h', '1 godz.'],
        [86400000, '1 d', '1 dzień'],
        [93784005, '1 d 2 h 3 min 4 s', '1 dzień 2 godz. 3 min 4 s'],
        [1728000000, '20 d', '20 dni']
    ];
    for (const [milliseconds, english, polish] of cases) {
        assert.equal(en.duration(milliseconds), english, `en ${milliseconds}`);
        assert.equal(pl.duration(milliseconds), polish, `pl ${milliseconds}`);
    }
    assert.equal(en.duration(-5), '0 s');
});

test('memory sizes use binary steps with two decimals through exabytes', async () => {
    const { en, pl } = await translators();
    const capacities = [
        0,
        1023,
        1024,
        65536,
        1572864,
        1073741824,
        1099511627776,
        1125899906842624,
        JSON.parse('9223372036854775807')
    ];
    assert.deepEqual(
        capacities.map((value) => en.bytes(value)),
        ['0 B', '1,023 B', '1 KB', '64 KB', '1.5 MB', '1 GB', '1 TB', '1 PB', '8 EB']
    );
    assert.equal(pl.bytes(1572864), '1,5 MB');
    assert.equal(en.bytes(18432), '18 KB');
});

test('slot quantities abbreviate from ten thousand with four-character SI suffixes rounded down', async () => {
    const { slotQuantity } = await quantity;
    const { en } = await translators();
    const samples = [
        [9999, '9,999'],
        [10000, '10k'],
        [128640, '128k'],
        [999999, '999k'],
        [1000000, '1M'],
        [2457600, '2.4M'],
        [1999999999, '1.9G'],
        [1000000000000, '1T'],
        [1000000000000000, '1P'],
        [1000000000000000000, '1E']
    ];
    for (const [value, display] of samples) assert.equal(slotQuantity(value, en), display, String(value));
});

test('timestamps show 24-hour whole seconds and add the date only outside the reference day', async () => {
    const { en, pl } = await translators();
    const started = Date.parse('2025-12-31T22:58:00.123Z');
    const sameDay = Date.parse('2025-12-31T22:59:59.456Z');
    const nextDay = Date.parse('2025-12-31T23:00:01.789Z');
    for (const locale of [en, pl]) {
        assert.equal(locale.dateTime(sameDay, started), '23:59:59');
        assert.equal(locale.time(nextDay), '00:00:01');
        assert.match(locale.dateTime(nextDay, started), /2026.*00:00:01/);
        assert.match(locale.dateTime(started), /2025.*23:58:00/);
    }
});
