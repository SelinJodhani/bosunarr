<script lang="ts">
	import { flip } from 'svelte/animate';
	import { Button, Dialog, ListItem, Icon, snackbar } from 'm3-svelte';
	import { WIDGETS, type WidgetInstance, type WidgetKind, type WidgetSize } from '$lib/widgets/types';
	import WidgetFrame from '$lib/widgets/WidgetFrame.svelte';
	import { SERVICE_TYPES } from '$lib/services/catalog';
	import { iAdd, iCheck, iEdit } from '$lib/icons';

	let { data } = $props();

	// Local, editable copy of the saved layout.
	// svelte-ignore state_referenced_locally
	let widgets = $state<WidgetInstance[]>(data.widgets);
	let editing = $state(false);
	let pickerOpen = $state(false);

	const serviceTypes = $derived(new Set(data.services.map((s) => s.type)));
	const available = (k: WidgetKind) => WIDGETS[k].needs.length === 0 || WIDGETS[k].needs.some((t) => serviceTypes.has(t));

	let saveTimer: ReturnType<typeof setTimeout>;
	function persist() {
		clearTimeout(saveTimer);
		saveTimer = setTimeout(async () => {
			const r = await fetch('/api/dashboard', { method: 'PUT', body: JSON.stringify({ widgets }) });
			if (!r.ok) snackbar('Could not save the dashboard', {}, true);
		}, 400);
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
	function addWidget(kind: WidgetKind) {
		widgets.push({ id: `${kind}-${Date.now().toString(36)}`, kind, size: WIDGETS[kind].defaultSize });
		pickerOpen = false;
		persist();
	}
</script>

<svelte:head><title>Dashboard · Bosunarr</title></svelte:head>

<header>
	<h1 class="title-large">Dashboard</h1>
	<div class="tools">
		{#if editing}
			<Button variant="tonal" onclick={() => (pickerOpen = true)}><Icon icon={iAdd} /> Add widget</Button>
			<Button variant="filled" onclick={() => (editing = false)}><Icon icon={iCheck} /> Done</Button>
		{:else}
			<Button variant="text" onclick={() => (editing = true)}><Icon icon={iEdit} /> Edit</Button>
		{/if}
	</div>
</header>

{#if data.services.length === 0}
	<section class="empty">
		<h2 class="title-medium">Nothing to show yet</h2>
		<p>Add Sonarr, Radarr, qBittorrent, Jellyfin and friends in Settings and widgets appear here.</p>
		<Button variant="filled" href="/settings"><Icon icon={iAdd} /> Add a service</Button>
	</section>
{:else}
	<div class="grid" class:editing>
		{#each widgets as w, i (w.id)}
			<div class="cell size-{w.size}" animate:flip={{ duration: 220 }}>
				<WidgetFrame
					widget={w}
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
			<!-- ListItem renders its own <li>. -->
			<ListItem
				headline={w.label}
				supporting={ok ? w.description : `Add ${w.needs.map((t) => SERVICE_TYPES[t].label).join(' or ')} first`}
				onclick={() => ok && addWidget(kind as WidgetKind)}
				aria-disabled={!ok}
			/>
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
		gap: 1rem;
		margin-bottom: 1rem;
		min-height: 2.5rem;
	}
	.tools {
		display: flex;
		gap: 0.5rem;
	}
	.grid {
		display: grid;
		gap: var(--gap);
		grid-template-columns: repeat(4, minmax(0, 1fr));
		grid-auto-flow: dense;
	}
	.cell {
		grid-column: span 4;
		min-width: 0;
	}
	@media (width >= 40rem) {
		.grid {
			grid-template-columns: repeat(8, minmax(0, 1fr));
		}
		.size-s {
			grid-column: span 4;
		}
		.size-m,
		.size-l {
			grid-column: span 8;
		}
	}
	@media (width >= 75rem) {
		.grid {
			grid-template-columns: repeat(12, minmax(0, 1fr));
		}
		.size-s {
			grid-column: span 3;
		}
		.size-m {
			grid-column: span 6;
		}
		.size-l {
			grid-column: span 12;
		}
	}
	.empty {
		max-width: 36rem;
		padding: 2rem 1.5rem;
		border-radius: var(--radius-l);
		background: var(--m3c-surface-container-low);
	}
	.empty p {
		color: var(--m3c-on-surface-variant);
	}
	.picker {
		list-style: none;
		padding: 0;
		margin: 0 -1.5rem;
	}
	.picker :global([aria-disabled='true']) {
		opacity: 0.5;
	}
</style>
