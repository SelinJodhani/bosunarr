import type { RequestHandler } from './$types';
import { handleApi } from '$lib/server/guard';
import { activity } from '$lib/server/activity';

export const GET: RequestHandler = () => handleApi(() => activity());
