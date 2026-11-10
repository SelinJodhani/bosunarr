import adapter from '@sveltejs/adapter-node';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

/** @type {import('@sveltejs/kit').Config} */
const config = {
	preprocess: vitePreprocess(),
	kit: {
		adapter: adapter({ out: 'build', precompress: true }),
		// Inline small per-component stylesheets: a cold load on a phone fetched 13 render-blocking CSS files.
		inlineStyleThreshold: 6144
	}
};

export default config;
