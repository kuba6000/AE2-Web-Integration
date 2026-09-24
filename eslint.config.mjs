import js from '@eslint/js';
import globals from 'globals';

export default [
    {
        files: ['src/main/resources/assets/web/**/*.mjs'],
        languageOptions: { globals: globals.browser },
        rules: js.configs.recommended.rules
    },
    {
        files: ['*.config.mjs'],
        languageOptions: { globals: globals.node },
        rules: js.configs.recommended.rules
    },
    {
        files: ['tools/ui-tests/**/*.cjs'],
        // Playwright callbacks execute in the browser; the HTTP fixture executes in Node.
        languageOptions: { globals: { ...globals.node, ...globals.browser } },
        rules: js.configs.recommended.rules
    }
];
