import type { RequestHandler } from './$types';
import { handleApi } from '$lib/server/guard';
import { serviceOf } from '$lib/server/svc/common';
import { nowPlaying } from '$lib/server/svc/jellystat';

export const GET: RequestHandler = ({ params }) => handleApi(() => nowPlaying(serviceOf(params.id, 'jellystat')));
