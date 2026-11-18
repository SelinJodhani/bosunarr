package app.bosunarr.notify

import app.bosunarr.nav.AlbumRoute
import app.bosunarr.nav.MovieRoute
import app.bosunarr.nav.SeriesRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotifyLinksTest {
    @Test fun albumNotificationOpensTheAlbum() {
        assertEquals(AlbumRoute("lid", 12), NotifyLinks.route(NotifyLinks.album("lid", 12)))
    }

    @Test fun movieAndSeriesLinksUnchanged() {
        assertEquals(MovieRoute("rad", 3), NotifyLinks.route(NotifyLinks.movie("rad", 3)))
        assertEquals(SeriesRoute("son", 4), NotifyLinks.route(NotifyLinks.series("son", 4)))
        assertNull(NotifyLinks.route("album/lid/x"))
    }
}
