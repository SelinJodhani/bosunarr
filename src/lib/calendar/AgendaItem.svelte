<script lang="ts">
	import { Icon } from 'm3-svelte';
	import Poster from '$lib/widgets/Poster.svelte';
	import { timeLabel } from '$lib/format';
	import { iCinema, iDownload, iOk, iSchedule, iTv, iUnmonitored } from '$lib/icons';
	import { STATUS_TEXT, releaseLabel, type CalendarEntry } from './types';

	let { entry }: { entry: CalendarEntry } = $props();

	const when = $derived(entry.allDay ? null : timeLabel(entry.date));
	const meta = $derived(
		[when, entry.runtime ? `${entry.runtime} min` : null, entry.network].filter(Boolean).join(' · ')
	);
	const statusIcon = $derived(entry.status === 'downloaded' ? iOk : entry.status === 'missing' ? iDownload : iSchedule);
	const copiesText = $derived(
		entry.copies.length > 1
			? entry.copies.map((c) => `${c.serviceName}: ${STATUS_TEXT[c.status].toLowerCase()}`).join(' · ')
			: entry.copies[0]?.serviceName
	);
</script>

<a class="item" href={entry.href} class:movie={entry.kind === 'movie'} class:dim={!entry.monitored}>
	<div class="pv">
		<Icon icon={entry.kind === 'movie' ? iCinema : iTv} size={20} />
		<Poster src={entry.poster} width={44} alt="" />
	</div>
	<div class="text">
		<div class="title">
			{entry.title}{#if entry.kind === 'movie' && entry.year}<span class="year">({entry.year})</span>{/if}
		</div>
		<div class="sub">{entry.episodeLabel ?? releaseLabel(entry.releases ?? [entry.release])}</div>
		{#if meta}<div class="meta">{meta}</div>{/if}
		<div class="tags">
			<span class="tag status {entry.status}" title={copiesText}>
				<Icon icon={statusIcon} size={14} />
				{STATUS_TEXT[entry.status]}
			</span>
			{#if entry.marker}<span class="tag marker">{entry.marker}</span>{/if}
			{#if entry.is4k}<span class="tag" title={copiesText}>4K</span>{/if}
			{#if !entry.monitored}<span class="tag"><Icon icon={iUnmonitored} size={14} /> Unmonitored</span>{/if}
		</div>
	</div>
</a>

<style>
	.item {
		display: flex;
		align-items: flex-start;
		gap: 0.75rem;
		min-height: 5.375rem;
		padding: 0.625rem 0.75rem 0.625rem 0.875rem;
		border-radius: var(--m3-shape-large, 1rem);
		overflow: hidden;
		color: inherit;
		text-decoration: none;
		background: var(--m3c-surface-container-low);
		position: relative;
		-webkit-tap-highlight-color: transparent;
		transition: background-color 120ms;
	}
	/* Kind accent: a full-height edge, clipped by the card's rounded corners. */
	.item::before {
		content: '';
		position: absolute;
		inset: 0 auto 0 0;
		width: 0.25rem;
		background: var(--m3c-primary);
	}
	.item.movie::before {
		background: var(--m3c-tertiary);
	}
	.item:hover {
		background: var(--m3c-surface-container);
	}
	.item:active {
		background: var(--m3c-surface-container-high);
	}
	.item:focus-visible {
		outline: 2px solid var(--m3c-primary);
		outline-offset: 2px;
	}
	.dim .pv,
	.dim .text {
		opacity: 0.6;
	}
	/* Kind icon behind the poster: it shows when there is no artwork (or it fails to load). */
	.pv {
		flex: none;
		position: relative;
		display: grid;
		border-radius: 0.5rem;
		background: var(--m3c-surface-container-highest);
		color: var(--m3c-on-surface-variant);
	}
	.pv > :global(*) {
		grid-area: 1 / 1;
	}
	.pv > :global(svg) {
		place-self: center;
		opacity: 0.7;
	}
	.pv :global(.poster) {
		background: transparent;
		position: relative;
	}
	.text {
		flex: 1;
		min-width: 0;
		display: grid;
		gap: 0.125rem;
	}
	.title {
		font-weight: 500;
		line-height: 1.25rem;
		display: -webkit-box;
		-webkit-box-orient: vertical;
		-webkit-line-clamp: 2;
		line-clamp: 2;
		overflow: hidden;
		overflow-wrap: anywhere;
	}
	.year {
		margin-left: 0.3em;
		color: var(--m3c-on-surface-variant);
		font-weight: 400;
		white-space: nowrap;
	}
	.sub,
	.meta {
		font-size: 0.8125rem;
		line-height: 1.125rem;
		color: var(--m3c-on-surface-variant);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.sub {
		color: var(--m3c-on-surface);
	}
	.tags {
		display: flex;
		flex-wrap: wrap;
		gap: 0.25rem;
		margin-top: 0.375rem;
	}
	.tag {
		display: inline-flex;
		align-items: center;
		gap: 0.25rem;
		height: 1.375rem;
		font-size: 0.6875rem;
		font-weight: 500;
		line-height: 1;
		padding: 0 0.5rem;
		border-radius: 0.375rem;
		background: var(--m3c-surface-container-highest);
		color: var(--m3c-on-surface-variant);
		white-space: nowrap;
	}
	.tag.marker {
		text-transform: capitalize;
		background: var(--m3c-secondary-container);
		color: var(--m3c-on-secondary-container);
	}
	.status {
		padding-left: 0.375rem;
	}
	.status.downloaded {
		color: var(--m3c-on-primary-container);
		background: var(--m3c-primary-container);
	}
	.status.missing {
		color: var(--m3c-on-error-container);
		background: var(--m3c-error-container);
	}
	.status.unaired {
		background: transparent;
		box-shadow: inset 0 0 0 1px var(--m3c-outline-variant);
	}
</style>
