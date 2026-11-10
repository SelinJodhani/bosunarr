<script lang="ts" module>
	export type SkeletonSpec = {
		/** Number of list rows. */
		rows: number;
		/** Poster thumbnail width (px) on each row; height is 1.5×. */
		thumb?: number;
		/** Thin progress bar under each row's text. */
		bar?: boolean;
		/** A taller summary line above the rows (speeds, "All 6 services healthy"). */
		lead?: boolean;
		/** Horizontal poster rail instead of rows. */
		rail?: boolean;
		/** Rows laid out as tiles in a responsive grid (Health). */
		tiles?: boolean;
	};
</script>

<script lang="ts">
	// Shared loading / error / empty states so every widget behaves the same.
	import type { Snippet } from 'svelte';
	import { Button, Icon, LoadingIndicator } from 'm3-svelte';
	import type { IconifyIcon } from '@iconify/types';
	import { iError } from '$lib/icons';
	import iHistory from '@ktibow/iconset-material-symbols/history-rounded';
	import { humanError } from './errors';
	import { frameContext } from './frame';
	import { rememberHeight } from './heights';

	let {
		loading,
		error,
		empty,
		emptyText = 'Nothing here right now',
		emptyIcon,
		emptyHint,
		emptyAction,
		skeleton,
		children
	}: {
		loading: boolean;
		error: string | null;
		empty: boolean;
		emptyText?: string;
		emptyIcon?: IconifyIcon;
		emptyHint?: string;
		/** A way through from an empty widget, e.g. "Open Activity". */
		emptyAction?: { label: string; href: string };
		/** Shape of the loading placeholder. Without it a spinner is shown (screens outside the dashboard). */
		skeleton?: SkeletonSpec;
		children: Snippet;
	} = $props();

	const frame = frameContext();
	const pending = $derived(empty && loading && !error);
	const failed = $derived(error && empty ? humanError(error) : null);
	let showDetail = $state(false);
	let height = $state(0);

	// Cached data + a failed refresh: the frame shows a small "last known data" chip.
	$effect(() => {
		frame?.setStale(error && !empty ? error : null);
	});
	$effect(() => {
		if (!pending && height > 0 && frame) rememberHeight(frame.id, height);
	});
</script>

{#if pending && !skeleton}
	<div class="center"><LoadingIndicator aria-label="Loading" /></div>
{:else if pending && skeleton}
	<div
		class="skeleton"
		class:rail={skeleton.rail}
		class:tiles={skeleton.tiles}
		style:height={frame?.reserve ? `${frame.reserve}px` : undefined}
		aria-busy="true"
		aria-label="Loading"
		role="status"
	>
		{#if skeleton.lead}<div class="sk lead"></div>{/if}
		{#if skeleton.rail}
			<div class="rail-row">
				{#each { length: skeleton.rows } as _, i (i)}
					<div class="rail-item"><div class="sk poster"></div><div class="sk line"></div><div class="sk line short"></div></div>
				{/each}
			</div>
		{:else}
			<div class="rows">
				{#each { length: skeleton.rows } as _, i (i)}
					<div class="row">
						{#if skeleton.thumb}<div class="sk" style:width="{skeleton.thumb}px" style:height="{skeleton.thumb * 1.5}px"></div>{/if}
						<div class="text">
							<div class="sk line" style:width="{70 - ((i * 17) % 30)}%"></div>
							{#if !skeleton.tiles}<div class="sk line short"></div>{/if}
							{#if skeleton.bar}<div class="sk bar"></div>{/if}
						</div>
					</div>
				{/each}
			</div>
		{/if}
	</div>
{:else if frame}
	<div class="content" bind:clientHeight={height}>{@render body()}</div>
{:else}
	{@render body()}
	{#if error && !empty}
		<p class="stale-note" title={error}><Icon icon={iHistory} size={14} /> Showing last known data · {humanError(error).short}</p>
	{/if}
{/if}

{#snippet body()}
		{#if failed}
			<div class="state" role="alert">
				<Icon icon={iError} size={32} />
				<p class="headline">{failed.service ? `${failed.service}: ` : ''}{failed.short}</p>
				{#if failed.hint}<p class="hint">{failed.hint}</p>{/if}
				<button class="detail-toggle" title={failed.detail} aria-expanded={showDetail} onclick={() => (showDetail = !showDetail)}>
					{showDetail ? 'Hide details' : 'Details'}
				</button>
				{#if showDetail}<p class="detail">{failed.detail}</p>{/if}
			</div>
		{:else if empty}
			<!-- Compact form of the app-wide empty state (app.css `.empty-state`). -->
			<div class="empty-state compact">
				{#if emptyIcon}<span class="es-icon"><Icon icon={emptyIcon} size={32} /></span>{/if}
				<p class="es-title">{emptyText}</p>
				{#if emptyHint}<p class="es-hint">{emptyHint}</p>{/if}
				{#if emptyAction}<div class="es-action"><Button variant="text" href={emptyAction.href}>{emptyAction.label}</Button></div>{/if}
			</div>
		{:else}
			{@render children()}
		{/if}
{/snippet}

<style>
	.center {
		display: grid;
		place-items: center;
		min-height: 4rem;
	}
	.stale-note {
		display: inline-flex;
		align-items: center;
		gap: 0.25rem;
		margin: 0.5rem 0 0;
		padding: 0.125rem 0.5rem;
		border-radius: 0.5rem;
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
		background: var(--m3c-surface-container-high);
	}
	.state {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		text-align: center;
		gap: 0.25rem;
		padding: 1rem 0.5rem;
		color: var(--m3c-on-surface-variant);
	}
	.state[role='alert'] :global(svg) {
		color: var(--m3c-error);
	}
	.state p {
		margin: 0;
	}
	.headline {
		margin-top: 0.25rem !important;
		font-size: 0.875rem;
		font-weight: 500;
		color: var(--m3c-on-surface);
	}
	.hint,
	.detail {
		font-size: 0.75rem;
	}
	.detail {
		max-width: 100%;
		overflow-wrap: anywhere;
		font-family: ui-monospace, monospace;
	}
	.detail-toggle {
		all: unset;
		cursor: pointer;
		min-height: 2.5rem;
		padding: 0 0.75rem;
		display: inline-flex;
		align-items: center;
		border-radius: 1.25rem;
		font-size: 0.8125rem;
		font-weight: 500;
		color: var(--m3c-primary);
	}
	.detail-toggle:hover {
		background: color-mix(in oklab, var(--m3c-primary) 8%, transparent);
	}
	.detail-toggle:focus-visible {
		outline: 2px solid var(--m3c-primary);
	}

	/* Skeleton: same rhythm as the real rows so nothing jumps when data paints. */
	.skeleton {
		overflow: hidden;
	}
	.sk {
		border-radius: 0.375rem;
		background: var(--m3c-surface-container-high);
		animation: pulse 1.4s ease-in-out infinite;
	}
	@keyframes pulse {
		50% {
			opacity: 0.55;
		}
	}
	@media (prefers-reduced-motion: reduce) {
		.sk {
			animation: none;
		}
	}
	.lead {
		height: 1.75rem;
		width: 55%;
		margin-bottom: 0.75rem;
	}
	.rows {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.75rem;
	}
	.tiles .rows {
		grid-template-columns: repeat(auto-fill, minmax(min(14rem, 100%), 1fr));
		gap: 0.5rem;
	}
	.tiles .row {
		height: 2.75rem;
		padding: 0 0.75rem;
		border-radius: var(--radius-m);
		background: var(--m3c-surface-container);
	}
	.row {
		display: flex;
		align-items: center;
		gap: 0.75rem;
	}
	.text {
		flex: 1;
		display: grid;
		gap: 0.375rem;
	}
	.line {
		height: 0.875rem;
	}
	.line.short {
		height: 0.75rem;
		width: 40%;
	}
	.bar {
		height: 4px;
	}
	.rail-row {
		display: flex;
		gap: 0.875rem;
	}
	.rail-item {
		flex: 1 0 112px;
		max-width: 160px;
		display: grid;
		gap: 0.375rem;
		align-content: start;
	}
	.poster {
		aspect-ratio: 2 / 3;
		margin-bottom: 0.125rem;
		border-radius: 0.5rem;
	}
</style>
