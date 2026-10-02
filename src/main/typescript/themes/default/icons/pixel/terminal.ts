// Original interface artwork. SPDX-License-Identifier: LGPL-3.0-or-later.

function icon(body: string) {
    return `<svg class="theme-icon" xmlns="http://www.w3.org/2000/svg" width="32" height="32" viewBox="0 0 32 32" shape-rendering="crispEdges" aria-hidden="true" focusable="false">${body}</svg>`;
}

export const craftingHammer = icon(`<g transform="scale(2)">
<path fill="#302b28" d="M9 6h2v3h-1v1H9v1H8v1H7v1H6v1H5v1H3v-1H2v-2h1v-1h1v-1h1V9h1V8h1V7h2z"/>
<path fill="#a16b3b" d="M9 8h1v1H9v1H8v1H7v1H6v1H5v1H3v-1h1v-1h1v-1h1v-1h1V9h1V8z"/>
<path fill="#754b2c" d="M3 13h2v-1h1v-1h1v-1h1V9h1V8h1v1H9v1H8v1H7v1H6v1H5v1H3z"/>
<path fill="#a16b3b" d="M8 7h1v1h-1zM7 8h1v1h-1zM6 9h1v1h-1zM5 10h1v1h-1zM4 11h1v1h-1zM3 12h1v1h-1zM3 13h1v1H3z"/>
<path fill="#302b28" d="M7 1h2v1h1v1h1v1h1v1h1v1h1v1h1v2h-1v1h-1v1h-2v-1h-1V9H9V8H8V7H7V6H6V5H5V3h1V2h1z"/>
<path fill="#a7b3b7" d="M7 2h2v1h1v1h1v1h1v1h1v1h1v2h-1v1h-2V9h-1V8H9V7H8V6H7V5H6V3h1z"/>
<path fill="#d2dcde" d="M7 2h2v1H8v1H7v1H6V3h1z"/>
<path fill="#74858d" d="M13 7h1v2h-1v1h-2V9h1V8h1z"/>
</g>`);
export const craftingQueue = icon(`<g transform="scale(2)">
    <path fill="#302b28" d="M3 1h10v3h-1v2h-1v1h-1v2h1v1h1v2h1v3H3v-3h1v-2h1V9h1V7H5V6H4V4H3z"/>
    <path fill="#a16b3b" d="M4 2h8v1H4zM4 13h8v1H4z"/>
    <path fill="#a7b3b7" d="M5 4h6v2h-1v1H9v2h1v1h1v2H5v-2h1V9h1V7H6V6H5z"/>
    <path fill="#d2dcde" d="M5 4h1v2H5zM5 10h1v2H5z"/>
    <path fill="#dcb85c" d="M6 5h4v1H9v1H7V6H6zM7 9h2v1h1v1h1v1H5v-1h1v-1h1z"/>
  </g>`);
export const craftingPriorityIcon = icon(`<g transform="scale(2)">
    <path fill="#302b28" d="M1 2h7v4H1zM1 7h7v4H1zM1 12h7v3H1zM11 2h1v1h1v1h1v1h1v3h-2v6h-3V8H8V5h1V4h1V3h1z"/>
    <path fill="#86b967" d="M2 3h5v2H2z"/>
    <path fill="#568340" d="M6 4h1v1H6z"/>
    <path fill="#dcb85c" d="M2 8h5v2H2z"/>
    <path fill="#a7833d" d="M6 9h1v1H6z"/>
    <path fill="#a7b3b7" d="M2 13h5v1H2z"/>
    <path fill="#d2dcde" d="M11 4h1v1h1v1h1v1h-2v6h-1V7H9V6h1V5h1z"/>
  </g>`);
const allIcon = icon(`<g transform="scale(2)">
    <path fill="#302b28" d="M2 2h5v5H2zM9 2h5v5H9zM2 9h5v5H2zM9 9h5v5H9z"/>
    <path fill="#d2dcde" d="M3 3h3v3H3z"/>
    <path fill="#74858d" d="M5 4h1v2H3V5h2z"/>
    <path fill="#dcb85c" d="M10 3h3v3h-3z"/>
    <path fill="#a7833d" d="M12 4h1v2h-3V5h2z"/>
    <path fill="#86b967" d="M3 10h3v3H3z"/>
    <path fill="#568340" d="M5 11h1v2H3v-1h2z"/>
    <path fill="#78a9c9" d="M10 10h3v3h-3z"/>
    <path fill="#507a9b" d="M12 11h1v2h-3v-1h2z"/>
  </g>`);
const storedIcon = icon(`<g transform="scale(2)">
    <path fill="#302b28" d="M3 3h10v1h1v9h-1v1H3v-1H2V4h1z"/>
    <path fill="#a16b3b" d="M3 4h10v9H3z"/>
    <path fill="#754b2c" d="M12 5h1v8H3v-2h9z"/>
    <path fill="#dcb85c" d="M3 4h10v2H3z"/>
    <path fill="#302b28" d="M3 7h10v1H3zM6 6h4v4H6z"/>
    <path fill="#d2dcde" d="M7 7h2v2H7z"/>
  </g>`);
const nameIcon = icon(`<g transform="scale(2)">
    <path fill="#302b28" d="M3 1h3v1h2v7H1V2h2zM8 8h7v3h-1v1h1v3H8v-3h1v-1H8z"/>
    <path fill="#d2dcde" d="M3 2h3v1H3zM2 3h1v2h3V3h1v5H6V6H3v2H2z"/>
    <path fill="#78a9c9" d="M9 9h5v1h-1v1h-1v1h-1v1h3v1H9v-1h1v-1h1v-1h1v-1H9z"/>
  </g>`);
const quantityIcon = icon(`<g transform="scale(2)">
    <path fill="#302b28" d="M1 10h4v5H1zM6 6h4v9H6zM11 2h4v13h-4z"/>
    <path fill="#dcb85c" d="M2 11h2v3H2zM7 7h2v7H7zM12 3h2v11h-2z"/>
    <path fill="#a7833d" d="M3 12h1v2H3zM8 8h1v6H8zM13 4h1v10h-1z"/>
  </g>`);
const idIcon = icon(`<g transform="scale(2)">
    <path fill="#302b28" d="M2 3h9v1h1v1h1v1h1v1h1v2h-1v1h-1v1h-1v1h-1v1H2v-1H1V4h1z"/>
    <path fill="#78a9c9" d="M2 4h9v1h1v1h1v1h1v2h-1v1h-1v1h-1v1H2z"/>
    <path fill="#507a9b" d="M11 10h2v1h-2v1H2v-1h9z"/>
    <path fill="#302b28" d="M4 5h1v1h2V5h1v1h1v1H8v2h1v1H8v1H7v-1H5v1H4v-1H3V9h1V7H3V6h1zM11 7h2v2h-2z"/>
    <path fill="#78a9c9" d="M5 7h2v2H5z"/>
  </g>`);

export const terminalIcons = {
    all: allIcon,
    stored: storedIcon,
    craftable: craftingHammer,
    name: nameIcon,
    quantity: quantityIcon,
    id: idIcon
};
