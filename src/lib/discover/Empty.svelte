<script lang="ts">
	// Look comes from the global `.empty-state` (app.css), shared with Activity, service screens and widgets.
	import type { ComponentProps, Snippet } from 'svelte';
	import { Icon } from 'm3-svelte';

	let { icon, title, hint, action }: { icon: ComponentProps<typeof Icon>['icon']; title: string; hint?: string; action?: Snippet } = $props();
</script>

<div class="empty-state">
	<span class="es-icon"><Icon {icon} size={40} /></span>
	<p class="es-title">{title}</p>
	{#if hint}<p class="es-hint">{hint}</p>{/if}
	{#if action}<div class="es-action">{@render action()}</div>{/if}
</div>
