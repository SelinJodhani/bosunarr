<script lang="ts" module>
	import type { RequestFilter, RequestsResult } from './types';
	/** First pages of the other filters, fetched in the background so switching filters paints at once. */
	const prefetched = new Map<string, RequestsResult>();
	const ALL_FILTERS: RequestFilter[] = ['pending', 'approved', 'available', 'failed', 'all'];
	const firstPage = (f: RequestFilter) => `/api/discover/requests?filter=${f}&page=1`;
</script>

<script lang="ts">
	import { getJSON } from '$lib/http';
	import { Button, Icon, snackbar } from 'm3-svelte';
	import { live } from '$lib/live.svelte';
	import { ago } from '$lib/format';
	import Poster from '$lib/widgets/Poster.svelte';
	import { iCheck, iDecline, iDelete, iEmpty } from '$lib/icons';
	import { iDoneAll, iMore } from './icons';
	import { AVAILABILITY_LABEL, AVAILABILITY_TONE, REQUEST_LABEL, REQUEST_TONE, copyLabel, detailHref, post, uniq, type Tone } from './client';
	import Empty from './Empty.svelte';
	import RequestConfirm from './RequestConfirm.svelte';
	import StatusBadge from './StatusBadge.svelte';
	import { backClose } from './backClose.svelte';
	import type { ConfirmTarget, RequestAction, RequestItem } from './types';

	/** Re-mounted by the parent ({#key}) when the filter changes. */
	let { filter, isAdmin }: { filter: RequestFilter; isAdmin: boolean } = $props();

	// svelte-ignore state_referenced_locally
	const base = `/api/discover/requests?filter=${filter}`;
	const q = live<RequestsResult>(`${base}&page=1`, 30_000);
	let more = $state<RequestsResult | null>(null);
	let page = $state(1);
	let loadingMore = $state(false);

	// svelte-ignore state_referenced_locally
	const warm = prefetched.get(firstPage(filter));
	const data = $derived(more ?? q.state.data ?? warm);
	const items = $derived(uniq(data?.items ?? []));

	// Once this filter has loaded, quietly fetch the others' first pages.
	let warmed = false;
	$effect(() => {
		if (!q.state.data || warmed) return;
		warmed = true;
		const t = setTimeout(() => {
			for (const f of ALL_FILTERS) {
				const url = firstPage(f);
				if (f === filter) continue;
				getJSON<RequestsResult>(url)
					.then((r) => prefetched.set(url, r))
					.catch(() => {});
			}
		}, 300);
		return () => clearTimeout(t);
	});

	async function loadMore(p = page + 1) {
		loadingMore = true;
		try {
			more = await getJSON<RequestsResult>(`${base}&page=${p}`);
			page = p;
		} catch (e) {
			snackbar(e instanceof Error ? e.message : String(e), {}, true);
		} finally {
			loadingMore = false;
		}
	}

	async function refresh() {
		prefetched.clear();
		warmed = false;
		await q.refresh();
		if (page > 1) await loadMore(page);
	}

	let busy = $state<string | null>(null);
	let confirmFor = $state<{ item: RequestItem; target: ConfirmTarget } | null>(null);
	let confirmOpen = $state(false);
	let confirmError = $state<string | null>(null);

	async function run(item: RequestItem, action: RequestAction['action']) {
		busy = item.key;
		const err = await post('/api/discover/requests/action', { serviceId: item.serviceId, requestId: item.id, action } satisfies RequestAction);
		busy = null;
		if (err) {
			// While the confirmation is open the error belongs in it (dialogs sit above snackbars).
			if (confirmOpen) confirmError = err;
			else snackbar(err, {}, true);
			return;
		}
		confirmOpen = false;
		const done = { approve: 'Approved', decline: 'Declined', delete: 'Deleted request for' }[action];
		snackbar(`${done} ${item.title}`, {}, true);
		refresh();
	}

	function ask(item: RequestItem, action: 'decline' | 'delete') {
		closeMenu();
		confirmFor = { item, target: { action, title: item.title, is4k: item.is4k, requestedBy: item.requestedBy, serviceName: item.serviceName } };
		confirmError = null;
		confirmOpen = true;
	}

	/* Overflow menu (one shared popover, placed under the button that opened it). */
	let menuEl = $state<HTMLElement>();
	let menuFor = $state<RequestItem | null>(null);
	let menuPos = $state({ top: 0, right: 0 });
	function openMenu(e: MouseEvent, item: RequestItem) {
		const r = (e.currentTarget as HTMLElement).getBoundingClientRect();
		menuFor = item;
		menuPos = { top: Math.min(r.bottom + 4, window.innerHeight - 64), right: Math.max(8, window.innerWidth - r.right) };
		menuEl?.showPopover();
	}
	// System back closes the menu rather than leaving the page.
	const menuBack = backClose('requestMenu', () => closeMenu());
	function onMenuToggle(e: ToggleEvent) {
		if (e.newState === 'open') menuBack.opened();
		else menuBack.closed();
	}
	function closeMenu() {
		try {
			menuEl?.hidePopover();
		} catch {
			/* already closed */
		}
	}

	/** One status per row: what's in the library wins over the request's own state, and nothing that just repeats the active filter. */
	function statusOf(r: RequestItem): { tone: Tone; label: string } | null {
		const media = r.media === 'available' || r.media === 'partial' ? r.media : null;
		if (media) return filter === 'available' ? null : { tone: AVAILABILITY_TONE[media]!, label: AVAILABILITY_LABEL[media] };
		// An HD request for a title that's on disk in the 4K library: say so rather than "Approved".
		const other = !r.is4k && (r.otherMedia === 'available' || r.otherMedia === 'partial') ? r.otherMedia : null;
		if (other && r.status !== 'declined') return { tone: AVAILABILITY_TONE[other]!, label: copyLabel(other, true) };
		if (r.status === filter) return null;
		return { tone: REQUEST_TONE[r.status], label: REQUEST_LABEL[r.status] };
	}

	const seasonsLabel = (s: number[]) => (s.length ? (s.length > 4 ? `${s.length} seasons` : s.map((n) => `S${n}`).join(', ')) : null);
	function subLine(r: RequestItem) {
		const parts = [r.mediaType === 'movie' ? 'Movie' : 'Series'];
		const seasons = seasonsLabel(r.seasons);
		if (seasons) parts.push(seasons);
		let who = `${r.requestedBy} · ${ago(r.createdAt)}`;
		if (r.modifiedBy && (r.status === 'approved' || r.status === 'declined') && r.modifiedBy !== r.requestedBy) who += ` · ${REQUEST_LABEL[r.status].toLowerCase()} by ${r.modifiedBy}`;
		return { what: parts.join(' · '), who };
	}
</script>

{#if !data}
	{#if q.state.error}
		<p class="error" role="alert">{q.state.error}</p>
	{:else}
		<ul class="list" aria-busy="true" aria-label="Loading requests">
			{#each { length: 4 } as _, i (i)}
				<li class="row sk-row" aria-hidden="true">
					<span class="sk sk-art"></span>
					<span class="body"><span class="sk sk-line"></span><span class="sk sk-line short"></span><span class="sk sk-line shorter"></span></span>
				</li>
			{/each}
		</ul>
	{/if}
{:else}
	{#if data.errors.length}
		<p class="error">Couldn't reach {data.errors.map((e) => e.service).join(', ')}: {data.errors[0].error}</p>
	{/if}
	{#if items.length === 0}
		{#if filter === 'pending'}
			<Empty icon={iDoneAll} title="All caught up" hint="No requests are waiting for approval." />
		{:else}
			<Empty icon={iEmpty} title="No requests here" />
		{/if}
	{:else}
		<ul class="list">
			{#each items as r (r.key)}
				{@const st = statusOf(r)}
				{@const sub = subLine(r)}
				<li class="row" class:has-actions={isAdmin && r.status === 'pending'}>
					<span class="art"><Poster src={r.poster} width={48} alt="" /></span>
					<div class="body">
						<a class="title" href={detailHref(r)}>{r.title}{r.year ? ` (${r.year})` : ''}</a>
						{#if st || r.is4k}
							<div class="chips">
								{#if st}<StatusBadge tone={st.tone} label={st.label} />{/if}
								{#if r.is4k}<StatusBadge tone="partial" label="4K" />{/if}
							</div>
						{/if}
						<div class="sub">{sub.what}</div>
						<div class="sub">
							{sub.who}{#if r.serviceName}<span class="svc"> · {r.serviceName}</span>{/if}
						</div>
					</div>
					{#if isAdmin}
						<span class="menu-btn">
							<Button variant="text" iconType="full" aria-label={`More actions for ${r.title}`} aria-haspopup="menu" onclick={(e: MouseEvent) => openMenu(e, r)}
								><Icon icon={iMore} /></Button
							>
						</span>
						{#if r.status === 'pending'}
							<div class="actions">
								<Button variant="text" data-danger disabled={busy === r.key} onclick={() => ask(r, 'decline')}><Icon icon={iDecline} /> Decline</Button>
								<Button variant="tonal" disabled={busy === r.key} onclick={() => run(r, 'approve')}><Icon icon={iCheck} /> Approve</Button>
							</div>
						{/if}
					{/if}
				</li>
			{/each}
		</ul>
		{#if data.hasMore && page < 10}
			<div class="center">
				<Button variant="tonal" disabled={loadingMore} onclick={() => loadMore()}>{loadingMore ? 'Loading…' : 'Load more'}</Button>
			</div>
		{/if}
	{/if}
{/if}

{#if isAdmin}
	<div bind:this={menuEl} popover="auto" class="menu" role="menu" ontoggle={onMenuToggle} style:top="{menuPos.top}px" style:right="{menuPos.right}px">
		<button role="menuitem" class="item danger" disabled={!menuFor} onclick={() => menuFor && ask(menuFor, 'delete')}>
			<Icon icon={iDelete} /> Delete request
		</button>
	</div>
	<RequestConfirm
		bind:open={confirmOpen}
		target={confirmFor?.target ?? null}
		busy={!!confirmFor && busy === confirmFor.item.key}
		error={confirmError}
		onconfirm={() => confirmFor && run(confirmFor.item, confirmFor.target.action)}
	/>
{/if}

<style>
	.center {
		display: grid;
		place-items: center;
		padding: 1.5rem 0;
	}
	.error {
		color: var(--m3c-error);
		font-size: 0.875rem;
	}
	.list {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		gap: 0.5rem;
	}
	.row {
		position: relative;
		display: grid;
		grid-template-columns: 48px minmax(0, 1fr) auto;
		grid-template-areas:
			'art body menu'
			'acts acts acts';
		column-gap: 0.875rem;
		align-items: start;
		padding: 0.75rem 0.25rem 0.75rem 0.75rem;
		border-radius: var(--radius-l, 1rem);
		background: var(--m3c-surface-container-low);
	}
	.art {
		grid-area: art;
		display: flex;
	}
	.body {
		grid-area: body;
		min-width: 0;
		display: grid;
		gap: 0.25rem;
		align-content: start;
	}
	.title {
		color: inherit;
		text-decoration: none;
		font-weight: 500;
		line-height: 1.25rem;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	/* The whole card opens the detail page (≥ 40 px target); buttons sit above it. */
	.title::after {
		content: '';
		position: absolute;
		inset: 0;
		border-radius: inherit;
	}
	.title:focus-visible {
		outline: none;
	}
	.title:focus-visible::after {
		outline: 2px solid var(--m3c-primary);
		outline-offset: -2px;
	}
	@media (hover: hover) {
		.row:has(.title:hover) {
			background: var(--m3c-surface-container);
		}
	}
	.menu-btn,
	.actions {
		position: relative;
		z-index: 1;
	}
	.menu-btn {
		grid-area: menu;
		margin-top: -0.25rem;
	}
	.chips {
		display: flex;
		flex-wrap: wrap;
		gap: 0.25rem;
	}
	.sub {
		font-size: 0.75rem;
		line-height: 1rem;
		color: var(--m3c-on-surface-variant);
		overflow-wrap: anywhere;
	}
	.actions {
		grid-area: acts;
		display: flex;
		justify-content: flex-end;
		gap: 0.5rem;
		margin-top: 0.5rem;
		padding-right: 0.5rem;
	}
	/* Wide screens: actions sit inline, so every row is one line of controls. */
	@media (width >= 52.5rem) {
		.row {
			grid-template-columns: 48px minmax(0, 1fr) auto auto;
			grid-template-areas: 'art body acts menu';
			align-items: center;
		}
		.actions {
			margin-top: 0;
			padding-right: 0.25rem;
		}
		.menu-btn {
			margin-top: 0;
		}
	}
	@media (width < 34rem) {
		.svc {
			display: none;
		}
	}
	.menu {
		position: fixed;
		inset: auto;
		margin: 0;
		padding: 0.5rem 0;
		border: none;
		border-radius: var(--m3-shape-extra-small, 0.25rem);
		min-width: 11rem;
		background: var(--m3c-surface-container);
		color: var(--m3c-on-surface);
		box-shadow: var(--m3-elevation-2);
	}
	.item {
		all: unset;
		box-sizing: border-box;
		display: flex;
		align-items: center;
		gap: 0.75rem;
		width: 100%;
		min-height: 3rem;
		padding: 0 1rem;
		cursor: pointer;
		font-size: 0.875rem;
	}
	.item:hover,
	.item:focus-visible {
		background: color-mix(in oklab, var(--m3c-on-surface) 8%, transparent);
	}
	.item.danger {
		color: var(--m3c-error);
	}
	/* Skeleton rows: same box as a real row. */
	.sk-row {
		grid-template-areas: 'art body';
		grid-template-columns: 48px minmax(0, 1fr);
	}
	.sk {
		display: block;
		border-radius: 0.375rem;
		background: var(--m3c-surface-container-highest);
		animation: pulse 1.2s ease-in-out infinite alternate;
	}
	.sk-art {
		grid-area: art;
		width: 48px;
		height: 72px;
		border-radius: 0.5rem;
	}
	.sk-line {
		height: 0.875rem;
		width: 70%;
		margin: 0.1875rem 0;
	}
	.sk-line.short {
		width: 35%;
		height: 1.375rem;
	}
	.sk-line.shorter {
		width: 50%;
		height: 0.75rem;
	}
	@keyframes pulse {
		to {
			opacity: 0.5;
		}
	}
	/* app.css's [data-danger] ties with m3-svelte's own variant rules on specificity and loses on
	   load order; repeat it with the scope class until the shared rule is fixed. */
	.actions :global(button.m3-container.text[data-danger]:not(:disabled)) {
		color: var(--m3c-error);
	}
</style>
