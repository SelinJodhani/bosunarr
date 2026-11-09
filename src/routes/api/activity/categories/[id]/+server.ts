import type { RequestHandler } from './$types';
import { handleApi } from '$lib/server/guard';
import { categories } from '$lib/server/activity';

export const GET: RequestHandler = ({ params }) => handleApi(() => categories(Number(params.id)));
