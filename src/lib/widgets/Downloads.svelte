<script lang="ts">
	import { Button, Icon, LinearProgress } from 'm3-svelte';
	import { live } from '$lib/live.svelte';
	import { eta, speed } from '$lib/format';
	import { iDownload, iUpload } from '$lib/icons';
	import State from './State.svelte';
	import iIdle from '@ktibow/iconset-material-symbols/file-download-off-rounded';
	import { WIDGETS, type DownloadsData, type WidgetSize } from './types';

	let { size }: { size: WidgetSize } = $props();
	const q = live<DownloadsData>('/api/widgets/downloads', WIDGETS.downloads.refreshMs);
	const d = $derived(q.state.data);
	const max = $derived(size === 'l' ? 8 : size === 'm' ? 4 : 0);
	// Older cached data has no split and lists seeding torrents too: keep only unfinished ones.
	const shown = $derived(d ? d.torrents.filter((t) => t.progress < 1).slice(0, max) : []);
	const summary = $derived.by(() => {
		if (!d) return '';
		if (d.downloading === undefined) return `${d.active} active`;
		const parts = [];
		if (d.downloading) parts.push(`${d.downloading} downloading`);
		if (d.seeding) parts.push(`${d.seeding} seeding`);
		return parts.join(' · ') || 'Idle';
	});
	const etaText = (s: number) => (s > 0 && s < 8_640_000 ? eta(s) : '');
</script>

<State loading={q.state.loading} error={q.state.error} empty={!d} skeleton={{ rows: max, lead: true, bar: true }}>
	{#if d}
		<a class="speeds row-link" href="/activity" aria-label={`Downloads: ${speed(d.down)} down, ${speed(d.up)} up, ${summary}. Open Activity`}>
			<span class="speed"><Icon icon={iDownload} /> <strong>{speed(d.down)}</strong></span>
			<span class="speed"><Icon icon={iUpload} /> <strong>{speed(d.up)}</strong></span>
			<span class="label summary">{summary}</span>
		</a>
		{#if shown.length}
			<ul>
				{#each shown as t (t.id)}
					<li>
						<a class="row-link item" href="/activity">
							<div class="line">
								<span class="name" title={t.name}>{t.name}</span>
								<span class="label">{t.dlspeed > 0 ? [speed(t.dlspeed), etaText(t.eta)].filter(Boolean).join(' · ') : t.state === 'metaDL' ? 'Fetching metadata' : `${Math.round(t.progress * 100)}% · stalled`}</span>
							</div>
							<LinearProgress percent={t.progress * 100} aria-label={`${t.name} progress`} />
						</a>
					</li>
				{/each}
			</ul>
		{:else if size !== 's'}
			<!-- Compact form of the app-wide empty state (app.css `.empty-state`). -->
			<div class="empty-state compact idle">
				<span class="es-icon"><Icon icon={iIdle} size={32} /></span>
				<p class="es-title">Nothing downloading</p>
				<div class="es-action"><Button variant="text" href="/activity">Open Activity</Button></div>
			</div>
		{/if}
	{/if}
</State>

<style>
	.speeds {
		gap: 0 1.25rem;
		flex-wrap: wrap;
		font-size: 1.25rem;
		margin-bottom: 0.5rem !important;
	}
	.speed {
		display: flex;
		align-items: center;
		gap: 0.25rem;
	}
	.summary {
		flex-basis: 100%;
		margin-top: -0.125rem;
	}
	.item {
		display: block !important;
		padding-block: 0.375rem !important;
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
		/* minmax(0, …) lets long torrent names truncate instead of widening the card. */
		grid-template-columns: minmax(0, 1fr);
		gap: 0.25rem;
	}
	.line {
		display: flex;
		justify-content: space-between;
		gap: 0.75rem;
		margin-bottom: 0.25rem;
		font-size: 0.875rem;
	}
	.name {
		flex: 1;
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
		padding-top: 0.5rem;
	}
</style>
