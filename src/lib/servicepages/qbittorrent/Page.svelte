<script lang="ts" module>
	// Remembered while moving around the app.
	const ui = $state({ tab: 'torrents' });
</script>

<script lang="ts">
	import { BottomSheet, Button, Chip, Icon, Switch, snackbar } from 'm3-svelte';
	import iAlt from '@ktibow/iconset-material-symbols/speed-rounded';
	import iFilter from '@ktibow/iconset-material-symbols/filter-alt';
	import iCategory from '@ktibow/iconset-material-symbols/category-rounded';
	import iTag from '@ktibow/iconset-material-symbols/label-rounded';
	import iCheck from '@ktibow/iconset-material-symbols/check-rounded';
	import iFeed from '@ktibow/iconset-material-symbols/sync-problem-rounded';
	import { iClose } from '$lib/icons';
	import { bytes, speed } from '$lib/format';
	import { live } from '$lib/live.svelte';
	import { liveActivity } from '$lib/activity/live.svelte';
	import { categoryLabel } from '$lib/activity/format';
	import TorrentList from '$lib/activity/TorrentList.svelte';
	import TorrentMenu from '$lib/activity/TorrentMenu.svelte';
	import AddTorrent from '$lib/activity/AddTorrent.svelte';
	import type { Torrent } from '$lib/activity/types';
	import type { ServicePageProps } from '../types';
	import Summary from '../shared/Summary.svelte';
	import ServiceTabs from '../shared/ServiceTabs.svelte';
	import Card from '../shared/Card.svelte';
	import Empty from '../shared/Empty.svelte';
	import Loading from '../shared/Loading.svelte';
	import { nb, sentence } from '../shared/format';
	import type { SummaryNotice } from '../shared/types';
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

	const TABS = [
		{ name: 'Torrents', value: 'torrents' },
		{ name: 'Limits', value: 'limits' },
		{ name: 'About', value: 'about' }
	];

	/* ---------------- Category / tag filters ---------------- */
	// One filter button in the torrent toolbar opens a sheet with both; the active one shows as a
	// removable chip at the start of the status chips.
	const NONE = '\u0000none';
	let category = $state<string | null>(null);
	let tag = $state<string | null>(null);
	let sheetOpen = $state(false);

	function countBy(keys: (x: Torrent) => string[]) {
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
	const canFilter = $derived(torrents.length > 0 && (catList.length > 0 || tagList.length > 0));

	const matches = (x: Torrent) =>
		(category === null || (category === NONE ? !x.category : x.category === category)) &&
		(tag === null || (tag === NONE ? x.tags.length === 0 : x.tags.includes(tag)));
	const catName = (v: string) => (v === NONE ? 'Uncategorized' : categoryLabel(v));
	const tagName = (v: string) => (v === NONE ? 'Untagged' : v);

	type Option = { value: string | null; label: string; count: number };
	const sections = $derived.by(() => {
		const out: Array<{ kind: 'category' | 'tag'; title: string; options: Option[] }> = [];
		if (catList.length || category !== null) {
			out.push({
				kind: 'category',
				title: 'Category',
				options: [
					{ value: null, label: 'All categories', count: torrents.length },
					...catList.map((v) => ({ value: v, label: catName(v), count: catCounts.get(v) ?? 0 })),
					...(catCounts.get(NONE) || category === NONE ? [{ value: NONE, label: catName(NONE), count: catCounts.get(NONE) ?? 0 }] : [])
				]
			});
		}
		if (tagList.length || tag !== null) {
			out.push({
				kind: 'tag',
				title: 'Tag',
				options: [
					{ value: null, label: 'All tags', count: torrents.length },
					...tagList.map((v) => ({ value: v, label: v, count: tagCounts.get(v) ?? 0 })),
					...(tagCounts.get(NONE) || tag === NONE ? [{ value: NONE, label: tagName(NONE), count: tagCounts.get(NONE) ?? 0 }] : [])
				]
			});
		}
		return out;
	});
	function pick(kind: 'category' | 'tag', v: string | null) {
		if (kind === 'category') category = v;
		else tag = v;
		sheetOpen = false;
	}

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
		const err = !res ? 'You seem to be offline' : res.ok ? null : ((await res.json().catch(() => ({})))?.error ?? `HTTP ${res.status}`);
		await ov.refresh();
		altPending = null;
		snackbar(err ? `Couldn't switch speed limits. ${sentence(err)}` : enabled ? 'Alternative speed limits on' : 'Alternative speed limits off', {}, true);
	}

	let addOpen = $state(false);
	// Lets the list inside the sheet scroll without the m3 sheet treating it as a drag.
	const stop = (e: Event) => e.stopPropagation();

	/* ---------------- Formatting ---------------- */
	/** Drops the decimal once there are three digits ("682.6 GB" → "683 GB") so tiles never truncate. */
	const tile = (v: string) => {
		const [n, unit] = v.split(' ');
		return nb(Number(n) >= 100 ? `${Math.round(Number(n))} ${unit}` : v);
	};
	const limit = (n: number) => (n > 0 ? nb(speed(n)) : 'Unlimited');
	const CONN: Record<QbitConnection, string> = {
		connected: 'Connected',
		firewalled: 'Firewalled',
		disconnected: 'Disconnected',
		unknown: 'Connection unknown'
	};
	const cap = (n: number) => (n < 0 ? 'No limit' : String(n));
	/** 0 means "stop as soon as it's done", not a ratio anyone would type. */
	const ratioLimit = (r: number | null) => (r === null ? 'No limit' : r === 0 ? 'Stops seeding immediately' : String(Number(r.toFixed(2))));
	const timeLimit = (m: number | null) => (m === null ? 'No limit' : m === 0 ? 'Stops seeding immediately' : minutes(m));
	function minutes(m: number) {
		if (m < 60) return `${m} min`;
		const h = Math.floor(m / 60);
		return h >= 48 && m % 1440 === 0 ? `${m / 1440} days` : `${h} h${m % 60 ? ` ${m % 60} min` : ''}`;
	}
	const notices = $derived.by((): SummaryNotice[] => {
		if (!t) return [];
		if (t.connection === 'disconnected') return [{ tone: 'bad', text: 'qBittorrent has no network connection.' }];
		if (t.connection === 'firewalled') return [{ tone: 'warn', text: "qBittorrent is firewalled: peers can't connect to it, so speeds may be lower." }];
		return [];
	});
</script>

{#if !o}
	{#if ov.state.error}
		<Empty error title={`Couldn't reach ${service.name}`} hint={sentence(ov.state.error)} onretry={() => ov.refresh()} />
	{:else}
		<Loading label="Loading qBittorrent" />
	{/if}
{:else if t}
	<Summary
		meta={[o.version ? `Version ${o.version.replace(/^v/, '')}` : null, CONN[t.connection], altOn && 'Alternative speed on']}
		stats={[
			{ value: tile(speed(t.down)), label: 'Download', tone: t.down > 0 ? 'info' : undefined },
			{ value: tile(speed(t.up)), label: 'Upload' },
			{ value: t.freeSpace === null ? null : tile(bytes(t.freeSpace)), label: 'Free space' }
		]}
	/>

	<ServiceTabs items={TABS} bind:tab={ui.tab} {notices} stale={ov.state.error}>
		{#if ui.tab === 'torrents'}
			{#if !snap}
				<Loading label="Loading torrents" />
			{:else if !client}
				<Empty icon={iFeed} title="This instance isn't in the activity feed yet" hint="It appears within a few seconds of being added." />
			{:else if client.error}
				<Empty error title="Couldn't load torrents" hint={sentence(client.error)} />
			{:else}
				{@const c = client}
				<div class="torrents">
					<TorrentList
						{torrents}
						filter={category === null && tag === null ? undefined : matches}
						onreset={() => ((category = null), (tag = null))}
						{isAdmin}
						showClient={() => service.name}
						onAdd={isAdmin ? () => (addOpen = true) : undefined}
					>
						{#snippet toolbar()}
							{#if canFilter}
								<span class="filter-btn">
									<Button
										variant={category !== null || tag !== null ? 'filled' : 'tonal'}
										iconType="full"
										aria-label="Filter by category or tag"
										aria-haspopup="dialog"
										onclick={() => (sheetOpen = true)}><Icon icon={iFilter} /></Button
									>
								</span>
							{/if}
							{#if isAdmin}<TorrentMenu {torrents} clients={[c]} {isAdmin} onadd={() => (addOpen = true)} />{/if}
						{/snippet}
						{#snippet chips()}
							{#if category !== null}
								<Chip variant="input" icon={iCategory} trailingIcon={iClose} selected onclick={() => (category = null)} aria-label={`Clear category ${catName(category)}`}
									>{catName(category)}</Chip
								>
							{/if}
							{#if tag !== null}
								<Chip variant="input" icon={iTag} trailingIcon={iClose} selected onclick={() => (tag = null)} aria-label={`Clear tag ${tagName(tag)}`}>{tagName(tag)}</Chip>
							{/if}
						{/snippet}
					</TorrentList>
				</div>
			{/if}
		{:else if ui.tab === 'limits'}
			<div class="alt" class:on={altOn}>
				<span class="alt-icon"><Icon icon={iAlt} size={20} /></span>
				<span class="alt-text">
					<span class="alt-title">Alternative speed limits</span>
					<span class="alt-sub">
						{#if altPending !== null}Switching…{:else if altOn}On · ↓&nbsp;{o.prefs ? limit(o.prefs.altDownLimit) : 'reduced'} · ↑&nbsp;{o.prefs ? limit(o.prefs.altUpLimit) : 'reduced'}{:else}Off · normal limits{/if}
					</span>
				</span>
				<!-- The label forwards clicks on the switch's track to its input. -->
				<label class="sw">
					<Switch aria-label="Alternative speed limits" disabled={!isAdmin || altPending !== null} bind:checked={() => altOn, (v) => setAlt(v)} />
				</label>
			</div>
			<div class="cards">
				<Card title="Speed limits">
					{#if o.prefs}
						<table class="limits">
							<thead><tr><th scope="col"><span class="sr-only">Mode</span></th><th scope="col">Download</th><th scope="col">Upload</th></tr></thead>
							<tbody>
								<tr class:active={!altOn}><th scope="row">Normal{#if !altOn}<span class="now">In use</span>{/if}</th><td>{limit(o.prefs.downLimit)}</td><td>{limit(o.prefs.upLimit)}</td></tr>
								<tr class:active={altOn}><th scope="row">Alternative{#if altOn}<span class="now">In use</span>{/if}</th><td>{limit(o.prefs.altDownLimit)}</td><td>{limit(o.prefs.altUpLimit)}</td></tr>
							</tbody>
						</table>
						{#if o.prefs.altScheduled}<p class="note">A schedule in qBittorrent also switches these automatically.</p>{/if}
					{:else}
						<p class="note">Couldn't read qBittorrent's preferences.</p>
					{/if}
					{#if !isAdmin}<p class="note">Only admins can switch speed limits.</p>{/if}
				</Card>
				{#if o.prefs}
					{@const p = o.prefs}
					<Card title="Queueing & seeding">
						<dl class="kv">
							<dt>Queueing</dt><dd>{p.queueing ? 'On' : 'Off'}</dd>
							{#if p.queueing}
								<dt>Active downloads</dt><dd>{cap(p.maxActiveDownloads)}</dd>
								<dt>Active uploads</dt><dd>{cap(p.maxActiveUploads)}</dd>
								<dt>Active torrents</dt><dd>{cap(p.maxActiveTorrents)}</dd>
							{/if}
							<dt>Max ratio</dt><dd>{ratioLimit(p.maxRatio)}</dd>
							<dt>Max seeding time</dt><dd>{timeLimit(p.maxSeedingTime)}</dd>
							{#if p.maxRatio !== null || p.maxSeedingTime !== null}<dt>Then</dt><dd>{p.maxRatioAction}</dd>{/if}
						</dl>
						<p class="note">Read-only. Change these in qBittorrent's own settings.</p>
					</Card>
				{/if}
			</div>
		{:else}
			<div class="cards">
				<Card title="Transfer">
					<dl class="kv">
						<dt>This session</dt><dd><span class="pair">↓&nbsp;{nb(bytes(t.sessionDown))}</span> <span class="pair">↑&nbsp;{nb(bytes(t.sessionUp))}</span></dd>
						<dt>All time</dt><dd><span class="pair">↓&nbsp;{nb(bytes(t.allTimeDown))}</span> <span class="pair">↑&nbsp;{nb(bytes(t.allTimeUp))}</span></dd>
						<dt>Global ratio</dt><dd>{t.ratio === null ? '—' : t.ratio.toFixed(2)}</dd>
						<dt>Peers</dt><dd>{t.peers.toLocaleString()}</dd>
						<dt>DHT nodes</dt><dd>{t.dhtNodes.toLocaleString()}</dd>
					</dl>
				</Card>
				<Card title="About">
					<dl class="kv">
						<dt>Version</dt><dd>{o.version ? o.version.replace(/^v/, '') : '—'}</dd>
						<dt>Web API</dt><dd>{o.webApiVersion ?? '—'}</dd>
						{#if o.prefs}
							{@const p = o.prefs}
							{#if p.savePath !== null}
								<dt>Save path</dt><dd class="path">{p.savePath || '—'}</dd>
								{#if p.tempPath}<dt>Incomplete</dt><dd class="path">{p.tempPath}</dd>{/if}
							{/if}
							<dt>DHT</dt><dd>{p.dht ? 'On' : 'Off'}</dd>
							{#if p.listenPort !== null}<dt>Listen port</dt><dd>{p.listenPort}</dd>{/if}
						{/if}
					</dl>
				</Card>
			</div>
		{/if}
	</ServiceTabs>

	{#if client && !client.error}<AddTorrent bind:open={addOpen} clients={[client]} />{/if}

	{#if sheetOpen}
		<BottomSheet close={() => (sheetOpen = false)}>
			<div class="sheet" ontouchstart={stop} onwheel={stop} role="presentation">
				<div class="sheet-head">
					<h2 class="sheet-title">Filter torrents</h2>
					<Button variant="text" iconType="full" aria-label="Close" onclick={() => (sheetOpen = false)}><Icon icon={iClose} /></Button>
				</div>
				{#each sections as sec (sec.kind)}
					<h3 class="sec-title">{sec.title}</h3>
					<ul class="options">
						{#each sec.options as opt (opt.value ?? '')}
							{@const current = (sec.kind === 'category' ? category : tag) === opt.value}
							<li>
								<button class="opt" class:current aria-pressed={current} onclick={() => pick(sec.kind, opt.value)}>
									<span class="check">{#if current}<Icon icon={iCheck} size={20} />{/if}</span>
									<span class="opt-label">{opt.label}</span>
									<span class="opt-count">{opt.count}</span>
								</button>
							</li>
						{/each}
					</ul>
				{/each}
			</div>
		</BottomSheet>
	{/if}
{:else}
	<Empty error title="qBittorrent sent no transfer data" hint={ov.state.error ? sentence(ov.state.error) : 'Try again in a moment.'} onretry={() => ov.refresh()} />
{/if}

<style>
	.cards {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.75rem;
		align-items: start;
	}
	@media (width >= 56rem) {
		.cards {
			grid-template-columns: repeat(2, minmax(0, 1fr));
		}
	}
	.note {
		margin: 0;
		font-size: 0.75rem;
		line-height: 1rem;
		color: var(--m3c-on-surface-variant);
	}

	/* Alternative speed: a quiet row at the top of Limits; "on" only tints the icon and adds a thin outline. */
	.alt {
		display: grid;
		grid-template-columns: auto minmax(0, 1fr) auto;
		align-items: center;
		gap: 0.75rem;
		padding: 0.5rem 0.75rem;
		border-radius: var(--radius-m);
		background: var(--m3c-surface-container-low);
		border: 1px solid transparent;
	}
	.alt.on {
		border-color: var(--m3c-tertiary);
	}
	.alt-icon {
		display: grid;
		place-items: center;
		width: 2.25rem;
		height: 2.25rem;
		border-radius: 50%;
		background: var(--m3c-surface-container-highest);
		color: var(--m3c-on-surface-variant);
	}
	.alt.on .alt-icon {
		background: var(--m3c-tertiary-container);
		color: var(--m3c-on-tertiary-container);
	}
	.alt-text {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		min-width: 0;
	}
	.alt-title {
		font-size: 0.875rem;
		font-weight: 500;
	}
	.alt-sub {
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
		overflow-wrap: anywhere;
	}
	.alt.on .alt-sub {
		color: var(--m3c-tertiary);
	}
	.sw {
		display: flex;
		cursor: pointer;
	}

	/* Limits */
	.limits {
		width: 100%;
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
		vertical-align: top;
	}
	.limits thead th {
		font-size: 0.75rem;
		font-weight: 500;
		color: var(--m3c-on-surface-variant);
	}
	.limits tbody th {
		font-weight: 400;
	}
	.limits tr.active th,
	.limits tr.active td {
		font-weight: 600;
	}
	.now {
		display: block;
		font-size: 0.6875rem;
		font-weight: 500;
		color: var(--m3c-primary);
	}

	/* Torrents: TorrentList brings its own spacing. */
	.torrents {
		min-width: 0;
	}
	.filter-btn {
		flex: none;
		display: flex;
	}

	/* One aligned key/value grid per card. */
	.kv {
		display: grid;
		grid-template-columns: minmax(7.5rem, max-content) minmax(0, 1fr);
		gap: 0.5rem 1rem;
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
	.pair {
		white-space: nowrap;
	}
	.pair + .pair {
		margin-left: 0.5rem;
	}
	.kv dd.path {
		font-family: ui-monospace, monospace;
		font-size: 0.75rem;
	}

	/* Filter sheet: the m3 sheet caps itself at 480 px and clips, so the content scrolls inside it. */
	.sheet {
		max-height: min(calc(480px - 3rem), calc(100dvh - 6rem));
		overflow-y: auto;
		overscroll-behavior: contain;
		touch-action: pan-y;
		scrollbar-width: thin;
		padding: 0 1rem 1.5rem;
		max-width: 40rem;
		margin: 0 auto;
	}
	.sheet-head {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 0.5rem;
		padding-left: 0.5rem;
	}
	.sheet-title {
		margin: 0;
		font-size: 1.125rem;
	}
	.sec-title {
		margin: 0.75rem 0 0.25rem;
		padding: 0 0.5rem;
		font-size: 0.875rem;
		font-weight: 500;
		color: var(--m3c-on-surface-variant);
	}
	.options {
		list-style: none;
		margin: 0;
		padding: 0;
	}
	.opt {
		all: unset;
		box-sizing: border-box;
		display: grid;
		grid-template-columns: 1.5rem minmax(0, 1fr) auto;
		align-items: center;
		gap: 0.75rem;
		width: 100%;
		min-height: 3rem;
		padding: 0 0.75rem 0 0.5rem;
		border-radius: 1.5rem;
		cursor: pointer;
	}
	.opt:hover {
		background: var(--m3c-surface-container-high);
	}
	.opt:focus-visible {
		outline: 2px solid var(--m3c-primary);
	}
	.opt.current {
		background: var(--m3c-secondary-container);
		color: var(--m3c-on-secondary-container);
	}
	.check {
		display: grid;
	}
	.opt-label {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.opt-count {
		font-size: 0.8125rem;
		font-variant-numeric: tabular-nums;
		color: var(--m3c-on-surface-variant);
	}
</style>
