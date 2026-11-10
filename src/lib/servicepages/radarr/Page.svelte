<script lang="ts">
	import ArrPage from '../arr/ArrPage.svelte';
	import type { ServicePageProps } from '../types';

	let props: ServicePageProps = $props();
</script>

<ArrPage {...props} />
