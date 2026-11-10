<script lang="ts">
	import { live } from '$lib/live.svelte';
	import State from '$lib/widgets/State.svelte';
	import type { JsHistory } from './types';
	import { duration, method, when } from './format';

	let { url, searching, onpages }: { url: string; searching: boolean; onpages: (pages: number) => void } = $props();
	// Re-keyed by the parent per page/search, so `url` is fixed per instance. Not persisted: one key
	// per page/search would pile personal watch history up in localStorage.
	// svelte-ignore state_referenced_locally
	const q = live<JsHistory>(url, 30_000, { persist: false });
	const d = $derived(q.state.data && Array.isArray(q.state.data.rows) ? q.state.data : undefined);
	const rows = $derived(d?.rows ?? []);
	$effect(() => {
		if (d) onpages(d.pages);
	});
</script>

<State loading={q.state.loading} error={q.state.error} empty={rows.length === 0} emptyText={searching ? 'No plays match that search' : 'No plays recorded yet'}>
	<ul>
		{#each rows as r (r.id)}
			<li>
				<div class="main">
					<div class="title" title={r.title}>{r.title}</div>
					{#if r.subtitle}<div class="ep">{r.subtitle}</div>{/if}
					<div class="sub">
						<span class="user">{r.user}</span>
						{#if r.client || r.device}<span>{[r.client, r.device].filter(Boolean).join(' · ')}</span>{/if}
					</div>
				</div>
				<div class="side">
					<span class="dur">{duration(r.seconds)}{r.plays > 1 ? ` · ${r.plays}×` : ''}</span>
					{#if r.method}<span class="method" class:transcode={r.method === 'Transcode'}>{method(r.method)}</span>{/if}
					<time datetime={r.at ?? undefined}>{when(r.at)}</time>
				</div>
			</li>
		{/each}
	</ul>
</State>

<style>
	ul {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		border-radius: var(--radius-l);
		background: var(--m3c-surface-container-low);
		overflow: hidden;
	}
	li {
		display: grid;
		grid-template-columns: minmax(0, 1fr) auto;
		gap: 0.75rem;
		padding: 0.75rem 1rem;
	}
	li + li {
		border-top: 1px solid var(--m3c-outline-variant);
	}
	.main {
		min-width: 0;
		display: grid;
		gap: 0.125rem;
	}
	.title,
	.ep,
	.sub span {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.title {
		font-weight: 500;
	}
	.ep,
	.sub {
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
	}
	.sub {
		display: flex;
		min-width: 0;
	}
	.sub span + span::before {
		content: '\00a0·\00a0';
	}
	.user {
		flex: none;
		max-width: 50%;
		color: var(--m3c-on-surface);
	}
	.side {
		display: grid;
		justify-items: end;
		align-content: start;
		gap: 0.125rem;
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
		text-align: right;
		font-variant-numeric: tabular-nums;
	}
	.dur {
		font-size: 0.875rem;
		color: var(--m3c-on-surface);
	}
	.method.transcode {
		color: var(--m3c-tertiary);
		font-weight: 500;
	}
</style>
