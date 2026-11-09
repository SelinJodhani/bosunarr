<script lang="ts">
	import { Icon, LinearProgress } from 'm3-svelte';
	import { live } from '$lib/live.svelte';
	import { eta, speed } from '$lib/format';
	import { iDownload, iUpload } from '$lib/icons';
	import State from './State.svelte';
	import { WIDGETS, type DownloadsData, type WidgetSize } from './types';

	let { size }: { size: WidgetSize } = $props();
	const q = live<DownloadsData>('/api/widgets/downloads', WIDGETS.downloads.refreshMs);
	const d = $derived(q.state.data);
	const shown = $derived(d ? d.torrents.slice(0, size === 'l' ? 8 : size === 'm' ? 4 : 0) : []);
</script>

<State loading={q.state.loading} error={q.state.error} empty={!d}>
	{#if d}
		<div class="speeds">
			<div><Icon icon={iDownload} /> <strong>{speed(d.down)}</strong></div>
			<div><Icon icon={iUpload} /> <strong>{speed(d.up)}</strong></div>
			<div class="label">{d.active} active</div>
		</div>
		{#if shown.length}
			<ul>
				{#each shown as t (t.id)}
					<li>
						<div class="line">
							<span class="name" title={t.name}>{t.name}</span>
							<span class="label">{speed(t.dlspeed)} · {eta(t.eta)}</span>
						</div>
						<LinearProgress percent={t.progress * 100} aria-label={`${t.name} progress`} />
					</li>
				{/each}
			</ul>
		{:else if size !== 's'}
			<p class="label idle">Nothing downloading</p>
		{/if}
	{/if}
</State>

<style>
	.speeds {
		display: flex;
		align-items: center;
		gap: 1.25rem;
		flex-wrap: wrap;
		font-size: 1.25rem;
		margin-bottom: 0.75rem;
	}
	.speeds div {
		display: flex;
		align-items: center;
		gap: 0.25rem;
	}
	strong {
		font-weight: 500;
		font-variant-numeric: tabular-nums;
	}
	ul {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		gap: 0.75rem;
	}
	.line {
		display: flex;
		justify-content: space-between;
		gap: 0.75rem;
		margin-bottom: 0.25rem;
		font-size: 0.875rem;
	}
	.name {
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.label {
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
		white-space: nowrap;
		font-variant-numeric: tabular-nums;
	}
	.idle {
		margin: 0;
	}
</style>
