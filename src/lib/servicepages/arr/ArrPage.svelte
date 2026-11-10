<script lang="ts">
	// Shared Sonarr / Radarr service screen: status, wanted, history and setup summary.
	import { untrack } from 'svelte';
	import { Button, Chip, Dialog, Icon, LinearProgress, LinearProgressEstimate, LoadingIndicator, snackbar, Tabs } from 'm3-svelte';
	import iRss from '@ktibow/iconset-material-symbols/rss-feed-rounded';
	import iSearch from '@ktibow/iconset-material-symbols/travel-explore-rounded';
	import iUpgrade from '@ktibow/iconset-material-symbols/upgrade-rounded';
	import iRefresh from '@ktibow/iconset-material-symbols/refresh-rounded';
	import iError from '@ktibow/iconset-material-symbols/error-rounded';
	import iWarning from '@ktibow/iconset-material-symbols/warning-rounded';
	import iInfo from '@ktibow/iconset-material-symbols/info-rounded';
	import iOk from '@ktibow/iconset-material-symbols/check-circle-rounded';
	import iOpen from '@ktibow/iconset-material-symbols/open-in-new-rounded';
	import iArrow from '@ktibow/iconset-material-symbols/arrow-forward-rounded';
	import iUpdate from '@ktibow/iconset-material-symbols/system-update-rounded';
	import { live } from '$lib/live.svelte';
	import { ago, bytes } from '$lib/format';
	import type { ServicePageProps } from '../types';
	import WantedList from './WantedList.svelte';
	import HistoryList from './HistoryList.svelte';
	import { dateTime, healthSource, protocol, since, taskStatus, until, uptime } from './format';
	import type { ArrCommand, ArrOverview, HistoryFilter, WantedKind } from './types';

	let { service, isAdmin }: ServicePageProps = $props();

	// The route remounts this page (keyed) when the service changes, so the id is fixed for its lifetime.
	const base = `/api/svc/arr/${untrack(() => service.id)}`;
	const ov = live<ArrOverview>(base, 30_000);
	const d = $derived(ov.state.data);
	const movies = $derived(service.type === 'radarr');

	let tab = $state('status');
	const TABS = [
		{ name: 'Status', value: 'status' },
		{ name: 'Wanted', value: 'wanted' },
		{ name: 'History', value: 'history' },
		{ name: 'Setup', value: 'setup' }
	];

	let wantedKind = $state<WantedKind>('missing');
	let wantedPage = $state(1);
	let historyFilter = $state<HistoryFilter>('all');
	let historyPage = $state(1);
	const HISTORY_FILTERS: Array<{ value: HistoryFilter; label: string }> = [
		{ value: 'all', label: 'All' },
		{ value: 'grabbed', label: 'Grabbed' },
		{ value: 'imported', label: 'Imported' },
		{ value: 'failed', label: 'Failed' }
	];

	function showWanted(kind: WantedKind) {
		wantedKind = kind;
		wantedPage = 1;
		tab = 'wanted';
	}

	// Ticking clock for uptime (once a minute is plenty).
	let now = $state(Date.now());
	$effect(() => {
		const t = setInterval(() => (now = Date.now()), 60_000);
		return () => clearInterval(t);
	});

	/* -------------------------------- actions -------------------------------- */
	const ACTIONS = $derived<Record<ArrCommand, { label: string; icon: typeof iRss; title: string; body: string }>>({
		rss: {
			label: 'RSS sync',
			icon: iRss,
			title: 'Run an RSS sync?',
			body: 'Checks every RSS-enabled indexer for new releases and grabs anything wanted.'
		},
		missing: {
			label: 'Search missing',
			icon: iSearch,
			title: `Search all missing ${movies ? 'movies' : 'episodes'}?`,
			body: `Searches your indexers for every monitored ${movies ? 'movie' : 'episode'} that isn't on disk${d?.missing ? ` (${d.missing.toLocaleString()})` : ''}. On a big library this can hit indexer limits.`
		},
		cutoff: {
			label: 'Search upgrades',
			icon: iUpgrade,
			title: 'Search all cutoff unmet?',
			body: `Looks for better releases for everything below its quality cutoff${d?.cutoff ? ` (${d.cutoff.toLocaleString()})` : ''}.`
		},
		refresh: {
			label: 'Refresh & scan',
			icon: iRefresh,
			title: `Refresh & scan all ${movies ? 'movies' : 'series'}?`,
			body: 'Refreshes metadata and rescans every folder on disk. It can take a while on large libraries.'
		}
	});
	const ACTION_ORDER: ArrCommand[] = ['rss', 'missing', 'cutoff', 'refresh'];

	let confirmOpen = $state(false);
	let pending = $state<ArrCommand | null>(null);
	let busy = $state(false);

	let refreshTimer: ReturnType<typeof setTimeout> | undefined;
	$effect(() => () => clearTimeout(refreshTimer));

	function ask(c: ArrCommand) {
		pending = c;
		confirmOpen = true;
	}

	async function run() {
		const c = pending;
		if (!c) return;
		busy = true;
		try {
			const r = await fetch(`${base}/command`, {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ command: c })
			});
			const body = (r.headers.get('content-type') ?? '').includes('json') ? await r.json().catch(() => null) : null;
			if (!r.ok) throw new Error(body?.error ?? body?.message ?? `HTTP ${r.status}`);
			snackbar(typeof body?.message === 'string' ? body.message : `${ACTIONS[c].label} started`, {}, true);
			clearTimeout(refreshTimer);
			refreshTimer = setTimeout(() => ov.refresh(), 1500);
		} catch (e) {
			snackbar(`Couldn't start ${ACTIONS[c].label.toLowerCase()}: ${e instanceof Error ? e.message : e}`, {}, true);
		} finally {
			busy = false;
			confirmOpen = false;
		}
	}

	const HEALTH_ICON = { error: iError, warning: iWarning, notice: iInfo, ok: iOk } as const;
	const issues = $derived((d?.health ?? []).filter((h) => h.type !== 'ok'));
	const running = $derived((d?.commands ?? []).some((c) => c.status === 'started' || c.status === 'queued'));
</script>

<Tabs items={TABS} bind:tab secondary />

<div class="content">
	{#if !d}
		{#if ov.state.error}
			<section class="card"><p class="error" role="alert">{ov.state.error}</p></section>
		{:else}
			<div class="center"><LoadingIndicator aria-label="Loading status" /></div>
		{/if}
	{:else}
		{#if ov.state.error}<p class="stale" role="status" title={ov.state.error}>Couldn't refresh — showing last known data ({ov.state.error})</p>{/if}
		{#if d.partial.length}<p class="stale" role="status">Couldn't load: {d.partial.join(', ')}</p>{/if}

		{#if tab === 'status'}
			<div class="cards">
				<section class="card">
					<h2>System</h2>
					{#if d.system.update}
						<div class="update">
							<Icon icon={iUpdate} />
							<span>
								Update available: <strong>{d.system.update.version}</strong>
								{#if d.system.update.releaseDate}<span class="muted"> · released {ago(d.system.update.releaseDate)}</span>{/if}
								{#if d.system.docker || d.system.updateMechanism === 'docker'}<span class="muted block">Update by pulling a newer container image.</span>{/if}
							</span>
						</div>
					{/if}
					<dl>
						<dt>Version</dt>
						<dd>{d.system.version}</dd>
						{#if d.system.startTime}
							<dt>Uptime</dt>
							<dd title={`Started ${dateTime(d.system.startTime)}`}>{uptime(d.system.startTime, now)}</dd>
						{/if}
						{#if d.system.branch}<dt>Branch</dt><dd>{d.system.branch}</dd>{/if}
						{#if d.system.os}<dt>OS</dt><dd>{d.system.os}{d.system.docker ? ' (Docker)' : ''}</dd>{/if}
						{#if d.system.runtime}<dt>Runtime</dt><dd>{d.system.runtime}</dd>{/if}
						{#if d.system.database}<dt>Database</dt><dd>{d.system.database}</dd>{/if}
					</dl>
				</section>

				<section class="card">
					<h2>At a glance</h2>
					<div class="stats">
						<a class="stat" href="/activity" class:bad={d.queue?.errors} class:warn={!d.queue?.errors && d.queue?.warnings}>
							<span class="num">{d.queue ? d.queue.count.toLocaleString() : '–'}</span>
							<span class="lbl">In queue</span>
						</a>
						<button class="stat" onclick={() => showWanted('missing')}>
							<span class="num">{d.missing?.toLocaleString() ?? '–'}</span>
							<span class="lbl">Missing</span>
						</button>
						<button class="stat" onclick={() => showWanted('cutoff')}>
							<span class="num">{d.cutoff?.toLocaleString() ?? '–'}</span>
							<span class="lbl">Cutoff unmet</span>
						</button>
					</div>
					{#if d.queue && (d.queue.errors || d.queue.warnings)}
						<p class="muted small">Some queue items need attention.</p>
					{/if}
					<div class="row-end">
						<Button variant="text" href="/activity">Open Activity <Icon icon={iArrow} /></Button>
					</div>
				</section>

				<section class="card">
					<h2>Health</h2>
					{#if issues.length === 0}
						<p class="healthy"><Icon icon={iOk} /> No issues</p>
					{:else}
						<ul class="issues">
							{#each issues as h, i (`${h.source}:${i}`)}
								<li class="issue issue-{h.type}">
									<Icon icon={HEALTH_ICON[h.type]} />
									<div class="issue-body">
										<span class="issue-src">{healthSource(h.source)}</span>
										<span>{h.message}</span>
										{#if h.wikiUrl}
											<a href={h.wikiUrl} target="_blank" rel="noopener noreferrer">How to fix <Icon icon={iOpen} size={14} /></a>
										{/if}
									</div>
								</li>
							{/each}
						</ul>
					{/if}
				</section>

				{#if isAdmin || d.commands.length}
					<section class="card">
						<h2>Tasks</h2>
						{#if isAdmin}
							<div class="actions">
								{#each ACTION_ORDER as c (c)}
									<Button variant="tonal" onclick={() => ask(c)}><Icon icon={ACTIONS[c].icon} />{ACTIONS[c].label}</Button>
								{/each}
							</div>
						{/if}
						{#if d.commands.length}
							<ul class="tasks">
								{#each d.commands as c (c.id)}
									<li>
										<span class="task-name">{c.name}</span>
										<span class="task-st st-{c.status}">{taskStatus(c.status)}{c.started ? ` · ${since(c.ended ?? c.started, now)}` : ''}</span>
									</li>
								{/each}
							</ul>
							{#if running}<LinearProgressEstimate sToHalfway={20} aria-label="Tasks running" />{/if}
						{:else}
							<p class="muted small">No recent tasks.</p>
						{/if}
					</section>
				{/if}

				<section class="card wide">
					<h2>Storage</h2>
					{#if d.disks.length === 0}
						<p class="muted">No disks reported.</p>
					{:else}
						<ul class="disks">
							{#each d.disks as k (k.path)}
								<li>
									<div class="disk-line">
										<span class="path" title={k.path}>{k.path}</span>
										{#if !k.accessible}
											<span class="free low">Not accessible</span>
										{:else}
											<span class="free" class:low={k.total ? k.free / k.total < 0.1 : false}>{bytes(k.free)} free</span>
										{/if}
									</div>
									{#if k.total}
										<LinearProgress percent={(1 - k.free / k.total) * 100} aria-label={`${k.path} usage`} />
									{/if}
									<div class="disk-sub">
										<span>{k.rootFolder ? 'Root folder' : 'Drive'}</span>
										{#if k.total}<span>{bytes(k.total - k.free)} of {bytes(k.total)} used</span>{/if}
										{#if k.unmapped}<span>{k.unmapped} unmapped folder{k.unmapped === 1 ? '' : 's'}</span>{/if}
									</div>
								</li>
							{/each}
						</ul>
					{/if}
				</section>
			</div>
		{:else if tab === 'wanted'}
			<div class="toolbar">
				<div class="chips">
					<Chip variant="general" selected={wantedKind === 'missing'} onclick={() => ((wantedKind = 'missing'), (wantedPage = 1))}>
						Missing{d.missing !== null ? ` · ${d.missing.toLocaleString()}` : ''}
					</Chip>
					<Chip variant="general" selected={wantedKind === 'cutoff'} onclick={() => ((wantedKind = 'cutoff'), (wantedPage = 1))}>
						Cutoff unmet{d.cutoff !== null ? ` · ${d.cutoff.toLocaleString()}` : ''}
					</Chip>
				</div>
				{#if isAdmin}
					<Button variant="text" onclick={() => ask(wantedKind)}>
						<Icon icon={wantedKind === 'missing' ? iSearch : iUpgrade} />Search all
					</Button>
				{/if}
			</div>
			{#key `${wantedKind}:${wantedPage}`}
				<WantedList url={`${base}/wanted?kind=${wantedKind}&page=${wantedPage}`} kind={wantedKind} onpage={(p) => (wantedPage = p)} />
			{/key}
		{:else if tab === 'history'}
			<div class="toolbar">
				<div class="chips">
					{#each HISTORY_FILTERS as f (f.value)}
						<Chip variant="general" selected={historyFilter === f.value} onclick={() => ((historyFilter = f.value), (historyPage = 1))}>{f.label}</Chip>
					{/each}
				</div>
			</div>
			{#key `${historyFilter}:${historyPage}`}
				<HistoryList url={`${base}/history?event=${historyFilter}&page=${historyPage}`} onpage={(p) => (historyPage = p)} />
			{/key}
		{:else}
			<div class="cards">
				<section class="card">
					<h2>Indexers</h2>
					{#if d.indexers.length === 0}
						<p class="muted">No indexers — add them in {movies ? 'Radarr' : 'Sonarr'} or sync them from Prowlarr.</p>
					{:else}
						<ul class="plist">
							{#each d.indexers as x (x.id)}
								{@const off = !x.rss && !x.autoSearch && !x.interactiveSearch}
								<li>
									<div class="pl-line">
										<span class="pl-name">{x.name}</span>
										{#if x.disabledTill}
											<span class="badge bad" title={x.lastFailure ? `Last failure ${dateTime(x.lastFailure)}` : undefined}>Failing</span>
										{:else if off}
											<span class="badge">Disabled</span>
										{:else}
											<span class="badge ok">Enabled</span>
										{/if}
									</div>
									<div class="pl-sub">
										{protocol(x.protocol)}{x.implementation ? ` · ${x.implementation}` : ''} · priority {x.priority}
									</div>
									{#if !off}
										<div class="pl-sub">
											{[x.rss && 'RSS', x.autoSearch && 'Automatic search', x.interactiveSearch && 'Interactive search'].filter(Boolean).join(' · ')}
										</div>
									{/if}
									{#if x.disabledTill}<div class="pl-sub bad-text">Paused until {until(x.disabledTill)} after failures</div>{/if}
								</li>
							{/each}
						</ul>
					{/if}
				</section>

				<section class="card">
					<h2>Download clients</h2>
					{#if d.downloadClients.length === 0}
						<p class="muted">No download clients.</p>
					{:else}
						<ul class="plist">
							{#each d.downloadClients as x (x.id)}
								<li>
									<div class="pl-line">
										<span class="pl-name">{x.name}</span>
										{#if x.disabledTill}
											<span class="badge bad">Failing</span>
										{:else if x.enabled}
											<span class="badge ok">Enabled</span>
										{:else}
											<span class="badge">Disabled</span>
										{/if}
									</div>
									<div class="pl-sub">{protocol(x.protocol)}{x.implementation && x.implementation !== x.name ? ` · ${x.implementation}` : ''} · priority {x.priority}</div>
									{#if x.disabledTill}<div class="pl-sub bad-text">Paused until {until(x.disabledTill)} after failures</div>{/if}
								</li>
							{/each}
						</ul>
					{/if}
				</section>

				<section class="card">
					<h2>Quality profiles</h2>
					{#if d.profiles.length === 0}
						<p class="muted">No quality profiles.</p>
					{:else}
						<ul class="plist">
							{#each d.profiles as p (p.id)}
								<li>
									<div class="pl-line">
										<span class="pl-name">{p.name}</span>
										<span class="badge">{p.upgradeAllowed ? 'Upgrades on' : 'No upgrades'}</span>
									</div>
									{#if p.cutoff && p.upgradeAllowed}<div class="pl-sub">Upgrade until {p.cutoff}</div>{/if}
									<div class="pl-sub quals">{p.qualities.length ? p.qualities.join(', ') : 'No qualities allowed'}</div>
								</li>
							{/each}
						</ul>
					{/if}
				</section>

				<section class="card">
					<h2>Tags</h2>
					{#if d.tags.length === 0}
						<p class="muted">No tags.</p>
					{:else}
						<ul class="tags">
							{#each d.tags as t (t.id)}
								<li class:unused={t.uses === 0}>
									<span>{t.label}</span>
									<span class="uses" title={`Used by ${t.uses} item${t.uses === 1 ? '' : 's'}`}>{t.uses}</span>
								</li>
							{/each}
						</ul>
						<p class="muted small">Numbers count everything a tag is attached to ({movies ? 'movies' : 'series'}, indexers, profiles…).</p>
					{/if}
				</section>
			</div>
		{/if}
	{/if}
</div>

<Dialog headline={pending ? ACTIONS[pending].title : ''} bind:open={confirmOpen}>
	{#if pending}<p class="dlg">{ACTIONS[pending].body}</p>{/if}
	{#snippet buttons()}
		<Button variant="text" onclick={() => (confirmOpen = false)}>Cancel</Button>
		<Button variant="filled" disabled={busy} onclick={run}>{pending ? ACTIONS[pending].label : 'Run'}</Button>
	{/snippet}
</Dialog>

<style>
	.content {
		margin-top: 1rem;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.75rem;
	}
	.center {
		display: grid;
		place-items: center;
		padding: 3rem 0;
	}
	.cards {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.75rem;
		align-items: start;
	}
	@media (min-width: 60rem) {
		.cards {
			grid-template-columns: repeat(2, minmax(0, 1fr));
		}
		.card.wide {
			grid-column: 1 / -1;
		}
	}
	.card {
		min-width: 0;
		padding: 1rem 1.125rem;
		border-radius: var(--radius-l);
		background: var(--m3c-surface-container-low);
	}
	h2 {
		margin: 0 0 0.75rem;
		font-size: 1rem;
		font-weight: 600;
	}
	p {
		margin: 0;
	}
	.muted {
		color: var(--m3c-on-surface-variant);
	}
	.small {
		margin-top: 0.5rem;
		font-size: 0.8125rem;
	}
	.block {
		display: block;
		font-size: 0.8125rem;
	}
	.error {
		color: var(--m3c-error);
	}
	.stale {
		overflow-wrap: anywhere;
		font-size: 0.8125rem;
		color: var(--m3c-error);
	}

	/* system */
	dl {
		display: grid;
		grid-template-columns: auto minmax(0, 1fr);
		gap: 0.375rem 1rem;
		margin: 0;
		font-size: 0.875rem;
	}
	dt {
		color: var(--m3c-on-surface-variant);
	}
	dd {
		margin: 0;
		overflow-wrap: anywhere;
	}
	.update {
		display: flex;
		gap: 0.625rem;
		align-items: flex-start;
		padding: 0.625rem 0.75rem;
		margin-bottom: 0.875rem;
		border-radius: var(--radius-m);
		background: var(--m3c-tertiary-container);
		color: var(--m3c-on-tertiary-container);
		font-size: 0.875rem;
	}
	.update .muted {
		color: inherit;
		opacity: 0.8;
	}

	/* at a glance */
	.stats {
		display: grid;
		grid-template-columns: repeat(3, minmax(0, 1fr));
		gap: 0.5rem;
	}
	.stat {
		display: grid;
		gap: 0.125rem;
		padding: 0.75rem 0.5rem;
		border: 0;
		border-radius: var(--radius-m);
		background: var(--m3c-surface-container-high);
		color: inherit;
		font: inherit;
		text-align: center;
		text-decoration: none;
		cursor: pointer;
	}
	.stat:hover {
		background: var(--m3c-surface-container-highest);
	}
	.stat.warn .num {
		color: var(--m3c-tertiary);
	}
	.stat.bad .num {
		color: var(--m3c-error);
	}
	.num {
		font-size: 1.5rem;
		font-weight: 500;
		font-variant-numeric: tabular-nums;
	}
	.lbl {
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.row-end {
		display: flex;
		justify-content: flex-end;
		margin: 0.5rem -0.5rem -0.5rem 0;
	}

	/* health */
	.healthy {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		color: var(--m3c-tertiary);
	}
	.issues {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		gap: 0.5rem;
	}
	.issue {
		display: flex;
		gap: 0.625rem;
		align-items: flex-start;
		font-size: 0.875rem;
	}
	.issue > :global(svg) {
		flex: none;
		margin-top: 0.0625rem;
	}
	.issue-error > :global(svg) {
		color: var(--m3c-error);
	}
	.issue-warning > :global(svg) {
		color: var(--m3c-tertiary);
	}
	.issue-notice > :global(svg) {
		color: var(--m3c-primary);
	}
	.issue-body {
		min-width: 0;
		display: grid;
		gap: 0.125rem;
		overflow-wrap: anywhere;
	}
	.issue-src {
		font-size: 0.75rem;
		font-weight: 600;
		color: var(--m3c-on-surface-variant);
	}
	.issue a {
		display: inline-flex;
		align-items: center;
		gap: 0.25rem;
		color: var(--m3c-primary);
		font-size: 0.8125rem;
		justify-self: start;
	}

	/* tasks */
	.actions {
		display: flex;
		flex-wrap: wrap;
		gap: 0.5rem;
		margin-bottom: 0.75rem;
	}
	.tasks {
		list-style: none;
		margin: 0 0 0.5rem;
		padding: 0;
		display: grid;
		gap: 0.375rem;
		font-size: 0.875rem;
	}
	.tasks li {
		display: flex;
		justify-content: space-between;
		gap: 0.75rem;
	}
	.task-name {
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.task-st {
		flex: none;
		color: var(--m3c-on-surface-variant);
	}
	.st-started,
	.st-queued {
		color: var(--m3c-primary);
	}
	.st-failed {
		color: var(--m3c-error);
	}

	/* storage */
	.disks {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 1rem;
	}
	@media (min-width: 60rem) {
		.disks {
			grid-template-columns: repeat(2, minmax(0, 1fr));
			column-gap: 1.5rem;
		}
	}
	.disk-line {
		display: flex;
		justify-content: space-between;
		gap: 0.5rem;
		font-size: 0.875rem;
		margin-bottom: 0.375rem;
	}
	.path {
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		font-weight: 500;
	}
	.free {
		white-space: nowrap;
		font-variant-numeric: tabular-nums;
	}
	.free.low {
		color: var(--m3c-error);
	}
	.disk-sub {
		display: flex;
		flex-wrap: wrap;
		gap: 0 0.75rem;
		margin-top: 0.25rem;
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
	}

	/* wanted / history toolbar */
	.toolbar {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		justify-content: space-between;
		gap: 0.5rem;
	}
	.chips {
		display: flex;
		flex-wrap: wrap;
		gap: 0.5rem;
	}

	/* setup */
	.plist {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		gap: 0.875rem;
	}
	.pl-line {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 0.5rem;
	}
	.pl-name {
		min-width: 0;
		font-weight: 500;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.pl-sub {
		font-size: 0.8125rem;
		color: var(--m3c-on-surface-variant);
		overflow-wrap: anywhere;
	}
	.bad-text {
		color: var(--m3c-error);
	}
	.badge {
		flex: none;
		padding: 0.125rem 0.5rem;
		border-radius: 0.5rem;
		font-size: 0.75rem;
		background: var(--m3c-surface-container-highest);
		color: var(--m3c-on-surface-variant);
	}
	.badge.ok {
		background: var(--m3c-secondary-container);
		color: var(--m3c-on-secondary-container);
	}
	.badge.bad {
		background: var(--m3c-error-container);
		color: var(--m3c-on-error-container);
	}
	.tags {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-wrap: wrap;
		gap: 0.5rem;
	}
	.tags li {
		display: inline-flex;
		align-items: center;
		gap: 0.5rem;
		padding: 0.25rem 0.375rem 0.25rem 0.75rem;
		border-radius: 0.5rem;
		border: 1px solid var(--m3c-outline-variant);
		font-size: 0.875rem;
		max-width: 100%;
	}
	.tags li span:first-child {
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.tags li.unused {
		color: var(--m3c-on-surface-variant);
	}
	.uses {
		min-width: 1.25rem;
		padding: 0 0.375rem;
		border-radius: 0.375rem;
		background: var(--m3c-surface-container-highest);
		font-size: 0.75rem;
		text-align: center;
	}
	.dlg {
		margin: 0;
	}
</style>
