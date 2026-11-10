<script lang="ts">
	import type { Tone } from './client';

	/** `size`: 's' = poster overlay / list rows, 'm' = detail page chips. */
	let { tone, label, size = 's' }: { tone: Tone; label: string; size?: 's' | 'm' } = $props();
</script>

<span class="badge {tone} {size}">{label}</span>

<style>
	.badge {
		display: inline-flex;
		align-items: center;
		flex: none;
		white-space: nowrap;
		font-weight: 500;
		border-radius: 0.5rem;
	}
	.s {
		height: 1.375rem;
		padding: 0 0.5rem;
		font-size: 0.75rem;
	}
	.m {
		height: 1.75rem;
		padding: 0 0.625rem;
		font-size: 0.8125rem;
	}
	.ok {
		background: var(--m3c-primary);
		color: var(--m3c-on-primary);
	}
	.partial {
		background: var(--m3c-secondary-container);
		color: var(--m3c-on-secondary-container);
	}
	.progress {
		background: var(--m3c-tertiary-container);
		color: var(--m3c-on-tertiary-container);
	}
	.wait {
		background: var(--m3c-surface-container-highest);
		color: var(--m3c-on-surface);
		box-shadow: inset 0 0 0 1px var(--m3c-outline);
	}
	.bad {
		background: var(--m3c-error-container);
		color: var(--m3c-on-error-container);
	}
</style>
