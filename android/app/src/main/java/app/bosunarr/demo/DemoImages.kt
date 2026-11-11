package app.bosunarr.demo

/**
 * Posters and backdrops for the demo, from `assets/demo/images/<key>-poster.webp` / `<key>-fanart.webp`, served at
 * the paths the app asks each service for:
 * - Sonarr / Radarr: `/MediaCover/<id>/poster-500.jpg`, `/MediaCover/lookup/<key>/poster-250.jpg` (search results);
 * - Jellyseerr: `/imageproxy/tmdb/t/p/w342/<key>.jpg` (backdrops: `<key>-bg.jpg`);
 * - Jellyfin: `/Items/<id>/Images/Primary`.
 */
internal object DemoImages {
    private val ARR = Regex("^/MediaCover/(\\d+|lookup/[\\w-]+)/(poster|fanart|banner)(?:-\\d+)?\\.jpg$")
    private val SEERR = Regex("^/imageproxy/tmdb/t/p/\\w+/([\\w-]+?)(-bg)?\\.jpg$")
    private val JELLYFIN = Regex("^/Items/([0-9a-f]{32})/Images/(\\w+)")

    fun serve(r: DemoRequest): DemoResponse? {
        if (r.method != "GET") return null
        val p = r.path
        val (key, fanart) = ARR.find(p)?.let { m ->
            val owner = m.groupValues[1]
            val w = DemoWorld.get()
            val key = if (owner.startsWith("lookup/")) owner.removePrefix("lookup/") else synchronized(w) {
                val id = owner.toInt()
                if (r.service == "sonarr") w.seriesById(id)?.s?.key else w.movie(id)?.m?.key
            }
            key to (m.groupValues[2] != "poster")
        } ?: SEERR.find(p)?.let { m -> m.groupValues[1] to m.groupValues[2].isNotEmpty() }
        ?: JELLYFIN.find(p)?.let { m ->
            val w = DemoWorld.get()
            synchronized(w) { DemoLibrary.imageKey(w, m.groupValues[1]) } to (m.groupValues[2] == "Backdrop")
        }
        ?: return null
        if (key == null) return DemoResponse.notFound("No image")
        val bytes = (if (fanart) DemoAssets.bytes("images/$key-fanart.webp") else null)
            ?: DemoAssets.bytes("images/$key-poster.webp")
            ?: return DemoResponse.notFound("No image")
        return DemoResponse(200, bytes, "image/webp", mapOf("Cache-Control" to "public, max-age=86400"))
    }
}
