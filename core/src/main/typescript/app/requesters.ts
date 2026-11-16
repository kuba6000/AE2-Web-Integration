import type { CraftingRequester } from './api-types.js';
import type { MessageValues } from './i18n.js';

/** How a crafting requester is named in CPU views and crafting history; names are data, never markup. */
export function requesterLabel(requester: CraftingRequester, t: (key: string, values?: MessageValues) => string) {
    switch (requester.source) {
        case 'PLAYER':
            return requester.player.name;
        case 'STANDING_ORDER':
            return t('craftingRequesterStandingOrder', { name: requester.orderName });
        case 'WEB':
            return t('craftingRequesterWeb');
        case 'AUTOMATION':
            return t('craftingRequesterAutomation');
    }
}
