<script lang="ts">
	import '../app.css';
	import { navigating, page } from '$app/state';
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

	/**
	 * Computed colours come back in the theme's space (`oklab(…)`), which Android's status bar ignores:
	 * paint it on a 1×1 canvas and read back sRGB as #rrggbb.
	 */
	let probe: CanvasRenderingContext2D | null | undefined;
	function toHex(color: string): string | null {
		if (/^#[\da-f]{6}$/i.test(color)) return color;
		probe ??= Object.assign(document.createElement('canvas'), { width: 1, height: 1 }).getContext('2d', { willReadFrequently: true });
		if (!probe) return null;
		probe.clearRect(0, 0, 1, 1);
		probe.fillStyle = '#000';
		probe.fillStyle = color;
		probe.fillRect(0, 0, 1, 1);
		const [r, g, b] = probe.getImageData(0, 0, 1, 1).data;
		return '#' + [r, g, b].map((n) => n.toString(16).padStart(2, '0')).join('');
	}

	// Keep the browser/PWA status bar colour in step with the page background (light, dark or system).
	$effect(() => {
		const meta = document.querySelector('meta[name="theme-color"]');
		if (!meta) return;
		const sync = () => {
			const hex = toHex(getComputedStyle(document.body).backgroundColor);
			if (hex) meta.setAttribute('content', hex);
		};
		sync();
		const mq = matchMedia('(prefers-color-scheme: dark)');
		const mo = new MutationObserver(sync);
		mo.observe(document.documentElement, { attributes: true, attributeFilter: ['data-scheme', 'style'] });
		mq.addEventListener('change', sync);
		return () => {
			mo.disconnect();
			mq.removeEventListener('change', sync);
		};
	});

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
<!-- Wrapper lifts snackbars above the phone nav bar (m3-svelte uses z-index 3; the nav is 10). -->
<div class="snack"><Snackbar /></div>
{#if navigating.to}<div class="nav-progress" role="progressbar" aria-label="Loading page"></div>{/if}

<style>
	.snack :global(.holder) {
		z-index: 30;
	}
	@media (width < 52.5rem) {
		.snack {
			--m3v-bottom-offset: calc(4rem + env(safe-area-inset-bottom));
		}
	}
	/* Thin indeterminate bar while a page loads; delayed so fast navigations don't flash it. */
	.nav-progress {
		position: fixed;
		inset: 0 0 auto;
		height: 3px;
		z-index: 40;
		overflow: hidden;
		pointer-events: none;
		opacity: 0;
		animation: show 0s 150ms forwards;
	}
	.nav-progress::before {
		content: '';
		position: absolute;
		inset: 0;
		width: 40%;
		background: var(--m3c-primary);
		border-radius: 3px;
		animation: slide 1s ease-in-out infinite;
	}
	@keyframes show {
		to {
			opacity: 1;
		}
	}
	@keyframes slide {
		from {
			transform: translateX(-100%);
		}
		to {
			transform: translateX(250%);
		}
	}
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
	/* Phones: the bar's colour continues below it, so overscroll and any gesture-nav area Chrome lets the page draw
	   behind (edge-to-edge, viewport-fit=cover) show the nav colour instead of the page or a dark gap. */
	@media (width < 52.5rem) {
		/* A shadow, not a pseudo-element: it paints below the bar without adding scrollable overflow. */
		.nav {
			box-shadow: 0 50vh 0 50vh var(--m3c-surface-container);
		}
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
