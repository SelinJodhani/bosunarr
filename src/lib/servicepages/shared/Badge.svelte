<script lang="ts">
	// Small status label. ok = filled secondary, off = outlined grey, bad = error, warn = tertiary.
	import type { Snippet } from 'svelte';
	import type { Tone } from './types';

	let { tone = 'off', title, children }: { tone?: Tone; title?: string; children: Snippet } = $props();
</script>

<span class="badge b-{tone}" {title}>{@render children()}</span>

<style>
	.badge {
		flex: none;
		display: inline-flex;
		align-items: center;
		height: 1.5rem;
		padding: 0 0.5rem;
		border-radius: 0.5rem;
		font-size: 0.75rem;
		font-weight: 500;
		white-space: nowrap;
		border: 1px solid transparent;
	}
	.b-ok {
		background: var(--m3c-secondary-container);
		color: var(--m3c-on-secondary-container);
	}
	.b-info {
		background: var(--m3c-primary-container);
		color: var(--m3c-on-primary-container);
	}
	.b-warn {
		background: var(--m3c-tertiary-container);
		color: var(--m3c-on-tertiary-container);
	}
	.b-bad {
		background: var(--m3c-error-container);
		color: var(--m3c-on-error-container);
	}
	.b-off {
		border-color: var(--m3c-outline-variant);
		color: var(--m3c-on-surface-variant);
	}
</style>
