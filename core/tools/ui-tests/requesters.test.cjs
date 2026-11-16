const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const modules = Promise.all(
    ['requesters', 'i18n'].map(
        (name) =>
            import(pathToFileURL(path.resolve(__dirname, `../../build/generated/frontend/assets/web/app/${name}.js`)))
    )
);

for (const language of ['en', 'pl']) {
    test(`a standing order requester shows its name rather than Automation (${language})`, async () => {
        const [{ requesterLabel }, { createI18n }] = await modules;
        const { common } = createI18n().forLanguage(language);
        const automation = requesterLabel({ source: 'AUTOMATION', player: null }, common);
        const label = requesterLabel(
            { source: 'STANDING_ORDER', player: null, orderKey: 'key', orderName: '<b>Keep plastic</b>' },
            common
        );
        assert.ok(label.includes('<b>Keep plastic</b>'), 'the order name is shown literally');
        assert.notEqual(label, automation);
        assert.notEqual(label, requesterLabel({ source: 'WEB', player: null }, common));
        assert.ok(!label.includes('{'), 'the name placeholder is filled in');
    });
}

test('the English and Polish labels read as the documented wording', async () => {
    const [{ requesterLabel }, { createI18n }] = await modules;
    const order = { source: 'STANDING_ORDER', player: null, orderKey: 'k', orderName: 'Plastic' };
    assert.equal(requesterLabel(order, createI18n().forLanguage('en').common), 'Standing order: Plastic');
    assert.equal(requesterLabel(order, createI18n().forLanguage('pl').common), 'Zlecenie stałe: Plastic');
});

test('players keep their own names, including look-alikes of the reserved sources', async () => {
    const [{ requesterLabel }, { createI18n }] = await modules;
    const { common } = createI18n().forLanguage('en');
    assert.equal(requesterLabel({ source: 'PLAYER', player: { uuid: 'u', name: 'Automation' } }, common), 'Automation');
    assert.equal(requesterLabel({ source: 'WEB', player: null }, common), 'Web');
});
