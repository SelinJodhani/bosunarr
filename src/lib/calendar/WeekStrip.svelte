<script lang="ts">
	import { dayKey } from './client.svelte';
	import type { CalendarEntry } from './types';

	let {
		days,
		groups,
		selected,
		today,
		onpick
	}: {
		days: Date[];
		groups: Map<string, CalendarEntry[]>;
		selected: string;
		/** Today's key ('' before the device clock is known). */
		today: string;
		onpick: (key: string) => void;
	} = $props();

	const cells = $derived(
		days.map((d) => {
			const k = dayKey(d);
			const list = groups.get(k) ?? [];
			return {
				k,
				weekday: d.toLocaleDateString(undefined, { weekday: 'narrow' }),
				full: d.toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' }),
				date: d.getDate(),
				episodes: list.some((e) => e.kind === 'episode'),
				movies: list.some((e) => e.kind === 'movie'),
				count: list.length
			};
		})
	);
</script>

<div class="strip" role="group" aria-label="Week">
	{#each cells as c (c.k)}
		<button
			type="button"
			class:today={c.k === today}
			class:selected={c.k === selected}
			aria-pressed={c.k === selected}
			aria-current={c.k === today ? 'date' : undefined}
			aria-label="{c.full}{c.count ? `, ${c.count} release${c.count === 1 ? '' : 's'}` : ''}"
			onclick={() => onpick(c.k)}
		>
			<span class="wd" aria-hidden="true">{c.weekday}</span>
			<span class="num" aria-hidden="true">{c.date}</span>
			<span class="dots" aria-hidden="true">
				{#if c.episodes}<i class="ep"></i>{/if}
				{#if c.movies}<i class="mv"></i>{/if}
			</span>
		</button>
	{/each}
</div>

<style>
	.strip {
		display: grid;
		grid-template-columns: repeat(7, 1fr);
		gap: 0.25rem;
	}
	button {
		all: unset;
		box-sizing: border-box;
		cursor: pointer;
		display: grid;
		justify-items: center;
		gap: 0.25rem;
		padding: 0.5rem 0 0.375rem;
		border-radius: 1.25rem;
		-webkit-tap-highlight-color: transparent;
		transition: background-color 150ms;
	}
	button:hover {
		background: var(--m3c-surface-container-high);
	}
	button:focus-visible {
		outline: 2px solid var(--m3c-primary);
	}
	.wd {
		font-size: 0.75rem;
		font-weight: 500;
		color: var(--m3c-on-surface-variant);
	}
	.num {
		display: grid;
		place-items: center;
		width: 2.25rem;
		height: 2.25rem;
		border-radius: 50%;
		font-size: 1rem;
		font-weight: 500;
		font-variant-numeric: tabular-nums;
	}
	.today .num {
		color: var(--m3c-primary);
		box-shadow: inset 0 0 0 1.5px var(--m3c-primary);
	}
	.selected .num {
		background: var(--m3c-primary);
		color: var(--m3c-on-primary);
		box-shadow: none;
	}
	.dots {
		display: flex;
		gap: 0.1875rem;
		height: 0.375rem;
	}
	.dots i {
		width: 0.375rem;
		height: 0.375rem;
		border-radius: 50%;
	}
	.ep {
		background: var(--m3c-primary);
	}
	.mv {
		background: var(--m3c-tertiary);
	}
</style>
