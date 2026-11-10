<script lang="ts" module>
	type Filter = 'all' | 'downloading' | 'seeding' | 'completed' | 'paused' | 'active' | 'errored';
	type Sort = 'added' | 'name' | 'progress' | 'dlspeed' | 'upspeed' | 'size' | 'eta' | 'ratio';
	// Survives leaving and returning to the page.
	const view = $state({ q: '', filter: 'all' as Filter, sort: 'added' as Sort });
</script>

<script lang="ts">
	import SortMenu from '$lib/components/SortMenu.svelte';
	import { BottomSheet, Button, Checkbox, Chip, Dialog, Icon, LinearProgress, TextField, snackbar } from 'm3-svelte';
	import { bytes, eta, speed } from '$lib/format';
	import { iAnnounce, iClose, iDelete, iDown, iPause, iRecheck, iResume, iSearch, iUp } from '$lib/icons';
	import { act } from './live.svelte';
	import type { Torrent, TorrentAction, TorrentGroup } from './types';

	let { torrents, isAdmin, showClient }: { torrents: Torrent[]; isAdmin: boolean; showClient: (id: number) => string } = $props();

	const GROUP_LABEL: Record<TorrentGroup, string> = {
		downloading: 'Downloading',
		seeding: 'Seeding',
		paused: 'Paused',
		completed: 'Completed',
		queued: 'Queued',
		checking: 'Checking',
		stalled: 'Stalled',
		errored: 'Error'
	};
	const isPaused = (t: Torrent) => t.group === 'paused' || t.group === 'completed' || /^(paused|stopped)/.test(t.state);

	const FILTERS: Array<{ value: Filter; label: string; test: (t: Torrent) => boolean }> = [
		{ value: 'all', label: 'All', test: () => true },
		{ value: 'active', label: 'Active', test: (t) => t.dlspeed > 0 || t.upspeed > 0 },
		{ value: 'downloading', label: 'Downloading', test: (t) => t.progress < 1 && !isPaused(t) && t.group !== 'errored' },
		{ value: 'seeding', label: 'Seeding', test: (t) => t.group === 'seeding' },
		{ value: 'completed', label: 'Completed', test: (t) => t.progress >= 1 },
		{ value: 'paused', label: 'Paused', test: isPaused },
		{ value: 'errored', label: 'Errored', test: (t) => t.group === 'errored' }
	];
	const SORTS = [
		{ value: 'added', text: 'Recently added' },
		{ value: 'name', text: 'Name' },
		{ value: 'progress', text: 'Progress' },
		{ value: 'dlspeed', text: 'Download speed' },
		{ value: 'upspeed', text: 'Upload speed' },
		{ value: 'size', text: 'Size' },
		{ value: 'eta', text: 'Time left' },
		{ value: 'ratio', text: 'Ratio' }
	];

	const counts = $derived(Object.fromEntries(FILTERS.map((f) => [f.value, torrents.filter(f.test).length])) as Record<Filter, number>);

	const list = $derived.by(() => {
		const f = FILTERS.find((x) => x.value === view.filter) ?? FILTERS[0];
		const needle = view.q.trim().toLowerCase();
		const out = torrents.filter((t) => f.test(t) && (!needle || t.name.toLowerCase().includes(needle) || t.category.toLowerCase() === needle));
		const etaKey = (t: Torrent) => (t.progress >= 1 || t.eta <= 0 || t.eta >= 8640000 ? Infinity : t.eta);
		const by: Record<Sort, (a: Torrent, b: Torrent) => number> = {
			added: (a, b) => b.addedOn - a.addedOn,
			name: (a, b) => a.name.localeCompare(b.name),
			progress: (a, b) => a.progress - b.progress,
			dlspeed: (a, b) => b.dlspeed - a.dlspeed,
			upspeed: (a, b) => b.upspeed - a.upspeed,
			size: (a, b) => b.size - a.size,
			eta: (a, b) => etaKey(a) - etaKey(b),
			ratio: (a, b) => b.ratio - a.ratio
		};
		return out.sort(by[view.sort]);
	});

	/* Details sheet — looks the torrent up in the live list so its numbers keep updating. */
	let openId = $state<string | null>(null);
	const open = $derived(openId ? torrents.find((t) => t.id === openId) : undefined);
	let busy = $state(false);
	let confirmOpen = $state(false);
	let deleteFiles = $state(false);

	async function run(action: TorrentAction, t: Torrent, label: string) {
		busy = true;
		const err = await act({ action, ids: [t.id], deleteFiles });
		busy = false;
		snackbar(err ?? label, {}, true);
		if (!err && action === 'delete') {
			confirmOpen = false;
			openId = null;
		}
	}
	const fmtDate = (s: number) => (s ? new Date(s * 1000).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }) : '—');
</script>

<div class="toolbar">
	<div class="search">
		<TextField
			label="Search torrents"
			leadingIcon={iSearch}
			type="search"
			autocomplete="off"
			bind:value={view.q}
			trailing={view.q ? { icon: iClose, onclick: () => (view.q = ''), 'aria-label': 'Clear search' } : undefined}
		/>
	</div>
	<SortMenu options={SORTS} bind:value={view.sort} />
</div>

<div class="chips" role="toolbar" aria-label="Filter torrents">
	{#each FILTERS as f (f.value)}
		{#if f.value === 'all' || counts[f.value] > 0 || view.filter === f.value}
			<Chip variant="general" selected={view.filter === f.value} onclick={() => (view.filter = f.value)}>{f.label} {counts[f.value]}</Chip>
		{/if}
	{/each}
</div>

{#if list.length === 0}
	<p class="empty">{torrents.length ? 'No torrents match' : 'No torrents'}</p>
{:else}
	<ul class="list">
		{#each list as t (t.id)}
			<li>
				<button class="row g-{t.group}" onclick={() => (openId = t.id)} aria-haspopup="dialog">
					<span class="name">{t.name}</span>
					<span class="bar"><LinearProgress percent={t.progress * 100} aria-label={`${t.name} progress`} /></span>
					<span class="meta">
						<span class="state">{GROUP_LABEL[t.group]}</span>
						<span>{t.progress >= 1 ? bytes(t.size) : `${(t.progress * 100).toFixed(1)}% of ${bytes(t.size)}`}</span>
						{#if t.dlspeed > 0}<span class="spd"><Icon icon={iDown} size={14} />{speed(t.dlspeed)}</span>{/if}
						{#if t.upspeed > 0}<span class="spd"><Icon icon={iUp} size={14} />{speed(t.upspeed)}</span>{/if}
						{#if t.progress < 1 && t.dlspeed > 0}<span>{eta(t.eta)}</span>{/if}
						{#if t.category}<span class="cat">{t.category}</span>{/if}
					</span>
				</button>
			</li>
		{/each}
	</ul>
{/if}

{#if open}
	<BottomSheet close={() => (openId = null)}>
		<div class="sheet">
			<h2 class="sheet-title">{open.name}</h2>
			<p class="sheet-sub">{GROUP_LABEL[open.group]} · {showClient(open.serviceId)}</p>
			<div class="bar"><LinearProgress percent={open.progress * 100} aria-label="Progress" /></div>
			<dl>
				<dt>Progress</dt><dd>{(open.progress * 100).toFixed(1)}% of {bytes(open.size)}</dd>
				<dt>Speed</dt><dd>↓ {speed(open.dlspeed)} · ↑ {speed(open.upspeed)}</dd>
				{#if open.progress < 1 && !isPaused(open)}<dt>Time left</dt><dd>{open.dlspeed > 0 ? eta(open.eta) : '∞'}</dd>{/if}
				<dt>Downloaded</dt><dd>{bytes(open.downloaded)}</dd>
				<dt>Uploaded</dt><dd>{bytes(open.uploaded)} · ratio {open.ratio.toFixed(2)}</dd>
				<dt>Seeds / peers</dt><dd>{open.seeds} / {open.peers}</dd>
				<dt>Added</dt><dd>{fmtDate(open.addedOn)}</dd>
				{#if open.completedOn}<dt>Completed</dt><dd>{fmtDate(open.completedOn)}</dd>{/if}
				{#if open.category}<dt>Category</dt><dd>{open.category}</dd>{/if}
				{#if open.tags.length}<dt>Tags</dt><dd>{open.tags.join(', ')}</dd>{/if}
				<dt>Saved to</dt><dd class="path">{open.savePath}</dd>
				<dt>State</dt><dd class="path">{open.state}</dd>
			</dl>
			{#if isAdmin}
				{@const t = open}
				<div class="actions">
					{#if isPaused(t)}
						<Button variant="filled" disabled={busy} onclick={() => run('resume', t, 'Resumed')}><Icon icon={iResume} /> Resume</Button>
					{:else}
						<Button variant="tonal" disabled={busy} onclick={() => run('pause', t, 'Paused')}><Icon icon={iPause} /> Pause</Button>
					{/if}
					<Button variant="tonal" disabled={busy} onclick={() => run('recheck', t, 'Recheck started')}><Icon icon={iRecheck} /> Recheck</Button>
					<Button variant="tonal" disabled={busy} onclick={() => run('reannounce', t, 'Reannounced to trackers')}><Icon icon={iAnnounce} /> Reannounce</Button>
					<Button
						variant="text"
						disabled={busy}
						onclick={() => {
							deleteFiles = false;
							confirmOpen = true;
						}}><Icon icon={iDelete} /> Delete</Button
					>
				</div>
			{/if}
		</div>
	</BottomSheet>
{/if}

<Dialog headline="Delete torrent?" bind:open={confirmOpen}>
	<p class="dlg-title">{open?.name}</p>
	<label class="check">
		<Checkbox><input type="checkbox" bind:checked={deleteFiles} /></Checkbox>
		<span>Also delete the downloaded files</span>
	</label>
	{#if deleteFiles}<p class="warn">Files are removed from disk. This can't be undone.</p>{/if}
	{#snippet buttons()}
		<Button variant="text" onclick={() => (confirmOpen = false)}>Cancel</Button>
		<Button variant="filled" disabled={busy || !open} onclick={() => open && run('delete', open, deleteFiles ? 'Deleted torrent and files' : 'Deleted torrent')}>Delete</Button>
	{/snippet}
</Dialog>

<style>
	.toolbar {
		display: flex;
		gap: 0.5rem;
		align-items: center;
	}
	.search {
		flex: 1;
		min-width: 0;
	}
	.search :global(.m3-container) {
		width: 100%;
	}
	.chips {
		display: flex;
		gap: 0.5rem;
		overflow-x: auto;
		padding: 0.75rem 0;
		scrollbar-width: none;
	}
	.chips > :global(*) {
		flex: none;
	}
	.empty {
		color: var(--m3c-on-surface-variant);
	}
	.list {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		gap: 0.375rem;
	}
	.list li {
		content-visibility: auto;
		contain-intrinsic-size: auto 5rem;
	}
	.row {
		all: unset;
		box-sizing: border-box;
		cursor: pointer;
		width: 100%;
		display: grid;
		gap: 0.375rem;
		padding: 0.75rem 1rem;
		border-radius: var(--radius-m);
		background: var(--m3c-surface-container-low);
		transition: background-color 150ms;
	}
	.row:hover {
		background: var(--m3c-surface-container);
	}
	.row:focus-visible {
		outline: 2px solid var(--m3c-primary);
	}
	.name {
		font-size: 0.875rem;
		font-weight: 500;
		overflow-wrap: anywhere;
		display: -webkit-box;
		-webkit-line-clamp: 2;
		line-clamp: 2;
		-webkit-box-orient: vertical;
		overflow: hidden;
	}
	.meta {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 0.125rem 0.75rem;
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
	}
	.state {
		font-weight: 600;
		color: var(--m3c-primary);
	}
	.g-paused .state,
	.g-completed .state,
	.g-queued .state {
		color: var(--m3c-on-surface-variant);
	}
	.g-stalled .state,
	.g-checking .state {
		color: var(--m3c-tertiary);
	}
	.g-errored .state {
		color: var(--m3c-error);
	}
	.g-paused :global(.m3-container),
	.g-completed :global(.m3-container) {
		filter: grayscale(1);
		opacity: 0.7;
	}
	.spd {
		display: inline-flex;
		align-items: center;
		font-variant-numeric: tabular-nums;
	}
	.cat {
		margin-left: auto;
		padding: 0 0.375rem;
		border-radius: 0.375rem;
		background: var(--m3c-secondary-container);
		color: var(--m3c-on-secondary-container);
	}
	.sheet {
		padding: 0 1.5rem 1.5rem;
		max-width: 40rem;
		margin: 0 auto;
	}
	.sheet-title {
		font-size: 1.125rem;
		line-height: 1.4;
		overflow-wrap: anywhere;
	}
	.sheet-sub {
		margin: 0.25rem 0 0.75rem;
		color: var(--m3c-on-surface-variant);
		font-size: 0.875rem;
	}
	dl {
		display: grid;
		grid-template-columns: max-content minmax(0, 1fr);
		gap: 0.375rem 1rem;
		margin: 1rem 0;
		font-size: 0.875rem;
	}
	dt {
		color: var(--m3c-on-surface-variant);
	}
	dd {
		margin: 0;
		overflow-wrap: anywhere;
		font-variant-numeric: tabular-nums;
	}
	dd.path {
		font-family: ui-monospace, monospace;
		font-size: 0.75rem;
	}
	.actions {
		display: flex;
		flex-wrap: wrap;
		gap: 0.5rem;
	}
	.dlg-title {
		margin: 0 0 0.75rem;
		font-weight: 500;
		overflow-wrap: anywhere;
	}
	.check {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		cursor: pointer;
	}
	.warn {
		color: var(--m3c-error);
		font-size: 0.875rem;
		margin: 0.5rem 0 0;
	}
</style>
