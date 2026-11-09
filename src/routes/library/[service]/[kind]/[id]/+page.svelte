<script lang="ts">
	import { page } from '$app/state';
	import DetailView from '$lib/library/DetailView.svelte';
</script>

{#key page.url.pathname}
	<DetailView service={page.params.service ?? ''} kind={page.params.kind ?? ''} id={page.params.id ?? ''} />
{/key}
