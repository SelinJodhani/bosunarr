<script lang="ts">
	import { page } from '$app/state';
	import DiscoverDetail from '$lib/discover/DiscoverDetail.svelte';
</script>

{#key page.url.pathname}
	<DiscoverDetail service={page.params.service ?? ''} type={page.params.type ?? ''} id={page.params.id ?? ''} />
{/key}
