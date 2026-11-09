type Fraction = { numerator: bigint; denominator: bigint };

/** Evaluate bounded quantity expressions exactly; HTTP still receives a safe integer. */
export function parseCraftingQuantity(input: string): number | null {
    if (!input.trim() || input.length > 256) return null;
    const tokens = input.match(/(?:\d+(?:[.,]\d*)?|[.,]\d+)[kmbgt]?|[()+*/-]|\S/gi) || [];
    let index = 0;
    const fraction = (numerator: bigint, denominator = 1n): Fraction => {
        if (!denominator) throw new Error('Division by zero');
        let a = numerator < 0n ? -numerator : numerator;
        let b = denominator < 0n ? -denominator : denominator;
        while (b) [a, b] = [b, a % b];
        const divisor = (a || 1n) * (denominator < 0n ? -1n : 1n);
        return { numerator: numerator / divisor, denominator: denominator / divisor };
    };
    function primary(): Fraction {
        const token = tokens[index++];
        if (token === '+' || token === '-') {
            const value = primary();
            return fraction(token === '-' ? -value.numerator : value.numerator, value.denominator);
        }
        if (token === '(') {
            const value = expression();
            if (tokens[index++] !== ')') throw new Error('Unclosed expression');
            return value;
        }
        const match = /^(\d+(?:[.,]\d*)?|[.,]\d+)([kmbgt]?)$/i.exec(token || '');
        if (!match) throw new Error('Invalid quantity');
        const [whole, decimal = ''] = match[1].replace(',', '.').split('.');
        const powers: Record<string, bigint> = {
            '': 1n,
            k: 1000n,
            m: 1000000n,
            b: 1000000000n,
            g: 1000000000n,
            t: 1000000000000n
        };
        return fraction(
            BigInt((whole || '0') + decimal) * powers[match[2].toLowerCase()],
            10n ** BigInt(decimal.length)
        );
    }
    function product(): Fraction {
        let value = primary();
        while (tokens[index] === '*' || tokens[index] === '/') {
            const operator = tokens[index++];
            const next = primary();
            value =
                operator === '*'
                    ? fraction(value.numerator * next.numerator, value.denominator * next.denominator)
                    : fraction(value.numerator * next.denominator, value.denominator * next.numerator);
        }
        return value;
    }
    function expression(): Fraction {
        let value = product();
        while (tokens[index] === '+' || tokens[index] === '-') {
            const operator = tokens[index++];
            const next = product();
            value = fraction(
                value.numerator * next.denominator + (operator === '+' ? 1n : -1n) * next.numerator * value.denominator,
                value.denominator * next.denominator
            );
        }
        return value;
    }
    try {
        const value = expression();
        return index === tokens.length &&
            value.denominator === 1n &&
            value.numerator > 0n &&
            value.numerator <= BigInt(Number.MAX_SAFE_INTEGER)
            ? Number(value.numerator)
            : null;
    } catch {
        return null;
    }
}
