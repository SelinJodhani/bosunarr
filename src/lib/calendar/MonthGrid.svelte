<script lang="ts">
	import { dayKey } from './client.svelte';
	import type { CalendarEntry } from './types';

	let {
		days,
		month,
		groups,
		selected,
		onpick
	}: {
		days: Date[];
		/** 0–11: days outside it are dimmed. */
		month: number;
		groups: Map<string, CalendarEntry[]>;
		selected: string;
		onpick: (key: string) => void;
	} = $props();

	const MAX = 3;
	const today = dayKey(new Date());
	const weekdays = $derived(days.slice(0, 7).map((d) => d.toLocaleDateString(undefined, { weekday: 'short' })));
	const cells = $derived(
		days.map((d) => {
			const k = dayKey(d);
			const list = groups.get(k) ?? [];
			return {
				k,
				date: d.getDate(),
				outside: d.getMonth() !== month,
				full: d.toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' }),
				list: list.slice(0, MAX),
				more: Math.max(0, list.length - MAX),
				count: list.length
			};
		})
	);
</script>

<div class="grid" role="grid" aria-label="Month">
	<div class="row head" role="row">
		{#each weekdays as w, i (i)}
			<div class="wd" role="columnheader">{w}</div>
		{/each}
	</div>
	{#each [0, 1, 2, 3, 4, 5] as row (row)}
		<div class="row" role="row">
			{#each cells.slice(row * 7, row * 7 + 7) as c (c.k)}
				<button
					type="button"
					role="gridcell"
					class="cell"
					class:outside={c.outside}
					class:today={c.k === today}
					class:selected={c.k === selected}
					aria-selected={c.k === selected}
					aria-label="{c.full}{c.count ? `, ${c.count} release${c.count === 1 ? '' : 's'}` : ''}"
					onclick={() => onpick(c.k)}
				>
					<span class="num">{c.date}</span>
					{#each c.list as e (e.id)}
						<span class="ev {e.kind} {e.status}" class:unmon={!e.monitored} title="{e.title}{e.episodeLabel ? ` · ${e.episodeLabel}` : ''}">
							<span class="n">{e.title}</span>
						</span>
					{/each}
					{#if c.more}<span class="more">+{c.more} more</span>{/if}
				</button>
			{/each}
		</div>
	{/each}
</div>

<style>
	.grid {
		display: grid;
		grid-template-rows: auto repeat(6, minmax(6.75rem, 1fr));
		border-radius: var(--m3-shape-large, 1rem);
		overflow: hidden;
		background: var(--m3c-outline-variant);
		gap: 1px;
		border: 1px solid var(--m3c-outline-variant);
	}
	.row {
		display: grid;
		grid-template-columns: repeat(7, minmax(0, 1fr));
		gap: 1px;
	}
	.wd {
		background: var(--m3c-surface-container);
		padding: 0.5rem;
		font-size: 0.75rem;
		font-weight: 500;
		color: var(--m3c-on-surface-variant);
		text-align: center;
	}
	.cell {
		all: unset;
		box-sizing: border-box;
		cursor: pointer;
		display: flex;
		flex-direction: column;
		gap: 0.1875rem;
		min-width: 0;
		padding: 0.375rem;
		background: var(--m3c-surface);
		transition: background-color 120ms;
	}
	.cell:hover {
		background: var(--m3c-surface-container-low);
	}
	.cell:focus-visible {
		outline: 2px solid var(--m3c-primary);
		outline-offset: -2px;
	}
	.cell.selected {
		background: var(--m3c-surface-container-high);
	}
	.outside {
		background: var(--m3c-surface-container-lowest);
	}
	.outside .num,
	.outside .ev {
		opacity: 0.55;
	}
	.num {
		align-self: flex-start;
		display: grid;
		place-items: center;
		min-width: 1.75rem;
		height: 1.75rem;
		border-radius: 0.875rem;
		font-size: 0.8125rem;
		font-weight: 500;
		font-variant-numeric: tabular-nums;
	}
	.today .num {
		background: var(--m3c-primary);
		color: var(--m3c-on-primary);
	}
	.ev {
		display: flex;
		gap: 0.25rem;
		min-width: 0;
		font-size: 0.75rem;
		line-height: 1.25rem;
		padding: 0 0.375rem;
		border-radius: 0.375rem;
		background: color-mix(in oklab, var(--m3c-primary) 14%, transparent);
		color: var(--m3c-on-surface);
		border-left: 3px solid var(--m3c-primary);
	}
	.ev.movie {
		background: color-mix(in oklab, var(--m3c-tertiary) 14%, transparent);
		border-left-color: var(--m3c-tertiary);
	}
	.ev.missing {
		border-left-color: var(--m3c-error);
	}
	.ev.downloaded .n {
		color: var(--m3c-on-surface-variant);
	}
	.ev.unmon {
		background: transparent;
	}
	.n {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.more {
		font-size: 0.6875rem;
		font-weight: 500;
		color: var(--m3c-on-surface-variant);
		padding-left: 0.375rem;
	}
</style>
