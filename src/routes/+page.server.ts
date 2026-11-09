import type { PageServerLoad } from './$types';
import { db } from '$lib/server/db';
import { listServices } from '$lib/server/services/store';
import { WIDGETS, type WidgetInstance, type WidgetKind } from '$lib/widgets/types';

/** First-run layout: every widget whose services exist, in a sensible order. */
function defaultLayout(): WidgetInstance[] {
	const types = new Set(listServices().map((s) => s.type));
	const order: WidgetKind[] = ['nowplaying', 'downloads', 'queue', 'requests', 'upcoming', 'disk', 'health'];
	return order
		.filter((k) => WIDGETS[k].needs.length === 0 || WIDGETS[k].needs.some((t) => types.has(t)))
		.map((kind) => ({ id: kind, kind, size: WIDGETS[kind].defaultSize }));
}

export const load: PageServerLoad = ({ locals }) => {
	const row = db.prepare('SELECT layout FROM dashboards WHERE user_id = ?').get(locals.user!.id) as { layout: string } | undefined;
	const widgets: WidgetInstance[] = row ? JSON.parse(row.layout).widgets : defaultLayout();
	return { widgets, customised: Boolean(row) };
};
