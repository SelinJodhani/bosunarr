<script lang="ts">
	import { page } from '$app/state';
	import { Button, Icon } from 'm3-svelte';
	import { live } from '$lib/live.svelte';
	import { SERVICE_TYPES, type PublicService } from '$lib/services/catalog';
	import type { HealthItem } from '$lib/widgets/types';
	import { iAdd, iSettings } from '$lib/icons';

	const services = $derived((page.data.services ?? []) as PublicService[]);
	const health = live<HealthItem[]>('/api/widgets/health', 30_000);
	const byId = $derived(new Map((health.state.data ?? []).map((h) => [h.id, h])));

	const ORDER = ['media', 'requests', 'indexer', 'subtitles', 'downloads', 'server', 'stats', 'users'] as const;
	const groups = $derived(
		ORDER.map((cat) => ({ cat, items: services.filter((s) => SERVICE_TYPES[s.type]?.category === cat) })).filter((g) => g.items.length)
	);
	const CAT_LABEL: Record<(typeof ORDER)[number], string> = {
		media: 'Media managers',
		requests: 'Requests',
		indexer: 'Indexers',
		subtitles: 'Subtitles',
		downloads: 'Downloads',
		server: 'Media server',
		stats: 'Statistics',
		users: 'Users & invites'
	};
</script>

<svelte:head><title>Services · Bosunarr</title></svelte:head>

<header>
	<h1 class="title-large">Services</h1>
	<Button variant="text" iconType="full" href="/settings" aria-label="Settings"><Icon icon={iSettings} /></Button>
</header>

{#if services.length === 0}
	<section class="empty">
		<p>No services yet. Add Sonarr, Radarr, qBittorrent and the rest in Settings.</p>
		<Button variant="filled" href="/settings"><Icon icon={iAdd} /> Add a service</Button>
	</section>
{:else}
	{#each groups as g (g.cat)}
		<h2 class="group">{CAT_LABEL[g.cat]}</h2>
		<ul class="grid">
			{#each g.items as s (s.id)}
				{@const h = byId.get(s.id)}
				<li>
					<a class="tile" href={`/services/${s.id}`}>
						<span class="badge" style:--c={SERVICE_TYPES[s.type]?.color}>{(SERVICE_TYPES[s.type]?.label ?? s.type).slice(0, 1)}</span>
						<span class="text">
							<span class="name">{s.name}</span>
							<span class="sub">
								{SERVICE_TYPES[s.type]?.label ?? s.type}{h?.version ? ` · v${h.version.replace(/^v/, '')}` : ''}
							</span>
						</span>
						<span
							class="state"
							class:ok={h?.ok}
							class:bad={h && !h.ok}
							title={h ? (h.ok ? `${h.ms} ms` : (h.error ?? 'Unreachable')) : 'Checking…'}
						>
							{h ? (h.ok ? `${h.ms} ms` : 'Down') : ''}
						</span>
					</a>
				</li>
			{/each}
		</ul>
	{/each}
{/if}

<style>
	header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		margin-bottom: 0.5rem;
	}
	.group {
		font-size: 0.75rem;
		font-weight: 600;
		letter-spacing: 0.04em;
		text-transform: uppercase;
		color: var(--m3c-primary);
		margin: 1.25rem 0 0.5rem;
	}
	.grid {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(min(100%, 18rem), 1fr));
		gap: 0.5rem;
	}
	.tile {
		display: flex;
		align-items: center;
		gap: 0.875rem;
		padding: 0.875rem 1rem;
		border-radius: var(--radius-m);
		background: var(--m3c-surface-container-low);
		color: inherit;
		text-decoration: none;
		min-height: 4.5rem;
		transition: background-color 150ms;
	}
	.tile:hover,
	.tile:focus-visible {
		background: var(--m3c-surface-container);
	}
	.badge {
		flex: none;
		width: 2.5rem;
		height: 2.5rem;
		border-radius: 0.875rem;
		display: grid;
		place-items: center;
		font-weight: 600;
		color: var(--c);
		background: color-mix(in oklab, var(--c) 18%, var(--m3c-surface-container-highest));
	}
	.text {
		flex: 1;
		min-width: 0;
		display: grid;
	}
	.name,
	.sub {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.name {
		font-weight: 500;
	}
	.sub {
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
	}
	.state {
		flex: none;
		font-size: 0.75rem;
		font-variant-numeric: tabular-nums;
		color: var(--m3c-on-surface-variant);
		display: inline-flex;
		align-items: center;
		gap: 0.375rem;
	}
	.state::before {
		content: '';
		width: 0.5rem;
		height: 0.5rem;
		border-radius: 50%;
		background: var(--m3c-outline);
	}
	.state.ok::before {
		background: #3fb950;
	}
	.state.bad {
		color: var(--m3c-error);
	}
	.state.bad::before {
		background: var(--m3c-error);
	}
	.empty {
		display: grid;
		gap: 1rem;
		justify-items: start;
		padding: 1.25rem;
		border-radius: var(--radius-l);
		background: var(--m3c-surface-container-low);
	}
	.empty p {
		margin: 0;
	}
</style>
