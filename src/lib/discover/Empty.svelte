<script lang="ts">
	import type { ComponentProps, Snippet } from 'svelte';
	import { Icon } from 'm3-svelte';

	let { icon, title, hint, action }: { icon: ComponentProps<typeof Icon>['icon']; title: string; hint?: string; action?: Snippet } = $props();
</script>

<div class="empty">
	<Icon {icon} size={40} />
	<p class="title-medium">{title}</p>
	{#if hint}<p class="body-small hint">{hint}</p>{/if}
	{#if action}<div class="action">{@render action()}</div>{/if}
</div>

<style>
	.empty {
		display: grid;
		justify-items: center;
		gap: 0.5rem;
		padding: 2.5rem 1rem;
		text-align: center;
		color: var(--m3c-on-surface-variant);
	}
	p {
		margin: 0;
	}
	.title-medium {
		color: var(--m3c-on-surface);
	}
	.action {
		margin-top: 0.5rem;
	}
</style>
