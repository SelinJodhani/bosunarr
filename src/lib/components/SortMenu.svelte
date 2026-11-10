<script lang="ts">
	// Compact sort control: a tonal icon button that opens an M3 menu. Saves a full row on phones.
	import { Button, Icon, Menu, MenuItem } from 'm3-svelte';
	import { iCheck, iSort } from '$lib/icons';

	let { options, value = $bindable() }: { options: Array<{ value: string; text: string }>; value: string } = $props();

	let open = $state(false);
	let root = $state<HTMLElement>();
	const current = $derived(options.find((o) => o.value === value)?.text ?? '');

	function pick(v: string) {
		value = v;
		open = false;
	}

	$effect(() => {
		if (!open) return;
		const onDown = (e: PointerEvent) => root && !root.contains(e.target as Node) && (open = false);
		const onKey = (e: KeyboardEvent) => e.key === 'Escape' && (open = false);
		document.addEventListener('pointerdown', onDown);
		document.addEventListener('keydown', onKey);
		return () => {
			document.removeEventListener('pointerdown', onDown);
			document.removeEventListener('keydown', onKey);
		};
	});
</script>

<div class="sort" bind:this={root}>
	<Button variant="tonal" iconType="full" aria-label={`Sort: ${current}`} aria-haspopup="menu" aria-expanded={open} onclick={() => (open = !open)}>
		<Icon icon={iSort} />
	</Button>
	{#if open}
		<div class="pop" role="menu" aria-label="Sort by">
			<Menu>
				{#each options as o (o.value)}
					<MenuItem icon={o.value === value ? iCheck : 'space'} onclick={() => pick(o.value)}>{o.text}</MenuItem>
				{/each}
			</Menu>
		</div>
	{/if}
</div>

<style>
	.sort {
		position: relative;
		flex: none;
		align-self: center;
	}
	.pop {
		position: absolute;
		top: calc(100% + 0.25rem);
		right: 0;
		z-index: 20;
		min-width: 12rem;
	}
</style>
