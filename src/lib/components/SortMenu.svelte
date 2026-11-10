<script lang="ts">
	// Compact sort control: a tonal icon button that opens an M3 menu. Saves a full row on phones.
	import { Button, Icon, Menu, MenuItem } from 'm3-svelte';
	import { iCheck, iSort } from '$lib/icons';
	import { useBackClose } from './backClose.svelte';

	// `defaultValue`: when the current sort differs, the button turns filled and shows a dot so a custom sort is visible.
	let {
		options,
		value = $bindable(),
		defaultValue
	}: { options: Array<{ value: string; text: string }>; value: string; defaultValue?: string } = $props();

	let open = $state(false);
	let root = $state<HTMLElement>();
	const current = $derived(options.find((o) => o.value === value)?.text ?? '');
	const custom = $derived(defaultValue != null && value !== defaultValue);

	// Android Back closes the menu instead of leaving the page.
	useBackClose(() => open, () => (open = false));

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
	<Button variant={custom ? 'filled' : 'tonal'} iconType="full" aria-label={`Sort: ${current}`} aria-haspopup="menu" aria-expanded={open} onclick={() => (open = !open)}>
		<Icon icon={iSort} />
	</Button>
	{#if custom}<span class="dot" aria-hidden="true"></span>{/if}
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
	.dot {
		position: absolute;
		top: 0.125rem;
		right: 0.125rem;
		width: 0.5rem;
		height: 0.5rem;
		border-radius: 50%;
		background: var(--m3c-tertiary);
		box-shadow: 0 0 0 2px var(--m3c-surface);
		pointer-events: none;
	}
	.pop {
		position: absolute;
		top: calc(100% + 0.25rem);
		right: 0;
		z-index: 20;
		min-width: 12rem;
	}
</style>
