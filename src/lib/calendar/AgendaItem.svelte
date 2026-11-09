<script lang="ts">
	import { Icon } from 'm3-svelte';
	import Poster from '$lib/widgets/Poster.svelte';
	import { timeLabel } from '$lib/format';
	import { i4k, iCinema, iDownload, iOk, iSchedule, iTv, iUnmonitored } from '$lib/icons';
	import { RELEASE_LABEL, STATUS_TEXT, type CalendarEntry } from './types';

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
	<Poster src={entry.poster} width={44} alt="" />
	<div class="text">
		<div class="title">
			{entry.title}{#if entry.kind === 'movie' && entry.year}<span class="year">({entry.year})</span>{/if}
		</div>
		<div class="sub">
			<Icon icon={entry.kind === 'movie' ? iCinema : iTv} size={16} />
			<span>{entry.episodeLabel ?? RELEASE_LABEL[entry.release]}</span>
		</div>
		{#if meta}<div class="meta">{meta}</div>{/if}
		{#if entry.marker || entry.is4k || !entry.monitored}
			<div class="tags">
				{#if entry.marker}<span class="tag marker">{entry.marker}</span>{/if}
				{#if entry.is4k}<span class="tag" title={copiesText}><Icon icon={i4k} size={16} /> 4K</span>{/if}
				{#if !entry.monitored}<span class="tag"><Icon icon={iUnmonitored} size={16} /> Unmonitored</span>{/if}
			</div>
		{/if}
	</div>
	<span class="status {entry.status}" title={copiesText}>
		<Icon icon={statusIcon} size={18} />
		<span class="lbl">{STATUS_TEXT[entry.status]}</span>
	</span>
</a>

<style>
	.item {
		display: flex;
		align-items: flex-start;
		gap: 0.75rem;
		padding: 0.625rem 0.75rem;
		border-radius: var(--m3-shape-large, 1rem);
		color: inherit;
		text-decoration: none;
		background: var(--m3c-surface-container-low);
		position: relative;
		transition: background-color 120ms;
	}
	.item::before {
		content: '';
		position: absolute;
		left: 0;
		top: 0.75rem;
		bottom: 0.75rem;
		width: 0.1875rem;
		border-radius: 0 0.25rem 0.25rem 0;
		background: var(--m3c-primary);
	}
	.item.movie::before {
		background: var(--m3c-tertiary);
	}
	.item:hover {
		background: var(--m3c-surface-container);
	}
	.item:focus-visible {
		outline: 2px solid var(--m3c-primary);
		outline-offset: 2px;
	}
	.dim :global(.poster),
	.dim .text {
		opacity: 0.6;
	}
	.text {
		flex: 1;
		min-width: 0;
		display: grid;
		gap: 0.125rem;
	}
	.title {
		font-weight: 500;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.year {
		margin-left: 0.3em;
		color: var(--m3c-on-surface-variant);
		font-weight: 400;
	}
	.sub,
	.meta {
		font-size: 0.8125rem;
		color: var(--m3c-on-surface-variant);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.sub {
		display: flex;
		align-items: center;
		gap: 0.25rem;
		color: var(--m3c-on-surface);
	}
	.sub span {
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.tags {
		display: flex;
		flex-wrap: wrap;
		gap: 0.25rem;
		margin-top: 0.25rem;
	}
	.tag {
		display: inline-flex;
		align-items: center;
		gap: 0.125rem;
		font-size: 0.6875rem;
		font-weight: 500;
		padding: 0.0625rem 0.375rem;
		border-radius: 0.375rem;
		background: var(--m3c-surface-container-highest);
		color: var(--m3c-on-surface-variant);
	}
	.tag.marker {
		text-transform: capitalize;
		background: var(--m3c-secondary-container);
		color: var(--m3c-on-secondary-container);
	}
	.status {
		flex: none;
		display: inline-flex;
		align-items: center;
		gap: 0.25rem;
		font-size: 0.75rem;
		font-weight: 500;
		padding: 0.25rem 0.5rem;
		border-radius: 1rem;
		align-self: center;
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
		color: var(--m3c-on-surface-variant);
		outline: 1px solid var(--m3c-outline-variant);
	}
	@media (width < 26rem) {
		.lbl {
			display: none;
		}
		.status {
			padding: 0.25rem;
		}
	}
</style>
