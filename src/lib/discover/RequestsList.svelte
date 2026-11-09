<script lang="ts">
	import { Button, Dialog, Icon, LoadingIndicator, snackbar } from 'm3-svelte';
	import { live } from '$lib/live.svelte';
	import { ago } from '$lib/format';
	import Poster from '$lib/widgets/Poster.svelte';
	import { iCheck, iDecline, iDelete } from '$lib/icons';
	import { AVAILABILITY_LABEL, REQUEST_LABEL, detailHref, post, uniq } from './client';
	import type { RequestAction, RequestFilter, RequestItem, RequestsResult } from './types';

	/** Re-mounted by the parent ({#key}) when the filter changes. */
	let { filter, isAdmin }: { filter: RequestFilter; isAdmin: boolean } = $props();

	// svelte-ignore state_referenced_locally
	const base = `/api/discover/requests?filter=${filter}`;
	const q = live<RequestsResult>(`${base}&page=1`, 30_000);
	let more = $state<RequestsResult | null>(null);
	let page = $state(1);
	let loadingMore = $state(false);

	const data = $derived(more ?? q.state.data);
	const items = $derived(uniq(data?.items ?? []));

	async function loadMore(p = page + 1) {
		loadingMore = true;
		try {
			const r = await fetch(`${base}&page=${p}`);
			const body = await r.json().catch(() => ({}));
			if (!r.ok) throw new Error(body?.error ?? `HTTP ${r.status}`);
			more = body as RequestsResult;
			page = p;
		} catch (e) {
			snackbar(e instanceof Error ? e.message : String(e), {}, true);
		} finally {
			loadingMore = false;
		}
	}

	async function refresh() {
		await q.refresh();
		if (page > 1) await loadMore(page);
	}

	let busy = $state<string | null>(null);
	let confirm = $state<{ item: RequestItem; action: 'decline' | 'delete' } | null>(null);
	let confirmOpen = $state(false);

	async function run(item: RequestItem, action: RequestAction['action']) {
		busy = item.key;
		const err = await post('/api/discover/requests/action', { serviceId: item.serviceId, requestId: item.id, action } satisfies RequestAction);
		busy = null;
		confirmOpen = false;
		const done = { approve: 'Approved', decline: 'Declined', delete: 'Deleted request for' }[action];
		snackbar(err ?? `${done} ${item.title}`, {}, true);
		refresh();
	}

	function ask(item: RequestItem, action: 'decline' | 'delete') {
		confirm = { item, action };
		confirmOpen = true;
	}

	const seasonsLabel = (s: number[]) => (s.length ? (s.length > 4 ? `${s.length} seasons` : s.map((n) => `S${n}`).join(', ')) : null);
</script>

{#if !data}
	{#if q.state.error}
		<p class="error" role="alert">{q.state.error}</p>
	{:else}
		<div class="center"><LoadingIndicator aria-label="Loading requests" /></div>
	{/if}
{:else}
	{#if data.errors.length}
		<p class="error">Couldn't reach {data.errors.map((e) => e.service).join(', ')}: {data.errors[0].error}</p>
	{/if}
	{#if items.length === 0}
		<p class="empty">{filter === 'pending' ? 'No requests waiting for approval — all caught up.' : 'No requests here.'}</p>
	{:else}
		<ul class="list">
			{#each items as r (r.key)}
				<li class="row" class:wrap={isAdmin && r.status === 'pending'}>
					<a class="art" href={detailHref(r)} aria-label={`Open ${r.title}`}><Poster src={r.poster} width={48} alt="" /></a>
					<div class="body">
						<a class="title" href={detailHref(r)}>{r.title}{r.year ? ` (${r.year})` : ''}</a>
						<div class="chips">
							<span class="chip st-{r.status}">{REQUEST_LABEL[r.status]}</span>
							{#if r.media === 'available' || r.media === 'partial'}<span class="chip subtle">{AVAILABILITY_LABEL[r.media]}</span>{/if}
							<span class="chip subtle">{r.mediaType === 'movie' ? 'Movie' : 'Series'}</span>
							{#if r.is4k}<span class="chip uhd">4K</span>{/if}
							{#if seasonsLabel(r.seasons)}<span class="chip subtle">{seasonsLabel(r.seasons)}</span>{/if}
						</div>
						<div class="sub">
							{r.requestedBy} · {ago(r.createdAt)}{r.modifiedBy && r.status !== 'pending' ? ` · by ${r.modifiedBy}` : ''}
							{#if r.serviceName}<span class="svc"> · {r.serviceName}</span>{/if}
						</div>
					</div>
					{#if isAdmin}
						<div class="actions">
							{#if r.status === 'pending'}
								<Button variant="tonal" iconType="full" aria-label={`Approve ${r.title}`} disabled={busy === r.key} onclick={() => run(r, 'approve')}
									><Icon icon={iCheck} /></Button
								>
								<Button variant="text" iconType="full" aria-label={`Decline ${r.title}`} disabled={busy === r.key} onclick={() => ask(r, 'decline')}
									><Icon icon={iDecline} /></Button
								>
							{/if}
							<Button variant="text" iconType="full" aria-label={`Delete request for ${r.title}`} disabled={busy === r.key} onclick={() => ask(r, 'delete')}
								><Icon icon={iDelete} /></Button
							>
						</div>
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

<Dialog headline={confirm?.action === 'delete' ? 'Delete this request?' : 'Decline this request?'} bind:open={confirmOpen}>
	<p class="dlg">
		{confirm?.item.title}{confirm?.item.is4k ? ' (4K)' : ''} — requested by {confirm?.item.requestedBy}.
		{#if confirm?.action === 'delete'}
			The request is removed from {confirm?.item.serviceName}. Anything already downloaded stays.
		{:else}
			The requester will see it as declined.
		{/if}
	</p>
	{#snippet buttons()}
		<Button variant="text" onclick={() => (confirmOpen = false)}>Cancel</Button>
		<Button variant="filled" disabled={!confirm || busy === confirm.item.key} onclick={() => confirm && run(confirm.item, confirm.action)}>
			{confirm?.action === 'delete' ? 'Delete' : 'Decline'}
		</Button>
	{/snippet}
</Dialog>

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
	.empty {
		color: var(--m3c-on-surface-variant);
	}
	.list {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		gap: 0.5rem;
	}
	.row {
		display: flex;
		gap: 0.875rem;
		align-items: center;
		padding: 0.75rem;
		border-radius: var(--radius-l, 1rem);
		background: var(--m3c-surface-container-low);
	}
	.art {
		display: flex;
		align-self: flex-start;
	}
	.body {
		flex: 1;
		min-width: 0;
		display: grid;
		gap: 0.25rem;
	}
	.title {
		color: inherit;
		text-decoration: none;
		font-weight: 500;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.title:hover {
		text-decoration: underline;
	}
	.chips {
		display: flex;
		flex-wrap: wrap;
		gap: 0.25rem;
	}
	.chip {
		display: inline-flex;
		align-items: center;
		height: 1.375rem;
		padding: 0 0.5rem;
		border-radius: 0.375rem;
		font-size: 0.75rem;
		font-weight: 500;
		background: var(--m3c-secondary-container);
		color: var(--m3c-on-secondary-container);
	}
	.chip.subtle {
		background: transparent;
		box-shadow: inset 0 0 0 1px var(--m3c-outline-variant);
		color: var(--m3c-on-surface-variant);
	}
	.chip.uhd {
		background: var(--m3c-inverse-surface);
		color: var(--m3c-inverse-on-surface);
	}
	.chip.st-pending {
		background: var(--m3c-tertiary-container);
		color: var(--m3c-on-tertiary-container);
	}
	.chip.st-approved,
	.chip.st-completed {
		background: var(--m3c-primary-container);
		color: var(--m3c-on-primary-container);
	}
	.chip.st-declined,
	.chip.st-failed {
		background: var(--m3c-error-container);
		color: var(--m3c-on-error-container);
	}
	.sub {
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.actions {
		display: flex;
		gap: 0.125rem;
		flex: none;
	}
	@media (width < 34rem) {
		.row.wrap {
			flex-wrap: wrap;
		}
		.svc {
			display: none;
		}
		.wrap .actions {
			width: 100%;
			justify-content: flex-end;
			margin-top: -0.75rem;
		}
	}
	.dlg {
		margin: 0;
	}
</style>
