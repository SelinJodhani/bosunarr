<script lang="ts">
	import { flip } from 'svelte/animate';
	import { tick } from 'svelte';
	import { Button, Dialog, ListItem, Icon, snackbar } from 'm3-svelte';
	import { WIDGETS, spansFor, type WidgetInstance, type WidgetKind, type WidgetSize } from '$lib/widgets/types';
	import WidgetFrame from '$lib/widgets/WidgetFrame.svelte';
	import { reserveFor, seedHeights } from '$lib/widgets/heights';
	import { SERVICE_TYPES } from '$lib/services/catalog';
	import { iAdd, iCheck, iClose, iEdit, iServices } from '$lib/icons';

	let { data } = $props();

	// Local, editable copy of the saved layout.
	// svelte-ignore state_referenced_locally
	let widgets = $state<WidgetInstance[]>(data.widgets);
	let editing = $state(false);
	let pickerOpen = $state(false);
	/** Layout when Edit was pressed, for Cancel. */
	let before: WidgetInstance[] | null = null;
	let highlightId = $state<string | null>(null);

	// svelte-ignore state_referenced_locally
	seedHeights(Object.fromEntries(Object.entries(data.heights).filter(([id]) => data.widgets.some((w) => w.id === id))));

	const serviceTypes = $derived(new Set(data.services.map((s) => s.type)));
	const available = (k: WidgetKind) => WIDGETS[k].needs.length === 0 || WIDGETS[k].needs.some((t) => serviceTypes.has(t));
	const onBoard = $derived(new Set(widgets.map((w) => w.kind)));
	const spans = $derived(spansFor(widgets));

	let saveTimer: ReturnType<typeof setTimeout> | undefined;
	async function save() {
		const r = await fetch('/api/dashboard', { method: 'PUT', body: JSON.stringify({ widgets }) });
		if (!r.ok) snackbar('Could not save the dashboard', {}, true);
	}
	function persist() {
		clearTimeout(saveTimer);
		saveTimer = setTimeout(save, 400);
	}

	function startEditing() {
		before = $state.snapshot(widgets);
		editing = true;
	}
	function done() {
		editing = false;
		before = null;
	}
	function cancel() {
		const prev = before;
		editing = false;
		before = null;
		if (!prev || JSON.stringify(prev) === JSON.stringify($state.snapshot(widgets))) return;
		widgets = prev;
		clearTimeout(saveTimer);
		save();
	}

	function move(i: number, by: number) {
		const j = i + by;
		if (j < 0 || j >= widgets.length) return;
		[widgets[i], widgets[j]] = [widgets[j], widgets[i]];
		persist();
	}
	function resize(i: number) {
		const order: WidgetSize[] = ['s', 'm', 'l'];
		widgets[i].size = order[(order.indexOf(widgets[i].size) + 1) % order.length];
		persist();
	}
	function remove(i: number) {
		const [w] = widgets.splice(i, 1);
		persist();
		snackbar(`Removed ${WIDGETS[w.kind].label}`, { Undo: () => (widgets.splice(i, 0, w), persist()) }, false, 5000);
	}
	async function addWidget(kind: WidgetKind) {
		const id = `${kind}-${Date.now().toString(36)}`;
		widgets.push({ id, kind, size: WIDGETS[kind].defaultSize });
		pickerOpen = false;
		persist();
		await tick();
		// Let the dialog finish closing (it hands focus back to the Add button, which scrolls) first.
		await new Promise((r) => setTimeout(r, 250));
		const el = document.querySelector(`[data-widget="${id}"]`);
		const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
		el?.scrollIntoView({ block: 'center', behavior: reduce ? 'auto' : 'smooth' });
		highlightId = id;
		setTimeout(() => highlightId === id && (highlightId = null), 1600);
	}
</script>

<svelte:head><title>Dashboard · Bosunarr</title></svelte:head>

<header>
	<h1 class="title-large">Dashboard</h1>
	{#if data.services.length > 0}
		<div class="tools">
			{#if editing}
				<!-- Icon-only on phones so the row never wraps; labelled from 40rem up. -->
				<span class="compact"><Button variant="text" iconType="full" aria-label="Cancel editing" title="Cancel" onclick={cancel}><Icon icon={iClose} /></Button></span>
				<span class="wide"><Button variant="text" onclick={cancel}>Cancel</Button></span>
				<span class="compact"><Button variant="tonal" iconType="full" aria-label="Add widget" title="Add widget" onclick={() => (pickerOpen = true)}><Icon icon={iAdd} /></Button></span>
				<span class="wide"><Button variant="tonal" onclick={() => (pickerOpen = true)}><Icon icon={iAdd} /> Add widget</Button></span>
				<Button variant="filled" onclick={done}><Icon icon={iCheck} /> Done</Button>
			{:else}
				<Button variant="text" onclick={startEditing}><Icon icon={iEdit} /> Edit</Button>
			{/if}
		</div>
	{/if}
</header>

{#if data.services.length === 0}
	<section class="empty">
		<Icon icon={iServices} size={40} />
		<h2 class="title-medium">Nothing to show yet</h2>
		<p class="body-small">Add Sonarr, Radarr, qBittorrent, Jellyfin and friends in Settings and widgets appear here.</p>
		<Button variant="tonal" href="/settings"><Icon icon={iAdd} /> Add a service</Button>
	</section>
{:else}
	<div class="grid" class:editing>
		{#each widgets as w, i (w.id)}
			<div class="cell" style:--span8={spans[i].span8} style:--span12={spans[i].span12} data-widget={w.id} animate:flip={{ duration: 220 }}>
				<WidgetFrame
					widget={w}
					reserve={reserveFor(data.heights, w)}
					highlight={highlightId === w.id}
					{editing}
					missing={!available(w.kind)}
					onup={() => move(i, -1)}
					ondown={() => move(i, 1)}
					onresize={() => resize(i)}
					onremove={() => remove(i)}
					first={i === 0}
					last={i === widgets.length - 1}
				/>
			</div>
		{/each}
	</div>
{/if}

<Dialog headline="Add a widget" bind:open={pickerOpen}>
	<ul class="picker">
		{#each Object.entries(WIDGETS) as [kind, w] (kind)}
			{@const ok = available(kind as WidgetKind)}
			{@const added = onBoard.has(kind as WidgetKind)}
			<!-- ListItem renders its own <li>. -->
			<ListItem
				headline={w.label}
				supporting={added ? 'Already on your dashboard' : ok ? w.description : `Add ${w.needs.map((t) => SERVICE_TYPES[t].label).join(' or ')} first`}
				onclick={() => ok && !added && addWidget(kind as WidgetKind)}
				aria-disabled={!ok || added}
			>
				{#snippet trailing()}
					{#if added}<Icon icon={iCheck} />{/if}
				{/snippet}
			</ListItem>
		{/each}
	</ul>
	{#snippet buttons()}
		<Button variant="text" onclick={() => (pickerOpen = false)}>Close</Button>
	{/snippet}
</Dialog>

<style>
	header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 0.5rem;
		height: 2.5rem;
		margin-bottom: 0.75rem;
	}
	h1 {
		margin: 0;
		white-space: nowrap;
	}
	.tools {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		white-space: nowrap;
	}
	.wide {
		display: none;
	}
	.compact {
		display: contents;
	}
	@media (width >= 40rem) {
		.wide {
			display: contents;
		}
		.compact {
			display: none;
		}
	}
	.grid {
		display: grid;
		gap: var(--gap);
		grid-template-columns: minmax(0, 1fr);
	}
	.cell {
		min-width: 0;
	}
	/* Spans are computed per layout (types.ts spansFor) so every row is filled edge to edge. */
	@media (width >= 40rem) {
		.grid {
			grid-template-columns: repeat(8, minmax(0, 1fr));
		}
		.cell {
			grid-column: span var(--span8);
		}
	}
	@media (width >= 75rem) {
		.grid {
			grid-template-columns: repeat(12, minmax(0, 1fr));
		}
		.cell {
			grid-column: span var(--span12);
		}
	}
	.empty {
		display: flex;
		flex-direction: column;
		align-items: center;
		text-align: center;
		gap: 0.5rem;
		max-width: 36rem;
		margin: 0 auto;
		padding: 2.5rem 1rem;
		color: var(--m3c-on-surface-variant);
	}
	.empty h2 {
		margin: 0.5rem 0 0;
		color: var(--m3c-on-surface);
	}
	.empty p {
		margin: 0 0 0.5rem;
	}
	.picker {
		list-style: none;
		padding: 0;
		margin: 0 -1.5rem;
	}
	.picker :global([aria-disabled='true']) {
		opacity: 0.5;
		cursor: default;
	}
</style>
