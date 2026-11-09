import type { RequestHandler } from './$types';
import { db } from '$lib/server/db';
import { handleApi } from '$lib/server/guard';
import { WIDGETS, type WidgetInstance } from '$lib/widgets/types';

export const PUT: RequestHandler = async ({ locals, request }) => {
	const body = (await request.json()) as { widgets: WidgetInstance[] };
	return handleApi(() => {
		const widgets = (body.widgets ?? [])
			.filter((w) => w && w.kind in WIDGETS && ['s', 'm', 'l'].includes(w.size))
			.slice(0, 40)
			.map((w) => ({ id: String(w.id).slice(0, 40), kind: w.kind, size: w.size }));
		db.prepare('INSERT INTO dashboards (user_id, layout) VALUES (?, ?) ON CONFLICT(user_id) DO UPDATE SET layout = excluded.layout').run(
			locals.user!.id,
			JSON.stringify({ widgets })
		);
		return { ok: true };
	});
};
