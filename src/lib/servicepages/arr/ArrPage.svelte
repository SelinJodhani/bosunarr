<script lang="ts">
	// Shared Sonarr / Radarr service screen: summary, tasks + storage, wanted, history and setup.
	import { untrack } from 'svelte';
	import { Button, Chip, Icon, LinearProgress, snackbar } from 'm3-svelte';
	import iRss from '@ktibow/iconset-material-symbols/rss-feed-rounded';
	import iSearch from '@ktibow/iconset-material-symbols/travel-explore-rounded';
	import iUpgrade from '@ktibow/iconset-material-symbols/upgrade-rounded';
	import iRefresh from '@ktibow/iconset-material-symbols/refresh-rounded';
	import iTasks from '@ktibow/iconset-material-symbols/task-alt-rounded';
	import iDisk from '@ktibow/iconset-material-symbols/hard-disk-rounded';
	import { live } from '$lib/live.svelte';
	import { ago, bytes } from '$lib/format';
	import type { ServicePageProps } from '../types';
	import Summary from '../shared/Summary.svelte';
	import ServiceTabs from '../shared/ServiceTabs.svelte';
	import Card from '../shared/Card.svelte';
	import Badge from '../shared/Badge.svelte';
	import Empty from '../shared/Empty.svelte';
	import Loading from '../shared/Loading.svelte';
	import Indeterminate from '../shared/Indeterminate.svelte';
	import '../shared/chips.css';
	import ConfirmDialog from '../shared/ConfirmDialog.svelte';
	import { protocolLabel, sentence } from '../shared/format';
	import type { SummaryNotice } from '../shared/types';
	import WantedList from './WantedList.svelte';
	import HistoryList from './HistoryList.svelte';
	import { dateTime, healthSource, since, taskStatus, until, uptime } from './format';
	import type { ArrCommand, ArrOverview, HistoryFilter, WantedKind } from './types';

	let { service, isAdmin }: ServicePageProps = $props();

	// The route remounts this page (keyed) when the service changes, so the id is fixed for its lifetime.
	const base = `/api/svc/arr/${untrack(() => service.id)}`;
	const ov = live<ArrOverview>(base, 30_000);
	const d = $derived(ov.state.data);
	const movies = $derived(service.type === 'radarr');
	const appName = $derived(movies ? 'Radarr' : 'Sonarr');

	let tab = $state('overview');
	const TABS = [
		{ name: 'Overview', value: 'overview' },
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

	// Ticking clock for uptime and "x minutes ago" (once a minute is plenty).
	let now = $state(Date.now());
	$effect(() => {
		const t = setInterval(() => (now = Date.now()), 60_000);
		return () => clearInterval(t);
	});

	/* -------------------------------- summary -------------------------------- */
	const PARTS: Record<string, string> = {
		queue: 'the queue',
		health: 'health checks',
		disks: 'disk space',
		'root folders': 'root folders',
		indexers: 'indexers',
		'download clients': 'download clients',
		profiles: 'quality profiles',
		tags: 'tags'
	};
	const issues = $derived(
		(d?.health ?? [])
			.filter((h) => h.type !== 'ok')
			// The update shows once, as its own notice.
			.filter((h) => !(d?.system.update && /update/i.test(h.source)))
	);
	const notices = $derived.by((): SummaryNotice[] => {
		if (!d) return [];
		const out: SummaryNotice[] = [];
		if (d.system.update) {
			const u = d.system.update;
			const how = d.system.docker || d.system.updateMechanism === 'docker' ? ' Pull the new image to install it.' : '';
			const when = u.releaseDate ? `, released ${ago(u.releaseDate)}` : '';
			out.push({
				tone: 'update',
				text: `Update available: ${u.version}${when}.${how}`,
				href: `https://github.com/${appName}/${appName}/releases`,
				linkLabel: 'Release notes'
			});
		}
		for (const h of issues) {
			out.push({ tone: h.type === 'error' ? 'bad' : h.type === 'warning' ? 'warn' : 'info', source: healthSource(h.source), text: h.message, href: h.wikiUrl ?? undefined });
		}
		if (d.partial.length) {
			const what = d.partial.map((p) => PARTS[p] ?? p);
			out.push({ tone: 'warn', text: `Couldn't load ${what.join(', ')}. Trying again shortly.` });
		}
		return out;
	});

	/* -------------------------------- actions -------------------------------- */
	const ACTIONS = $derived<Record<ArrCommand, { label: string; icon: typeof iRss; confirm: string | null; body: string }>>({
		rss: { label: 'RSS sync', icon: iRss, confirm: null, body: '' },
		missing: {
			label: 'Search missing',
			icon: iSearch,
			confirm: `Search all missing ${movies ? 'movies' : 'episodes'}?`,
			body: `Searches your indexers for every monitored ${movies ? 'movie' : 'episode'} that isn't on disk${d?.missing ? ` (${d.missing.toLocaleString()})` : ''}. On a big library this can hit indexer limits.`
		},
		cutoff: {
			label: 'Search upgrades',
			icon: iUpgrade,
			confirm: 'Search all cutoff unmet?',
			body: `Looks for better releases for everything below its quality cutoff${d?.cutoff ? ` (${d.cutoff.toLocaleString()})` : ''}.`
		},
		refresh: {
			label: 'Refresh & scan',
			icon: iRefresh,
			confirm: `Refresh & scan all ${movies ? 'movies' : 'series'}?`,
			body: 'Refreshes metadata and rescans every folder on disk. It can take a while on large libraries.'
		}
	});
	const ACTION_ORDER: ArrCommand[] = ['rss', 'missing', 'cutoff', 'refresh'];

	const isRunning = (s: string) => s === 'started' || s === 'queued';
	const runningCmds = $derived(new Set((d?.commands ?? []).filter((c) => isRunning(c.status) && c.command).map((c) => c.command)));
	/** Started from here but not yet reported back by the arr: keeps its button disabled in between. */
	let launched = $state<Partial<Record<ArrCommand, number>>>({});
	const blocked = (c: ArrCommand) => runningCmds.has(c) || launched[c] !== undefined;

	// Once the arr lists the command (running or finished), its own status takes over.
	$effect(() => {
		const cmds = d?.commands ?? [];
		untrack(() => {
			for (const c of Object.keys(launched) as ArrCommand[]) {
				const t = launched[c] ?? 0;
				if (cmds.some((x) => x.command === c && (!x.started || +new Date(x.started) >= t - 5_000)) || Date.now() - t > 30_000) delete launched[c];
			}
		});
	});

	let confirmOpen = $state(false);
	let pending = $state<ArrCommand | null>(null);
	let refreshTimer: ReturnType<typeof setTimeout> | undefined;
	$effect(() => () => clearTimeout(refreshTimer));

	function trigger(c: ArrCommand) {
		if (blocked(c)) return;
		if (ACTIONS[c].confirm) {
			pending = c;
			confirmOpen = true;
		} else {
			start(c).then((err) => err && snackbar(`Couldn't start ${ACTIONS[c].label.toLowerCase()}. ${err}`, {}, true));
		}
	}

	/** Starts a command; resolves to an error message or null. */
	async function start(c: ArrCommand): Promise<string | null> {
		launched[c] = Date.now();
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
			return null;
		} catch (e) {
			delete launched[c];
			return sentence(e instanceof Error ? e.message : String(e));
		}
	}

	const ok = (b: boolean) => (b ? 'ok' : 'off');
</script>

{#if !d}
	{#if ov.state.error}
		<Empty error title={`Couldn't reach ${service.name}`} hint={sentence(ov.state.error)} onretry={() => ov.refresh()} />
	{:else}
		<Loading label={`Loading ${service.name}`} />
	{/if}
{:else}
	<Summary
		meta={[`${appName} ${d.system.version}`, d.system.startTime && `Up ${uptime(d.system.startTime, now)}`]}
		stats={[
			{ value: d.queue ? d.queue.count.toLocaleString() : null, label: 'In queue', href: '/activity', tone: d.queue?.errors ? 'bad' : d.queue?.warnings ? 'warn' : undefined },
			{ value: d.missing?.toLocaleString() ?? null, label: 'Missing', onclick: () => showWanted('missing'), tone: d.missing ? 'info' : undefined },
			{ value: d.cutoff?.toLocaleString() ?? null, label: 'Cutoff unmet', onclick: () => showWanted('cutoff') }
		]}
	/>

	<ServiceTabs items={TABS} bind:tab {notices} stale={ov.state.error}>
		{#if tab === 'overview'}
			<div class="cards">
				{#if isAdmin || d.commands.length}
					<Card title="Tasks" icon={iTasks}>
						{#if isAdmin}
							<div class="actions">
								{#each ACTION_ORDER as c (c)}
									<Button variant="tonal" disabled={blocked(c)} onclick={() => trigger(c)}><Icon icon={ACTIONS[c].icon} />{ACTIONS[c].label}</Button>
								{/each}
							</div>
						{/if}
						{#if d.commands.length}
							<ul class="tasks">
								{#each d.commands as c (c.id)}
									<li>
										<div class="task-line">
											<span class="task-name">{c.name}</span>
											<span class="task-st st-{c.status}">{taskStatus(c.status)}{c.started && !isRunning(c.status) ? ` · ${since(c.ended ?? c.started, now)}` : ''}</span>
										</div>
										{#if isRunning(c.status)}<Indeterminate label={`${c.name} running`} />{/if}
									</li>
								{/each}
							</ul>
						{:else}
							<p class="muted small">No recent tasks.</p>
						{/if}
					</Card>
				{/if}

				<Card title="Storage" icon={iDisk}>
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
				</Card>
			</div>
		{:else if tab === 'wanted'}
			{@const count = wantedKind === 'missing' ? d.missing : d.cutoff}
			<div class="toolbar">
				<div class="chip-row wrap">
					<Chip variant="general" selected={wantedKind === 'missing'} onclick={() => ((wantedKind = 'missing'), (wantedPage = 1))}>
						Missing{d.missing !== null ? ` · ${d.missing.toLocaleString()}` : ''}
					</Chip>
					<Chip variant="general" selected={wantedKind === 'cutoff'} onclick={() => ((wantedKind = 'cutoff'), (wantedPage = 1))}>
						Cutoff unmet{d.cutoff !== null ? ` · ${d.cutoff.toLocaleString()}` : ''}
					</Chip>
				</div>
				{#if isAdmin && count}
					<Button variant="tonal" disabled={blocked(wantedKind)} onclick={() => trigger(wantedKind)}>
						<Icon icon={wantedKind === 'missing' ? iSearch : iUpgrade} />Search all
					</Button>
				{/if}
			</div>
			{#key `${wantedKind}:${wantedPage}`}
				<WantedList url={`${base}/wanted?kind=${wantedKind}&page=${wantedPage}`} kind={wantedKind} onpage={(p) => (wantedPage = p)} />
			{/key}
		{:else if tab === 'history'}
			<div class="toolbar">
				<div class="chip-row scroll" role="toolbar" aria-label="Filter history">
					{#each HISTORY_FILTERS as f (f.value)}
						<Chip variant="general" selected={historyFilter === f.value} onclick={() => ((historyFilter = f.value), (historyPage = 1))}>{f.label}</Chip>
					{/each}
				</div>
			</div>
			{#key `${historyFilter}:${historyPage}`}
				<HistoryList url={`${base}/history?event=${historyFilter}&page=${historyPage}`} filtered={historyFilter !== 'all'} onpage={(p) => (historyPage = p)} />
			{/key}
		{:else}
			<div class="cards">
				<Card title="System">
					<dl>
						<dt>Version</dt>
						<dd>{d.system.version}</dd>
						{#if d.system.startTime}
							<dt>Started</dt>
							<dd>{dateTime(d.system.startTime)}</dd>
						{/if}
						{#if d.system.branch}<dt>Branch</dt><dd>{d.system.branch}</dd>{/if}
						{#if d.system.os}<dt>OS</dt><dd>{d.system.os}{d.system.docker ? ' (Docker)' : ''}</dd>{/if}
						{#if d.system.runtime}<dt>Runtime</dt><dd>{d.system.runtime}</dd>{/if}
						{#if d.system.database}<dt>Database</dt><dd>{d.system.database}</dd>{/if}
					</dl>
				</Card>

				<Card title="Indexers">
					{#if d.indexers.length === 0}
						<p class="muted">No indexers — add them in {appName} or sync them from Prowlarr.</p>
					{:else}
						<ul class="plist">
							{#each d.indexers as x (x.id)}
								{@const off = !x.rss && !x.autoSearch && !x.interactiveSearch}
								<li>
									<div class="pl-line">
										<span class="pl-name">{x.name}</span>
										{#if x.disabledTill}
											<Badge tone="bad" title={x.lastFailure ? `Last failure ${dateTime(x.lastFailure)}` : undefined}>Failing</Badge>
										{:else}
											<Badge tone={ok(!off)}>{off ? 'Disabled' : 'Enabled'}</Badge>
										{/if}
									</div>
									<div class="pl-sub">
										{protocolLabel(x.protocol)}{x.implementation ? ` · ${x.implementation}` : ''} · priority {x.priority}
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
				</Card>

				<Card title="Download clients">
					{#if d.downloadClients.length === 0}
						<p class="muted">No download clients.</p>
					{:else}
						<ul class="plist">
							{#each d.downloadClients as x (x.id)}
								<li>
									<div class="pl-line">
										<span class="pl-name">{x.name}</span>
										{#if x.disabledTill}
											<Badge tone="bad">Failing</Badge>
										{:else}
											<Badge tone={ok(x.enabled)}>{x.enabled ? 'Enabled' : 'Disabled'}</Badge>
										{/if}
									</div>
									<div class="pl-sub">{protocolLabel(x.protocol)}{x.implementation && x.implementation !== x.name ? ` · ${x.implementation}` : ''} · priority {x.priority}</div>
									{#if x.disabledTill}<div class="pl-sub bad-text">Paused until {until(x.disabledTill)} after failures</div>{/if}
								</li>
							{/each}
						</ul>
					{/if}
				</Card>

				<Card title="Quality profiles">
					{#if d.profiles.length === 0}
						<p class="muted">No quality profiles.</p>
					{:else}
						<ul class="plist">
							{#each d.profiles as p (p.id)}
								<li>
									<div class="pl-line">
										<span class="pl-name">{p.name}</span>
										<Badge tone={p.upgradeAllowed ? 'info' : 'off'}>{p.upgradeAllowed ? 'Upgrades on' : 'No upgrades'}</Badge>
									</div>
									{#if p.cutoff && p.upgradeAllowed}<div class="pl-sub">Upgrade until {p.cutoff}</div>{/if}
									<div class="pl-sub">{p.qualities.length ? p.qualities.join(', ') : 'No qualities allowed'}</div>
								</li>
							{/each}
						</ul>
					{/if}
				</Card>

				<Card title="Tags">
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
				</Card>
			</div>
		{/if}
	</ServiceTabs>
{/if}

<ConfirmDialog
	bind:open={confirmOpen}
	headline={pending ? (ACTIONS[pending].confirm ?? '') : ''}
	confirm={pending ? ACTIONS[pending].label : 'Run'}
	onconfirm={() => (pending ? start(pending) : Promise.resolve(null))}
>
	{#if pending}<p>{ACTIONS[pending].body}</p>{/if}
</ConfirmDialog>

<style>
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
	}
	p {
		margin: 0;
	}
	.muted {
		color: var(--m3c-on-surface-variant);
		font-size: 0.875rem;
	}
	.small {
		font-size: 0.8125rem;
	}

	/* system */
	dl {
		display: grid;
		grid-template-columns: max-content minmax(0, 1fr);
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

	/* tasks */
	.actions {
		display: flex;
		flex-wrap: wrap;
		gap: 0.5rem;
	}
	.tasks {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.5rem;
		font-size: 0.875rem;
	}
	.tasks li {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.375rem;
	}
	.task-line {
		display: flex;
		justify-content: space-between;
		gap: 0.75rem;
		min-width: 0;
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
	.disk-line {
		display: flex;
		justify-content: space-between;
		gap: 0.5rem;
		font-size: 0.875rem;
		margin-bottom: 0.375rem;
		min-width: 0;
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

	/* setup */
	.plist {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.875rem;
	}
	.pl-line {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 0.5rem;
		min-width: 0;
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
</style>
