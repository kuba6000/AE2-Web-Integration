import type { ResourceStack } from '../../app/api-types.js';

export function registryId(resource: Pick<ResourceStack, 'registryNamespace' | 'registryPath'>): string {
    return resource.registryNamespace !== null && resource.registryPath !== null
        ? `${resource.registryNamespace}:${resource.registryPath}`
        : '';
}
