export default {
    extends: ['stylelint-config-recommended'],
    rules: {
        // Independent views may target the same element types without sharing a cascade.
        'no-descending-specificity': null
    }
};
