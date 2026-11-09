const colors = {
    0: 'black',
    1: 'dark_blue',
    2: 'dark_green',
    3: 'dark_aqua',
    4: 'dark_red',
    5: 'dark_purple',
    6: 'gold',
    7: 'gray',
    8: 'dark_gray',
    9: 'blue',
    a: 'green',
    b: 'aqua',
    c: 'red',
    d: 'light_purple',
    e: 'yellow',
    f: 'white'
} as const;

export type TextStyle = {
    color: (typeof colors)[keyof typeof colors] | null;
    bold: boolean;
    italic: boolean;
    underline: boolean;
    strikethrough: boolean;
    obfuscated: boolean;
};
export type TextSegment = TextStyle & { text: string };
const defaults: TextStyle = {
    color: null,
    bold: false,
    italic: false,
    underline: false,
    strikethrough: false,
    obfuscated: false
};

/**
 * Decode classic Minecraft section-sign codes without choosing a rendering format.
 * Unknown codes and a trailing section sign remain literal text.
 */
export function parseMinecraftText(text: string): TextSegment[] {
    const segments: TextSegment[] = [];
    let style = { ...defaults };
    let content = '';
    function flush() {
        if (content) segments.push({ ...style, text: content });
        content = '';
    }
    for (let index = 0; index < text.length; index++) {
        const code = text[index] === '§' ? text[index + 1]?.toLowerCase() : undefined;
        if (!code || !'0123456789abcdefklmnor'.includes(code)) {
            content += text[index];
            continue;
        }
        flush();
        index++;
        if (Object.hasOwn(colors, code)) {
            // A color code clears decorations as well as changing the color in Java Edition.
            style = { ...defaults, color: colors[code as keyof typeof colors] };
        } else if (code === 'r') style = { ...defaults };
        else if (code === 'k') style.obfuscated = true;
        else if (code === 'l') style.bold = true;
        else if (code === 'm') style.strikethrough = true;
        else if (code === 'n') style.underline = true;
        else if (code === 'o') style.italic = true;
    }
    flush();
    return segments;
}

/** Readable text for search, sorting and controls without rich text support. */
export function plainMinecraftText(text: string) {
    return parseMinecraftText(text)
        .map((segment) => segment.text)
        .join('');
}
