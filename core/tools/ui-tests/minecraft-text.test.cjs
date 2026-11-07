const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const text = import(
    pathToFileURL(path.resolve(__dirname, '../../build/generated/frontend/assets/web/app/minecraft-text.js'))
);
const plain = { color: null, bold: false, italic: false, underline: false, strikethrough: false, obfuscated: false };

test('section codes decode colors and decorations, and a color or reset clears earlier decorations', async () => {
    const { parseMinecraftText } = await text;
    assert.deepEqual(
        parseMinecraftText('Base §aGreen §lBold §oItalic §nUnderlined §mStruck §BColorCleared §RRestored'),
        [
            { ...plain, text: 'Base ' },
            { ...plain, color: 'green', text: 'Green ' },
            { ...plain, color: 'green', bold: true, text: 'Bold ' },
            { ...plain, color: 'green', bold: true, italic: true, text: 'Italic ' },
            { ...plain, color: 'green', bold: true, italic: true, underline: true, text: 'Underlined ' },
            {
                ...plain,
                color: 'green',
                bold: true,
                italic: true,
                underline: true,
                strikethrough: true,
                text: 'Struck '
            },
            { ...plain, color: 'aqua', text: 'ColorCleared ' },
            { ...plain, text: 'Restored' }
        ]
    );
});

test('decoration codes apply without a color and codes are case-insensitive', async () => {
    const { parseMinecraftText } = await text;
    assert.deepEqual(parseMinecraftText('§L§O§N§M§KHeavy'), [
        { ...plain, bold: true, italic: true, underline: true, strikethrough: true, obfuscated: true, text: 'Heavy' }
    ]);
});

test('all sixteen classic colors map to their names', async () => {
    const { parseMinecraftText } = await text;
    const names = [
        'black',
        'dark_blue',
        'dark_green',
        'dark_aqua',
        'dark_red',
        'dark_purple',
        'gold',
        'gray',
        'dark_gray',
        'blue',
        'green',
        'aqua',
        'red',
        'light_purple',
        'yellow',
        'white'
    ];
    [...'0123456789abcdef'].forEach((code, index) =>
        assert.deepEqual(parseMinecraftText(`§${code}x`), [{ ...plain, color: names[index], text: 'x' }])
    );
});

test('unknown codes, a trailing section sign and markup remain literal text', async () => {
    const { parseMinecraftText, plainMinecraftText } = await text;
    assert.deepEqual(parseMinecraftText('Plain §qUnknown tail§'), [{ ...plain, text: 'Plain §qUnknown tail§' }]);
    const literal = '<img src=x onerror=alert(1)> & <script>alert(2)</script>';
    assert.deepEqual(parseMinecraftText(`§a${literal}`), [{ ...plain, color: 'green', text: literal }]);
    assert.deepEqual(parseMinecraftText(''), []);
    assert.equal(plainMinecraftText('§kSecret §aWords §rPlain §q'), 'Secret Words Plain §q');
});
