import type { PageServerLoad } from './$types';
import { db } from '$lib/server/db';
import { listServices } from '$lib/server/services/store';
import { HEIGHTS_COOKIE, parseHeights } from '$lib/widgets/heights';
import { WIDGETS, type WidgetInstance, type WidgetKind } from '$lib/widgets/types';

/** First-run layout: every widget whose services exist, in a sensible order. */
function defaultLayout(): WidgetInstance[] {
	const types = new Set(listServices().map((s) => s.type));
	// Pairs of medium widgets, the wide Upcoming rail, then Health + Disk: rows fill evenly on wide screens.
	const order: WidgetKind[] = ['nowplaying', 'requests', 'downloads', 'queue', 'upcoming', 'health', 'disk'];
	return order
		.filter((k) => WIDGETS[k].needs.length === 0 || WIDGETS[k].needs.some((t) => types.has(t)))
		.map((kind) => ({ id: kind, kind, size: WIDGETS[kind].defaultSize }));
}

export const load: PageServerLoad = ({ locals, cookies }) => {
	const row = db.prepare('SELECT layout FROM dashboards WHERE user_id = ?').get(locals.user!.id) as { layout: string } | undefined;
	const widgets: WidgetInstance[] = row ? JSON.parse(row.layout).widgets : defaultLayout();
	// Last rendered widget heights, so loading skeletons match and the page doesn't jump when data paints.
	return { widgets, customised: Boolean(row), heights: parseHeights(cookies.get(HEIGHTS_COOKIE)) };
};
