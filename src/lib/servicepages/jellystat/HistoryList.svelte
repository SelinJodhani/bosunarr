<script lang="ts">
	import { live } from '$lib/live.svelte';
	import iHistory from '@ktibow/iconset-material-symbols/history-rounded';
	import State from '../shared/State.svelte';
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

<State
	loading={q.state.loading}
	error={q.state.error}
	empty={rows.length === 0}
	icon={iHistory}
	title={searching ? 'No plays match that search' : 'No plays recorded yet'}
	onretry={() => q.refresh()}
>
	<ul>
		{#each rows as r (r.id)}
			<li>
				<div class="main">
					<div class="title" title={r.title}>{r.title}</div>
					<div class="sub">{[r.subtitle, r.user, r.client || r.device].filter(Boolean).join(' · ')}</div>
				</div>
				<div class="side">
					<time datetime={r.at ?? undefined}>{when(r.at)}</time>
					<span>
						<span class="dur">{duration(r.seconds)}{r.plays > 1 ? ` · ${r.plays}×` : ''}</span>{#if r.method}<span class="method" class:transcode={r.method === 'Transcode'}>{'\u00a0· '}{method(r.method)}</span>{/if}
					</span>
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
		align-items: center;
		gap: 0.75rem;
		min-height: 4rem;
		padding: 0.625rem 1rem;
	}
	li + li {
		border-top: 1px solid var(--m3c-outline-variant);
	}
	.main,
	.side {
		min-width: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.125rem;
	}
	.title,
	.sub {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.title {
		font-weight: 500;
	}
	.sub {
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
	}
	.side {
		justify-items: end;
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
		text-align: right;
		font-variant-numeric: tabular-nums;
		white-space: nowrap;
	}
	.dur {
		color: var(--m3c-on-surface);
	}
	.method.transcode {
		color: var(--m3c-tertiary);
		font-weight: 500;
	}
</style>
