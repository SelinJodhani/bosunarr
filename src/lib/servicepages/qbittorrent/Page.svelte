<script lang="ts">
	import { Button, Chip, Icon, LoadingIndicator, Switch, snackbar } from 'm3-svelte';
	import iDown from '@ktibow/iconset-material-symbols/arrow-downward-rounded';
	import iUp from '@ktibow/iconset-material-symbols/arrow-upward-rounded';
	import iAdd from '@ktibow/iconset-material-symbols/add-rounded';
	import iPause from '@ktibow/iconset-material-symbols/pause-rounded';
	import iResume from '@ktibow/iconset-material-symbols/play-arrow-rounded';
	import iConnected from '@ktibow/iconset-material-symbols/wifi-rounded';
	import iDisconnected from '@ktibow/iconset-material-symbols/wifi-off-rounded';
	import iFirewalled from '@ktibow/iconset-material-symbols/warning-rounded';
	import iAlt from '@ktibow/iconset-material-symbols/slow-motion-video-rounded';
	import iCategory from '@ktibow/iconset-material-symbols/category-rounded';
	import iTag from '@ktibow/iconset-material-symbols/label-rounded';
	import { bytes, speed } from '$lib/format';
	import { live } from '$lib/live.svelte';
	import { act, liveActivity } from '$lib/activity/live.svelte';
	import TorrentList from '$lib/activity/TorrentList.svelte';
	import AddTorrent from '$lib/activity/AddTorrent.svelte';
	import type { ServicePageProps } from '../types';
	import type { QbitConnection, QbitOverview } from './types';

	let { service, isAdmin }: ServicePageProps = $props();

	// Instance details (server state, alt speed, categories, tags, prefs) poll here;
	// the torrent list comes from Activity's shared live stream, filtered to this instance.
	// The page is re-created per service ({#key} in the route), so reading service.id once is fine.
	// svelte-ignore state_referenced_locally
	const ov = live<QbitOverview>(`/api/svc/qbittorrent/${service.id}`, 2_000);
	const feed = liveActivity();

	const o = $derived(ov.state.data);
	const t = $derived(o?.transfer);
	const snap = $derived(feed.state.data);
	const client = $derived(snap?.clients.find((c) => c.id === service.id));
	const torrents = $derived(snap?.torrents.filter((x) => x.serviceId === service.id) ?? []);

	/* ---------------- Category / tag filters ---------------- */
	const NONE = '\u0000none';
	let category = $state<string | null>(null);
	let tag = $state<string | null>(null);

	function countBy(keys: (x: (typeof torrents)[number]) => string[]) {
		const m = new Map<string, number>();
		for (const x of torrents) for (const k of keys(x)) m.set(k, (m.get(k) ?? 0) + 1);
		return m;
	}
	const catCounts = $derived(countBy((x) => [x.category || NONE]));
	const tagCounts = $derived(countBy((x) => (x.tags.length ? x.tags : [NONE])));
	const union = (a: string[] = [], m: Map<string, number>, selected: string | null) =>
		[...new Set([...a, ...[...m.keys()].filter((k) => k !== NONE), ...(selected && selected !== NONE ? [selected] : [])])].sort((x, y) => x.localeCompare(y));
	const catList = $derived(union(o?.categories, catCounts, category));
	const tagList = $derived(union(o?.tags, tagCounts, tag));

	const shown = $derived(
		torrents.filter(
			(x) =>
				(category === null || (category === NONE ? !x.category : x.category === category)) &&
				(tag === null || (tag === NONE ? x.tags.length === 0 : x.tags.includes(tag)))
		)
	);

	/* ---------------- Alternative speed ---------------- */
	let altPending = $state<boolean | null>(null);
	const altOn = $derived(altPending ?? t?.altSpeed ?? false);

	async function setAlt(enabled: boolean) {
		if (!isAdmin || altPending !== null) return;
		altPending = enabled;
		const res = await fetch(`/api/svc/qbittorrent/${service.id}/altspeed`, {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ enabled })
		}).catch(() => null);
		const err = !res ? 'Network error' : res.ok ? null : ((await res.json().catch(() => ({})))?.error ?? `HTTP ${res.status}`);
		await ov.refresh();
		altPending = null;
		snackbar(err ?? (enabled ? 'Alternative speed limits on' : 'Alternative speed limits off'), {}, true);
	}

	/* ---------------- Bulk actions ---------------- */
	let addOpen = $state(false);
	let busy = $state(false);
	async function all(action: 'pauseAll' | 'resumeAll') {
		busy = true;
		const err = await act({ action, ids: [`${service.id}:all`] });
		busy = false;
		snackbar(err ?? (action === 'pauseAll' ? 'Paused all torrents' : 'Resumed all torrents'), {}, true);
	}

	/* ---------------- Formatting ---------------- */
	const limit = (n: number) => (n > 0 ? speed(n) : 'Unlimited');
	const CONN: Record<QbitConnection, { label: string; icon: typeof iConnected }> = {
		connected: { label: 'Connected', icon: iConnected },
		firewalled: { label: 'Firewalled', icon: iFirewalled },
		disconnected: { label: 'Disconnected', icon: iDisconnected },
		unknown: { label: 'Unknown', icon: iDisconnected }
	};
	const cap = (n: number) => (n < 0 ? 'no limit' : String(n));
	function minutes(m: number) {
		if (m < 60) return `${m} min`;
		const h = Math.floor(m / 60);
		return h >= 48 && m % 1440 === 0 ? `${m / 1440} days` : `${h} h${m % 60 ? ` ${m % 60} min` : ''}`;
	}
</script>

{#if !o}
	{#if ov.state.error}
		<p class="error" role="alert">{ov.state.error}</p>
	{:else}
		<div class="center"><LoadingIndicator aria-label="Loading qBittorrent" /></div>
	{/if}
{:else if t}
	{@const conn = CONN[t.connection]}
	<div class="page">
		{#if ov.state.error}<p class="error" role="alert">Showing the last data: {ov.state.error}</p>{/if}

		<div class="top">
			<!-- Transfer -->
			<section class="card transfer" aria-label="Transfer">
				<div class="speeds">
					<div class="speed">
						<span class="lbl-m"><Icon icon={iDown} size={16} /> Download</span>
						<strong class="big">{speed(t.down)}</strong>
						<span class="body-small sub">Limit {limit(t.downLimit)}</span>
					</div>
					<div class="speed">
						<span class="lbl-m"><Icon icon={iUp} size={16} /> Upload</span>
						<strong class="big">{speed(t.up)}</strong>
						<span class="body-small sub">Limit {limit(t.upLimit)}</span>
					</div>
					<span class="conn c-{t.connection}" title="Connection status"><Icon icon={conn.icon} size={16} />{conn.label}</span>
				</div>
				<dl class="stats">
					<div><dt>Session</dt><dd>↓ {bytes(t.sessionDown)} · ↑ {bytes(t.sessionUp)}</dd></div>
					<div><dt>All-time</dt><dd>↓ {bytes(t.allTimeDown)} · ↑ {bytes(t.allTimeUp)}</dd></div>
					<div><dt>Global ratio</dt><dd>{t.ratio === null ? '—' : t.ratio.toFixed(2)}</dd></div>
					<div><dt>Free space</dt><dd>{t.freeSpace === null ? '—' : bytes(t.freeSpace)}</dd></div>
					<div><dt>DHT nodes</dt><dd>{t.dhtNodes.toLocaleString()}</dd></div>
					<div><dt>Peers</dt><dd>{t.peers.toLocaleString()}</dd></div>
				</dl>
			</section>

			<!-- Alternative speed limits -->
			<section class="card alt" class:on={altOn} aria-label="Speed limits">
				<div class="alt-head">
					<span class="alt-icon"><Icon icon={iAlt} size={24} /></span>
					<div class="alt-text">
						<h2 class="h-small">Alternative speed limits</h2>
						<p class="body-small">
							{#if altPending !== null}Switching…{:else if altOn}On — {o.prefs ? `↓ ${limit(o.prefs.altDownLimit)} · ↑ ${limit(o.prefs.altUpLimit)}` : 'reduced limits'}{:else}Off — normal limits{/if}
						</p>
					</div>
					<!-- The label forwards clicks on the switch's track to its input. -->
					<label class="sw">
						<Switch
							aria-label="Alternative speed limits"
							disabled={!isAdmin || altPending !== null}
							bind:checked={() => altOn, (v) => setAlt(v)}
						/>
					</label>
				</div>
				{#if o.prefs}
					<table class="limits">
						<thead><tr><th scope="col"></th><th scope="col">Download</th><th scope="col">Upload</th></tr></thead>
						<tbody>
							<tr class:active={!altOn}><th scope="row">Normal</th><td>{limit(o.prefs.downLimit)}</td><td>{limit(o.prefs.upLimit)}</td></tr>
							<tr class:active={altOn}><th scope="row">Alternative</th><td>{limit(o.prefs.altDownLimit)}</td><td>{limit(o.prefs.altUpLimit)}</td></tr>
						</tbody>
					</table>
					{#if o.prefs.altScheduled}<p class="body-small note">A schedule in qBittorrent also switches these automatically.</p>{/if}
				{/if}
				{#if !isAdmin}<p class="body-small note">Only admins can change this.</p>{/if}
			</section>
		</div>

		<div class="body">
			<!-- Torrents -->
			<section class="torrents" aria-label="Torrents">
				<h2 class="title-medium">Torrents{client ? ` (${torrents.length})` : ''}</h2>
				{#if !snap}
					<div class="center"><LoadingIndicator aria-label="Loading torrents" /></div>
				{:else if !client}
					<p class="empty">This instance isn't in the activity feed yet.</p>
				{:else if client.error}
					<p class="error" role="alert">{client.error}</p>
				{:else}
					{#if isAdmin}
						<div class="bulk">
							<Button variant="filled" onclick={() => (addOpen = true)}><Icon icon={iAdd} /> Add</Button>
							<Button variant="tonal" disabled={busy} aria-label="Resume all" onclick={() => all('resumeAll')}><Icon icon={iResume} /><span class="lbl">Resume<span class="all">&nbsp;all</span></span></Button>
							<Button variant="tonal" disabled={busy} aria-label="Pause all" onclick={() => all('pauseAll')}><Icon icon={iPause} /><span class="lbl">Pause<span class="all">&nbsp;all</span></span></Button>
						</div>
					{/if}

					{#if catList.length}
						<div class="chips" role="toolbar" aria-label="Filter by category">
							<span class="chip-label" title="Category"><Icon icon={iCategory} size={18} /></span>
							<Chip variant="general" selected={category === null} onclick={() => (category = null)}>All categories</Chip>
							{#each catList as c (c)}
								<Chip variant="general" selected={category === c} onclick={() => (category = category === c ? null : c)}>{c} {catCounts.get(c) ?? 0}</Chip>
							{/each}
							{#if catCounts.get(NONE) || category === NONE}
								<Chip variant="general" selected={category === NONE} onclick={() => (category = category === NONE ? null : NONE)}>Uncategorized {catCounts.get(NONE) ?? 0}</Chip>
							{/if}
						</div>
					{/if}
					{#if tagList.length}
						<div class="chips" role="toolbar" aria-label="Filter by tag">
							<span class="chip-label" title="Tag"><Icon icon={iTag} size={18} /></span>
							<Chip variant="general" selected={tag === null} onclick={() => (tag = null)}>All tags</Chip>
							{#each tagList as g (g)}
								<Chip variant="general" selected={tag === g} onclick={() => (tag = tag === g ? null : g)}>{g} {tagCounts.get(g) ?? 0}</Chip>
							{/each}
							{#if tagCounts.get(NONE) || tag === NONE}
								<Chip variant="general" selected={tag === NONE} onclick={() => (tag = tag === NONE ? null : NONE)}>Untagged {tagCounts.get(NONE) ?? 0}</Chip>
							{/if}
						</div>
					{/if}

					<TorrentList torrents={shown} {isAdmin} showClient={() => service.name} />
				{/if}
			</section>

			<!-- About + preferences -->
			<aside class="card about" aria-label="About and preferences">
				<h2 class="h-small">About</h2>
				<dl class="kv">
					<dt>Version</dt><dd>{o.version ?? '—'}</dd>
					<dt>Web API</dt><dd>{o.webApiVersion ?? '—'}</dd>
				</dl>
				{#if o.prefs}
					{@const p = o.prefs}
					{#if p.savePath !== null}
						<h2 class="h-small">Downloads</h2>
						<dl class="kv">
							<dt>Save path</dt><dd class="path">{p.savePath || '—'}</dd>
							{#if p.tempPath}<dt>Incomplete</dt><dd class="path">{p.tempPath}</dd>{/if}
						</dl>
					{/if}
					<h2 class="h-small">Queueing</h2>
					<dl class="kv">
						{#if p.queueing}
							<dt>Downloads</dt><dd>{cap(p.maxActiveDownloads)}</dd>
							<dt>Uploads</dt><dd>{cap(p.maxActiveUploads)}</dd>
							<dt>Torrents</dt><dd>{cap(p.maxActiveTorrents)}</dd>
						{:else}
							<dt>Status</dt><dd>Off</dd>
						{/if}
					</dl>
					<h2 class="h-small">Seeding limits</h2>
					<dl class="kv">
						<dt>Max ratio</dt><dd>{p.maxRatio === null ? 'No limit' : p.maxRatio.toFixed(2)}</dd>
						<dt>Max time</dt><dd>{p.maxSeedingTime === null ? 'No limit' : minutes(p.maxSeedingTime)}</dd>
						{#if p.maxRatio !== null || p.maxSeedingTime !== null}<dt>Then</dt><dd>{p.maxRatioAction}</dd>{/if}
					</dl>
					<h2 class="h-small">Network</h2>
					<dl class="kv">
						<dt>DHT</dt><dd>{p.dht ? 'On' : 'Off'}</dd>
						{#if p.listenPort !== null}<dt>Listen port</dt><dd>{p.listenPort}</dd>{/if}
					</dl>
					<p class="body-small note">Read-only. Change these in qBittorrent's own settings.</p>
				{:else}
					<p class="body-small note">Couldn't read preferences.</p>
				{/if}
			</aside>
		</div>
	</div>

	{#if client && !client.error}<AddTorrent bind:open={addOpen} clients={[client]} />{/if}
{:else}
	<p class="error" role="alert">{ov.state.error ?? 'qBittorrent sent no transfer data. Try reloading.'}</p>
{/if}

<style>
	.page {
		display: grid;
		gap: 1rem;
	}
	.top,
	.body {
		display: grid;
		gap: 1rem;
		grid-template-columns: minmax(0, 1fr);
		align-items: start;
	}
	@media (width >= 56rem) {
		.top {
			grid-template-columns: minmax(0, 3fr) minmax(0, 2fr);
			align-items: stretch;
		}
	}
	@media (width >= 72rem) {
		.body {
			grid-template-columns: minmax(0, 1fr) minmax(0, 20rem);
		}
	}
	.card {
		padding: 1rem 1.25rem;
		border-radius: var(--radius-l);
		background: var(--m3c-surface-container-low);
		min-width: 0;
	}
	h2 {
		margin: 0;
	}
	.h-small {
		font-size: 0.875rem;
		line-height: 1.25rem;
		font-weight: 500;
	}
	.lbl-m {
		font-size: 0.75rem;
		line-height: 1rem;
		font-weight: 500;
		letter-spacing: 0.03em;
	}
	.center {
		display: grid;
		place-items: center;
		padding: 3rem 0;
	}
	.error {
		color: var(--m3c-error);
		font-size: 0.875rem;
		margin: 0;
	}
	.empty,
	.note {
		color: var(--m3c-on-surface-variant);
	}
	.note {
		margin: 0.75rem 0 0;
	}

	/* Transfer */
	.speeds {
		display: grid;
		grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
		gap: 0.25rem 1rem;
	}
	.speed {
		display: grid;
		gap: 0.125rem;
		min-width: 0;
	}
	.speed .lbl-m {
		display: inline-flex;
		align-items: center;
		gap: 0.25rem;
		color: var(--m3c-on-surface-variant);
	}
	.big {
		font-size: 1.75rem;
		line-height: 2.25rem;
		font-weight: 500;
		font-variant-numeric: tabular-nums;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.sub {
		color: var(--m3c-on-surface-variant);
	}
	.conn {
		grid-column: 1 / -1;
		justify-self: start;
		margin-top: 0.5rem;
		display: inline-flex;
		align-items: center;
		gap: 0.375rem;
		padding: 0.25rem 0.75rem;
		border-radius: 1rem;
		font-size: 0.75rem;
		font-weight: 500;
		background: var(--m3c-secondary-container);
		color: var(--m3c-on-secondary-container);
	}
	.c-firewalled {
		background: var(--m3c-tertiary-container);
		color: var(--m3c-on-tertiary-container);
	}
	.c-disconnected,
	.c-unknown {
		background: var(--m3c-error-container);
		color: var(--m3c-on-error-container);
	}
	.stats {
		display: grid;
		grid-template-columns: repeat(2, minmax(0, 1fr));
		gap: 0.75rem 1rem;
		margin: 1rem 0 0;
	}
	@media (width >= 40rem) {
		.stats {
			grid-template-columns: repeat(3, minmax(0, 1fr));
		}
	}
	.stats div {
		min-width: 0;
	}
	.stats dt {
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
	}
	.stats dd {
		margin: 0;
		font-size: 0.875rem;
		font-weight: 500;
		font-variant-numeric: tabular-nums;
		overflow-wrap: anywhere;
	}

	/* Alternative speed */
	.alt.on {
		background: var(--m3c-tertiary-container);
		color: var(--m3c-on-tertiary-container);
	}
	.alt-head {
		display: grid;
		grid-template-columns: auto minmax(0, 1fr) auto;
		align-items: center;
		gap: 0.75rem;
	}
	.alt-icon {
		display: grid;
		place-items: center;
		width: 2.5rem;
		height: 2.5rem;
		border-radius: 50%;
		background: var(--m3c-surface-container-highest);
		color: var(--m3c-on-surface-variant);
	}
	.alt.on .alt-icon {
		background: var(--m3c-tertiary);
		color: var(--m3c-on-tertiary);
	}
	.alt.on :is(.body-small, .note) {
		color: inherit;
	}
	.sw {
		display: flex;
		cursor: pointer;
	}
	.alt-text p {
		margin: 0.125rem 0 0;
		opacity: 0.85;
	}
	.limits {
		width: 100%;
		margin-top: 1rem;
		border-collapse: collapse;
		font-size: 0.875rem;
		font-variant-numeric: tabular-nums;
		table-layout: fixed;
	}
	.limits th,
	.limits td {
		text-align: left;
		padding: 0.375rem 0.5rem 0.375rem 0;
		overflow-wrap: anywhere;
	}
	.limits thead th {
		font-size: 0.75rem;
		font-weight: 500;
		opacity: 0.75;
	}
	.limits tbody th {
		font-weight: 400;
	}
	.limits tr.active {
		font-weight: 600;
	}
	.limits tr.active th {
		font-weight: 600;
	}

	/* Torrents */
	.torrents {
		display: grid;
		gap: 0.5rem;
		min-width: 0;
	}
	.bulk {
		display: flex;
		gap: 0.5rem;
	}
	.bulk :global(button) {
		flex: 1 1 auto;
	}
	.lbl {
		white-space: nowrap;
	}
	@media (width < 24rem) {
		.all {
			display: none;
		}
	}
	.chips {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		overflow-x: auto;
		scrollbar-width: none;
		padding: 0.125rem 0;
	}
	.chips > :global(*) {
		flex: none;
	}
	.chip-label {
		display: grid;
		place-items: center;
		color: var(--m3c-on-surface-variant);
	}

	/* About */
	.about {
		display: grid;
		gap: 0.5rem;
	}
	.about h2:not(:first-child) {
		margin-top: 0.5rem;
	}
	.kv {
		display: grid;
		grid-template-columns: max-content minmax(0, 1fr);
		gap: 0.25rem 1rem;
		margin: 0;
		font-size: 0.875rem;
	}
	.kv dt {
		color: var(--m3c-on-surface-variant);
	}
	.kv dd {
		margin: 0;
		overflow-wrap: anywhere;
		font-variant-numeric: tabular-nums;
	}
	.kv dd.path {
		font-family: ui-monospace, monospace;
		font-size: 0.75rem;
	}
</style>
