<script lang="ts">
	import type { SubLang } from './types';
	let { langs }: { langs: SubLang[] } = $props();
</script>

<span class="tags">
	{#each langs as l, i (i)}
		<span class="tag" title={l.name + (l.hi ? ' (hearing impaired)' : '') + (l.forced ? ' (forced)' : '')}>
			{l.name}{#if l.hi}<b>HI</b>{/if}{#if l.forced}<b>F</b>{/if}
		</span>
	{/each}
</span>

<style>
	.tags {
		display: flex;
		flex-wrap: wrap;
		gap: 0.25rem;
		min-width: 0;
	}
	.tag {
		display: inline-flex;
		align-items: center;
		gap: 0.25rem;
		padding: 0.125rem 0.5rem;
		border-radius: 0.5rem;
		background: var(--m3c-secondary-container);
		color: var(--m3c-on-secondary-container);
		font-size: 0.75rem;
		line-height: 1.25rem;
		max-width: 100%;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	b {
		font-weight: 600;
		font-size: 0.6875rem;
		opacity: 0.8;
	}
</style>
