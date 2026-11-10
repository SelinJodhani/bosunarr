import { getService, type ServiceRecord } from '../services/store';
import { ServiceError } from '../services/client';
import type { ServiceTypeId } from '$lib/services/catalog';

/**
 * Resolve a service id from a route param and check its type.
 * Every per-service API route (`/api/svc/<type>/[id]/...`) starts with this.
 */
export function serviceOf(id: string | number | undefined, ...types: ServiceTypeId[]): ServiceRecord {
	const n = Number(id);
	const s = Number.isInteger(n) && n > 0 ? getService(n) : undefined;
	if (!s || (types.length && !types.includes(s.type))) throw new ServiceError('Service not found', 404);
	return s;
}
