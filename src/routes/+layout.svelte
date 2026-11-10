<script lang="ts">
	import '../app.css';
	import { page } from '$app/state';
	import { NavCMLX, NavCMLXItem, Snackbar } from 'm3-svelte';
	import { iActivity, iCalendar, iDashboard, iDiscover, iLibrary, iSettings } from '$lib/icons';

	let { children, data } = $props();

	const nav = [
		{ href: '/', text: 'Dashboard', icon: iDashboard },
		{ href: '/library', text: 'Library', icon: iLibrary },
		{ href: '/discover', text: 'Discover', icon: iDiscover },
		{ href: '/calendar', text: 'Calendar', icon: iCalendar },
		{ href: '/activity', text: 'Activity', icon: iActivity },
		{ href: '/settings', text: 'Settings', icon: iSettings }
	];

	const bare = $derived(['/login', '/setup'].includes(page.url.pathname));
	// Service screens (/services/…) are opened from Settings and the dashboard, so they light up Settings.
	const isActive = (href: string) =>
		href === '/' ? page.url.pathname === '/' : page.url.pathname.startsWith(href) || (href === '/settings' && page.url.pathname.startsWith('/services'));
</script>

{#if bare || !data.user}
	{@render children()}
{:else}
	<div class="shell">
		<div class="nav">
			<NavCMLX variant="auto">
				{#each nav as item (item.href)}
					<NavCMLXItem variant="auto" href={item.href} icon={item.icon} text={item.text} selected={isActive(item.href)} />
				{/each}
			</NavCMLX>
		</div>
		<main>
			{@render children()}
		</main>
	</div>
{/if}
<Snackbar />

<style>
	.shell {
		min-height: 100dvh;
		display: flex;
		flex-direction: column-reverse;
	}
	.nav {
		position: sticky;
		bottom: 0;
		z-index: 10;
		padding-bottom: env(safe-area-inset-bottom);
		background: var(--m3c-surface-container);
	}
	main {
		flex: 1;
		min-width: 0;
		padding: 1rem max(1rem, env(safe-area-inset-left)) 1.5rem;
	}
	/* Compact bar: six destinations share the width evenly. */
	@media (width < 52.5rem) {
		.nav :global(.m3-container) {
			justify-content: stretch;
		}
		.nav :global(.m3-container > a) {
			flex: 1 1 0;
			min-width: 0;
		}
		/* Icons only on phones; the label stays in the DOM (font-size 0) so screen readers still announce it.
		   Fixed 64 px bar with the icons centred: the hidden label must not leave an empty band below them. */
		.nav :global(.m3-container) {
			height: 4rem;
			align-items: center;
			padding-block: 0;
		}
		.nav :global(.m3-container > a) {
			height: 100%;
			padding-block: 0;
			display: flex;
			align-items: center;
			justify-content: center;
		}
		.nav :global(.m3-container > a .content) {
			font-size: 0;
			line-height: 0;
			gap: 0;
			padding-block: 0;
		}
	}
	@media (width >= 52.5rem) {
		.shell {
			flex-direction: row;
		}
		.nav {
			position: sticky;
			top: 0;
			height: 100dvh;
			padding: 1rem 0 0;
			background: transparent;
		}
		main {
			padding: 1.5rem 1.5rem 2rem 0.5rem;
		}
	}
</style>
