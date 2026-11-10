import type { PublicService } from '$lib/services/catalog';

/**
 * Contract for per-service screens.
 *
 * Add `src/lib/servicepages/<type>/Page.svelte` (folder name = the service type id from
 * `src/lib/services/catalog.ts`, e.g. `prowlarr`, `qbittorrent`). It is discovered automatically by
 * `/services/[id]` — no registry to edit. Several types can share a page by re-exporting it
 * (e.g. `radarr/Page.svelte` importing `../arr/ArrPage.svelte`).
 *
 * The page receives these props. Fetch its data from your own API routes under
 * `src/routes/api/svc/<type>/[id]/...` (use `serviceOf()` from `$lib/server/svc/common`), and
 * guard write actions with `requireAdmin`.
 */
export type ServicePageProps = {
	service: PublicService;
	isAdmin: boolean;
};
