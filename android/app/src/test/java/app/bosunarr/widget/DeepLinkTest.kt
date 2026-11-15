package app.bosunarr.widget

import app.bosunarr.nav.AlbumRoute
import app.bosunarr.nav.MovieRoute
import app.bosunarr.nav.SeriesRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinkTest {
    @Test fun albumLinkOpensTheAlbum() {
        assertEquals(AlbumRoute("lid", 42), DeepLink.route(DeepLink.album("lid", 42)))
    }

    @Test fun seriesAndMovieLinksStillWork() {
        assertEquals(SeriesRoute("son", 7), DeepLink.route(DeepLink.series("son", 7)))
        assertEquals(MovieRoute("rad", 9), DeepLink.route(DeepLink.movie("rad", 9)))
    }

    @Test fun malformedAlbumLinkIsIgnored() {
        assertNull(DeepLink.route("album:lid:notanumber"))
    }
}
