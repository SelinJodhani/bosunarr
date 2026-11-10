<script lang="ts">
	import type { JsDay } from './types';
	import { count, shortDate } from './format';

	let { days }: { days: JsDay[] } = $props();

	const SERIES = [
		{ key: 'movies', label: 'Movies', color: 'var(--m3c-primary)' },
		{ key: 'episodes', label: 'Episodes', color: 'var(--m3c-tertiary)' },
		{ key: 'other', label: 'Other', color: 'var(--m3c-outline)' }
	] as const;

	// Plot area in viewBox units; the SVG stretches to the card width (no text inside it, so nothing distorts).
	const W = 600;
	const H = 180;

	const total = (d: JsDay) => d.movies + d.episodes + d.other;
	const hasOther = $derived(days.some((d) => d.other > 0));
	const series = $derived(SERIES.filter((s) => s.key !== 'other' || hasOther));
	const max = $derived(Math.max(1, ...days.map(total)));
	/** A "nice" axis top: 1, 2, 5 × 10^n */
	const top = $derived.by(() => {
		const p = 10 ** Math.floor(Math.log10(max));
		return [1, 2, 5, 10].map((m) => m * p).find((v) => v >= max) ?? max;
	});
	const slot = $derived(W / Math.max(1, days.length));
	const gap = $derived(slot > 8 ? slot * 0.2 : slot * 0.12);
	const bars = $derived(
		days.map((d, i) => {
			let y = H;
			const parts = series.map((s) => {
				const h = (d[s.key] / top) * H;
				y -= h;
				return { key: s.key, color: s.color, y, h };
			});
			return { d, x: i * slot + gap / 2, w: Math.max(0.5, slot - gap), parts };
		})
	);
	const sum = $derived(days.reduce((t, d) => t + total(d), 0));
	const busiest = $derived(days.reduce<JsDay | null>((b, d) => (!b || total(d) > total(b) ? d : b), null));
	const summary = $derived(
		days.length
			? `Plays per day from ${shortDate(days[0].date)} to ${shortDate(days[days.length - 1].date)}: ${count(sum)} plays in total` +
					(busiest && total(busiest) ? `, busiest ${shortDate(busiest.date)} with ${count(total(busiest))}.` : '.')
			: 'No plays in this period.'
	);
	const ticks = $derived(days.length ? [days[0], days[Math.floor((days.length - 1) / 2)], days[days.length - 1]] : []);
</script>

<figure>
	<div class="legend" aria-hidden="true">
		{#each series as s (s.key)}
			<span><i style:background={s.color}></i>{s.label}</span>
		{/each}
	</div>
	<div class="plot">
		<div class="yaxis" aria-hidden="true">
			<span>{count(top)}</span>
			<span>{count(top / 2)}</span>
			<span>0</span>
		</div>
		<svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" role="img" aria-label={summary}>
			<line x1="0" x2={W} y1={H / 2} y2={H / 2} class="grid" vector-effect="non-scaling-stroke" />
			<line x1="0" x2={W} y1={H} y2={H} class="axis" vector-effect="non-scaling-stroke" />
			{#each bars as b (b.d.date)}
				<g>
					<title>{shortDate(b.d.date)}: {series.map((s) => `${b.d[s.key]} ${s.label.toLowerCase()}`).join(', ')}</title>
					<!-- invisible full-height hit area so thin bars still show their tooltip -->
					<rect x={b.x - gap / 2} y="0" width={slot} height={H} fill="transparent" />
					{#each b.parts as p (p.key)}
						{#if p.h > 0}<rect x={b.x} y={p.y} width={b.w} height={p.h} fill={p.color} />{/if}
					{/each}
				</g>
			{/each}
		</svg>
		<div class="xaxis" aria-hidden="true">
			{#each ticks as t, i (i)}
				<span>{shortDate(t.date)}</span>
			{/each}
		</div>
	</div>
	<details>
		<summary>Show as table</summary>
		<div class="table-wrap">
			<table>
				<caption class="sr-only">Plays per day</caption>
				<thead>
					<tr>
						<th scope="col">Day</th>
						{#each series as s (s.key)}<th scope="col">{s.label}</th>{/each}
						<th scope="col">Total</th>
					</tr>
				</thead>
				<tbody>
					{#each [...days].reverse() as d (d.date)}
						<tr>
							<th scope="row">{shortDate(d.date)}</th>
							{#each series as s (s.key)}<td>{d[s.key]}</td>{/each}
							<td>{total(d)}</td>
						</tr>
					{/each}
				</tbody>
			</table>
		</div>
	</details>
</figure>

<style>
	figure {
		margin: 0;
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: 0.5rem;
	}
	.legend {
		display: flex;
		flex-wrap: wrap;
		gap: 0.25rem 1rem;
		font-size: 0.75rem;
		color: var(--m3c-on-surface-variant);
	}
	.legend span {
		display: inline-flex;
		align-items: center;
		gap: 0.375rem;
	}
	.legend i {
		width: 0.625rem;
		height: 0.625rem;
		border-radius: 0.1875rem;
	}
	.plot {
		display: grid;
		grid-template-columns: auto minmax(0, 1fr);
		grid-template-rows: 11rem auto;
		gap: 0.375rem 0.5rem;
	}
	.yaxis {
		display: flex;
		flex-direction: column;
		justify-content: space-between;
		align-items: flex-end;
		font-size: 0.6875rem;
		line-height: 1;
		color: var(--m3c-on-surface-variant);
		font-variant-numeric: tabular-nums;
		margin: -0.3rem 0;
	}
	svg {
		width: 100%;
		height: 100%;
		display: block;
		overflow: visible;
	}
	.grid {
		stroke: var(--m3c-outline-variant);
		stroke-dasharray: 3 3;
	}
	.axis {
		stroke: var(--m3c-outline-variant);
	}
	.xaxis {
		display: flex;
		justify-content: space-between;
		font-size: 0.6875rem;
		color: var(--m3c-on-surface-variant);
		grid-column: 2;
	}
	details summary {
		cursor: pointer;
		font-size: 0.8125rem;
		color: var(--m3c-primary);
		width: fit-content;
		padding: 0.25rem 0;
	}
	.table-wrap {
		max-height: 18rem;
		overflow: auto;
		margin-top: 0.5rem;
		border-radius: var(--radius-m, 0.75rem);
		border: 1px solid var(--m3c-outline-variant);
	}
	table {
		width: 100%;
		border-collapse: collapse;
		font-size: 0.8125rem;
		font-variant-numeric: tabular-nums;
	}
	th,
	td {
		padding: 0.375rem 0.625rem;
		text-align: right;
		white-space: nowrap;
	}
	th:first-child {
		text-align: left;
		font-weight: 500;
	}
	thead th {
		position: sticky;
		top: 0;
		background: var(--m3c-surface-container);
		font-weight: 500;
	}
	tbody tr:nth-child(even) {
		background: color-mix(in srgb, var(--m3c-on-surface) 4%, transparent);
	}
	.sr-only {
		position: absolute;
		width: 1px;
		height: 1px;
		overflow: hidden;
		clip: rect(0 0 0 0);
		white-space: nowrap;
	}
</style>
