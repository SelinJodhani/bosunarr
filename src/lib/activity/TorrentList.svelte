<script lang="ts" module>
	type Filter = 'all' | 'downloading' | 'seeding' | 'completed' | 'paused' | 'active' | 'errored';
	type Sort = 'added' | 'name' | 'progress' | 'dlspeed' | 'upspeed' | 'size' | 'eta' | 'ratio';
	// Survives leaving and returning to the page.
	const view = $state({ q: '', filter: 'all' as Filter, sort: 'added' as Sort });
</script>

<script lang="ts">
	import type { Snippet } from 'svelte';
	import SortMenu from '$lib/components/SortMenu.svelte';
	import { BottomSheet, Button, Checkbox, Chip, Dialog, Icon, LinearProgress, TextField, snackbar } from 'm3-svelte';
	import { bytes, eta, speed } from '$lib/format';
	import { iAdd, iAnnounce, iClose, iDelete, iDown, iEmpty, iPause, iRecheck, iResume, iSearch, iUp } from '$lib/icons';
	import { act } from './live.svelte';
	import { categoryLabel, isCheckingState, isStopped, torrentState } from './format';
	import Empty from './Empty.svelte';
	import type { Torrent, TorrentAction, TorrentGroup } from './types';
	import '$lib/servicepages/shared/chips.css';

	let {
		torrents,
		isAdmin,
		showClient,
		toolbar,
		onAdd,
		filter,
		onreset,
		chips
	}: {
		torrents: Torrent[];
		isAdmin: boolean;
		showClient: (id: number) => string;
		/** Extra controls for the end of the search row (e.g. an overflow menu). */
		toolbar?: Snippet;
		/** Shows an "Add torrent" action in the empty state. */
		onAdd?: () => void;
		/** Extra filter from the host page (e.g. a category); applied before search and the status chips. */
		filter?: (t: Torrent) => boolean;
		/** Called by "Show all" so the host can clear its own filter too. */
		onreset?: () => void;
		/** Chips placed before the status chips (e.g. the host's active category, removable). */
		chips?: Snippet;
	} = $props();

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
	const isPaused = (t: Torrent) => t.group === 'paused' || t.group === 'completed' || isStopped(t.state);

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

	// Search first, then count per filter: the chip numbers always match what tapping them would show.
	const base = $derived(filter ? torrents.filter(filter) : torrents);
	const searched = $derived.by(() => {
		const needle = view.q.trim().toLowerCase();
		if (!needle) return base;
		return base.filter((t) => t.name.toLowerCase().includes(needle) || t.category.toLowerCase() === needle);
	});
	const counts = $derived(Object.fromEntries(FILTERS.map((f) => [f.value, searched.filter(f.test).length])) as Record<Filter, number>);

	const list = $derived.by(() => {
		const f = FILTERS.find((x) => x.value === view.filter) ?? FILTERS[0];
		const out = searched.filter(f.test);
		const etaKey = (t: Torrent) => (t.progress >= 1 || t.eta <= 0 || t.eta >= 8640000 ? Infinity : t.eta);
		// Ties fall back to the hash so equal keys (0 B/s, same size…) never swap places between live updates.
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
		const cmp = by[view.sort];
		return out.sort((a, b) => cmp(a, b) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
	});

	/* Details sheet — looks the torrent up in the live list so its numbers keep updating. */
	let openId = $state<string | null>(null);
	const open = $derived(openId ? torrents.find((t) => t.id === openId) : undefined);
	/** Action in flight from the sheet. Pause/resume stay "busy" until the live state flips, so the button never flickers back. */
	let pending = $state<{ action: TorrentAction; id: string; from: string } | null>(null);
	let confirmOpen = $state(false);
	let deleting = $state(false);
	let deleteFiles = $state(false);

	$effect(() => {
		if (!pending || (pending.action !== 'pause' && pending.action !== 'resume')) return;
		const t = torrents.find((x) => x.id === pending!.id);
		if (!t || t.state !== pending.from) pending = null;
	});
	$effect(() => {
		if (!pending) return;
		const timer = setTimeout(() => (pending = null), 6_000); // never stay stuck if the client ignores us
		return () => clearTimeout(timer);
	});

	async function run(action: TorrentAction, t: Torrent, label: string) {
		pending = { action, id: t.id, from: t.state };
		const err = await act({ action, ids: [t.id] });
		if (err || (action !== 'pause' && action !== 'resume')) pending = null;
		snackbar(err ?? label, {}, true);
	}

	async function remove(t: Torrent) {
		deleting = true;
		const files = deleteFiles;
		const err = await act({ action: 'delete', ids: [t.id], deleteFiles: files });
		deleting = false;
		snackbar(err ?? (files ? 'Deleted torrent and files' : 'Deleted torrent'), {}, true);
		if (!err) {
			confirmOpen = false;
			openId = null;
		}
	}

	/** What the primary button does for this state. Completed torrents seed; the rest download. */
	function toggleFor(t: Torrent) {
		const done = t.progress >= 1;
		if (isPaused(t) || t.group === 'errored')
			return { action: 'resume' as const, icon: iResume, label: done ? 'Seed' : 'Resume', busy: done ? 'Starting…' : 'Resuming…', done: done ? 'Seeding started' : 'Resumed' };
		return { action: 'pause' as const, icon: iPause, label: done ? 'Stop' : 'Pause', busy: done ? 'Stopping…' : 'Pausing…', done: done ? 'Stopped seeding' : 'Paused' };
	}

	const fmtDate = (s: number) => (s ? new Date(s * 1000).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }) : '—');
	const pct = (p: number) => `${(Math.floor(p * 1000) / 10).toFixed(1)}%`;
	// Stops the m3 sheet from treating a scroll inside the content as a drag/resize of the sheet itself.
	const stop = (e: Event) => e.stopPropagation();
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
	{@render toolbar?.()}
</div>

{#if torrents.length}
	<div class="chip-row scroll" role="toolbar" aria-label="Filter torrents">
		{@render chips?.()}
		{#each FILTERS as f (f.value)}
			<Chip
				variant="general"
				selected={view.filter === f.value}
				disabled={f.value !== 'all' && counts[f.value] === 0 && view.filter !== f.value}
				onclick={() => (view.filter = f.value)}>{f.label}<span class="chip-n">{counts[f.value]}</span></Chip
			>
		{/each}
	</div>
{/if}

{#if list.length === 0}
	{#if torrents.length === 0}
		<Empty
			icon={iEmpty}
			title="No torrents"
			hint="Torrents added here or by Sonarr and Radarr show up in this list."
			action={isAdmin && onAdd ? { label: 'Add torrent', icon: iAdd, onclick: onAdd } : undefined}
		/>
	{:else}
		<Empty
			icon={iSearch}
			title="No torrents match"
			hint={view.q.trim() ? `Nothing ${view.filter === 'all' ? '' : `${FILTERS.find((f) => f.value === view.filter)?.label.toLowerCase()} `}matches “${view.q.trim()}”.` : 'Try another filter.'}
			action={{
				label: 'Show all',
				onclick: () => {
					view.q = '';
					view.filter = 'all';
					onreset?.();
				}
			}}
		/>
	{/if}
{:else}
	<ul class="list">
		{#each list as t (t.id)}
			{@const moving = t.dlspeed > 0 || t.upspeed > 0}
			<li>
				<button class="row g-{t.group}" onclick={() => (openId = t.id)} aria-haspopup="dialog">
					<span class="name">{t.name}</span>
					<span class="bar"><LinearProgress percent={t.progress * 100} aria-label={`${t.name} progress`} /></span>
					<span class="meta">
						<span class="left">
							<span class="state">{GROUP_LABEL[t.group]}</span>
							<span class="dot" aria-hidden="true">·</span>
							<span class="sz">{t.progress >= 1 ? bytes(t.size) : `${Math.floor(t.progress * 100)}% of ${bytes(t.size)}`}</span>
							{#if t.category}<span class="cat">{categoryLabel(t.category)}</span>{/if}
						</span>
						{#if moving}
							<span class="rates">
								{#if t.dlspeed > 0}<span class="spd"><Icon icon={iDown} size={14} />{speed(t.dlspeed)}</span>{/if}
								{#if t.upspeed > 0}<span class="spd"><Icon icon={iUp} size={14} />{speed(t.upspeed)}</span>{/if}
								{#if t.progress < 1 && t.dlspeed > 0 && t.eta > 0 && t.eta < 8640000}<span class="spd">{eta(t.eta)}</span>{/if}
							</span>
						{/if}
					</span>
				</button>
			</li>
		{/each}
	</ul>
{/if}

{#if open}
	{@const t = open}
	{@const toggle = toggleFor(t)}
	{@const checking = isCheckingState(t.state)}
	{@const busy = pending?.id === t.id ? pending.action : null}
	<BottomSheet close={() => (openId = null)}>
		<div class="sheet tsheet" ontouchstart={stop} onwheel={stop} role="presentation">
			<div class="sheet-body">
				<h2 class="sheet-title">{t.name}</h2>
				<p class="sheet-sub"><span class="state-{t.group}">{torrentState(t.state)}</span> · {showClient(t.serviceId)}</p>
				<div class="bar g-{t.group}"><LinearProgress percent={t.progress * 100} aria-label="Progress" /></div>
				<dl>
					<dt>Progress</dt><dd>{pct(t.progress)} of {bytes(t.size)}</dd>
					<dt>Speed</dt><dd>↓ {speed(t.dlspeed)} · ↑ {speed(t.upspeed)}</dd>
					{#if t.progress < 1 && !isPaused(t)}<dt>Time left</dt><dd>{t.dlspeed > 0 ? eta(t.eta) : '∞'}</dd>{/if}
					<dt>Downloaded</dt><dd>{bytes(t.downloaded)}</dd>
					<dt>Uploaded</dt><dd>{bytes(t.uploaded)} · ratio {t.ratio.toFixed(2)}</dd>
					<dt>Seeds / peers</dt><dd>{t.seeds} / {t.peers}</dd>
					<dt>Added</dt><dd>{fmtDate(t.addedOn)}</dd>
					{#if t.completedOn}<dt>Completed</dt><dd>{fmtDate(t.completedOn)}</dd>{/if}
					{#if t.category}<dt>Category</dt><dd>{categoryLabel(t.category)}</dd>{/if}
					{#if t.tags.length}<dt>Tags</dt><dd>{t.tags.join(', ')}</dd>{/if}
					<dt>Saved to</dt><dd class="path">{t.savePath}</dd>
				</dl>
			</div>
			{#if isAdmin}
				<div class="actions">
					<Button variant="filled" disabled={!!busy} onclick={() => run(toggle.action, t, toggle.done)}
						><Icon icon={toggle.icon} />{busy === toggle.action ? toggle.busy : toggle.label}</Button
					>
					<Button variant="tonal" disabled={!!busy || checking} onclick={() => run('recheck', t, 'Recheck started')}
						><Icon icon={iRecheck} />{busy === 'recheck' ? 'Rechecking…' : 'Recheck'}</Button
					>
					<Button variant="tonal" disabled={!!busy || isPaused(t) || checking} onclick={() => run('reannounce', t, 'Reannounced to trackers')}
						><Icon icon={iAnnounce} />{busy === 'reannounce' ? 'Announcing…' : 'Reannounce'}</Button
					>
					<Button
						variant="tonal"
						data-danger
						disabled={!!busy}
						onclick={() => {
							deleteFiles = false;
							confirmOpen = true;
						}}><Icon icon={iDelete} />Delete</Button
					>
				</div>
			{/if}
		</div>
	</BottomSheet>
{/if}

<Dialog headline="Delete torrent?" bind:open={confirmOpen}>
	<p class="dlg-title">{open?.name}</p>
	<label class="check">
		<Checkbox><input type="checkbox" bind:checked={deleteFiles} disabled={deleting} /></Checkbox>
		<span>Also delete the downloaded files</span>
	</label>
	<p class="hint" class:warn={deleteFiles}>{deleteFiles ? "Files are removed from disk. This can't be undone." : 'The downloaded files stay on disk.'}</p>
	{#snippet buttons()}
		<Button variant="text" disabled={deleting} onclick={() => (confirmOpen = false)}>Cancel</Button>
		<Button variant="filled" data-danger disabled={deleting || !open} onclick={() => open && remove(open)}>{deleting ? 'Deleting…' : 'Delete'}</Button>
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
		min-width: 0;
	}
	.chip-row {
		margin: 0.5rem 0;
	}
	.list {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.5rem;
	}
	.list li {
		min-width: 0;
		content-visibility: auto;
		contain-intrinsic-size: auto 5.5rem;
	}
	.row {
		all: unset;
		box-sizing: border-box;
		cursor: pointer;
		width: 100%;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.5rem;
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
		outline-offset: -2px;
	}
	.name {
		font-size: 0.875rem;
		line-height: 1.25rem;
		font-weight: 500;
		overflow-wrap: anywhere;
		display: -webkit-box;
		-webkit-line-clamp: 2;
		line-clamp: 2;
		-webkit-box-orient: vertical;
		overflow: hidden;
	}
	/* One fixed-height line: live numbers change without the row ever growing or wrapping. */
	.meta {
		display: flex;
		align-items: center;
		min-width: 0;
		gap: 0.75rem;
		height: 1.25rem;
		font-size: 0.75rem;
		line-height: 1rem;
		color: var(--m3c-on-surface-variant);
		white-space: nowrap;
	}
	.left {
		display: flex;
		align-items: center;
		gap: 0.375rem;
		flex: 1;
		min-width: 0;
		overflow: hidden;
	}
	.left > :global(*) {
		flex: none;
	}
	.left .sz {
		flex: 0 1 auto;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		font-variant-numeric: tabular-nums;
	}
	.left .cat {
		flex: 0 100 auto;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		margin-left: 0.25rem;
		padding: 0 0.375rem;
		border-radius: 0.375rem;
		background: var(--m3c-secondary-container);
		color: var(--m3c-on-secondary-container);
	}
	.rates {
		display: flex;
		gap: 0.5rem;
		flex: none;
		font-variant-numeric: tabular-nums;
	}
	.spd {
		display: inline-flex;
		align-items: center;
		gap: 0.125rem;
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

	/* Sheet: the m3 BottomSheet caps itself at 480 px and clips. The body scrolls inside it and the
	   actions stay pinned at the bottom, above the gesture bar. */
	.sheet {
		max-width: 40rem;
		margin: 0 auto;
		max-height: min(calc(480px - 3rem), calc(100dvh - 6rem));
		display: flex;
		flex-direction: column;
		touch-action: pan-y;
	}
	/* app.css caps every m3 dialog at 100vw - 3rem (for centred dialogs); a bottom sheet spans the phone's width. */
	:global(dialog.m3-container.m3-container:has(.tsheet)) {
		max-width: 40rem;
	}
	.sheet-body {
		scrollbar-width: thin;
		flex: 1 1 auto;
		min-height: 0;
		overflow-y: auto;
		overscroll-behavior: contain;
		padding: 0 0.5rem 0.5rem;
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
	.sheet-sub .state-errored {
		color: var(--m3c-error);
	}
	.sheet-sub .state-stalled,
	.sheet-sub .state-checking {
		color: var(--m3c-tertiary);
	}
	dl {
		display: grid;
		grid-template-columns: max-content minmax(0, 1fr);
		gap: 0.375rem 1rem;
		margin: 1rem 0 0;
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
		flex: none;
		display: grid;
		grid-template-columns: repeat(2, minmax(0, 1fr));
		gap: 0.5rem;
		padding: 0.75rem 0.5rem max(1rem, env(safe-area-inset-bottom));
		border-top: 1px solid var(--m3c-outline-variant);
	}
	.actions :global(button) {
		width: 100%;
		min-width: 0;
		white-space: nowrap;
	}
	@media (width >= 40rem) {
		.actions {
			grid-template-columns: repeat(4, minmax(0, 1fr));
		}
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
		min-height: 2.5rem;
		cursor: pointer;
	}
	.hint {
		color: var(--m3c-on-surface-variant);
		font-size: 0.875rem;
		margin: 0.25rem 0 0;
	}
	.hint.warn {
		color: var(--m3c-error);
	}
</style>
