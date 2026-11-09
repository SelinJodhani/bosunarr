import type { RequestHandler } from './$types';
import { handleApi } from '$lib/server/guard';
import { library } from '$lib/server/library';

export const GET: RequestHandler = () => handleApi(() => library());
