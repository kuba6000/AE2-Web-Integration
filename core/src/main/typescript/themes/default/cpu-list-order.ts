import type { ThemeState } from '../../app/theme-api.js';
import { plainMinecraftText } from '../../app/minecraft-text.js';
import { selectionModes } from './cpu-presentation.js';
import { terminalIcons, craftingHammer } from './icons/pixel/terminal.js';
import { cpuChipIcon } from './icons/pixel/cpu-controls.js';

export const cpuSortCriteria = [
    { value: 'name', label: 'name', icon: terminalIcons.name },
    { value: 'capacity', label: 'cpuCapacityLabel', icon: terminalIcons.stored },
    { value: 'coprocessors', label: 'cpuCoprocessorsLabel', icon: cpuChipIcon },
    { value: 'state', label: 'cpuBusyState', icon: craftingHammer },
    { value: 'automation', label: 'cpuAutomation', icon: terminalIcons.all }
] as const;

export function orderedCpus(
    cpus: ThemeState['cpus']['cpus'],
    criterion: (typeof cpuSortCriteria)[number]['value'],
    descending: boolean,
    query: string
) {
    const language = document.documentElement.lang;
    const terms = query.trim().toLocaleLowerCase(language).split(/\s+/);
    return cpus
        .filter((cpu) => {
            const text = `${plainMinecraftText(cpu.name)} ${cpu.key}`.toLocaleLowerCase(language);
            return terms.every((term) => text.includes(term));
        })
        .sort((a, b) => {
            const name = (plainMinecraftText(a.name).trim() || a.key).localeCompare(
                plainMinecraftText(b.name).trim() || b.key,
                language
            );
            const aMode = a.selectionMode ? selectionModes[a.selectionMode].order : null;
            const bMode = b.selectionMode ? selectionModes[b.selectionMode].order : null;
            if (criterion === 'automation' && (aMode === null) !== (bMode === null)) return aMode === null ? 1 : -1;
            let primary = name;
            switch (criterion) {
                case 'capacity':
                    primary = a.availableStorage - b.availableStorage;
                    break;
                case 'coprocessors':
                    primary = a.coProcessors - b.coProcessors;
                    break;
                case 'state':
                    primary = Number(a.isBusy) - Number(b.isBusy);
                    break;
                case 'automation':
                    primary = (aMode ?? 0) - (bMode ?? 0);
                    break;
            }
            return (descending ? -primary : primary) || name || (a.key < b.key ? -1 : a.key > b.key ? 1 : 0);
        });
}
