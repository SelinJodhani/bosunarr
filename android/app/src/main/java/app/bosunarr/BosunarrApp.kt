package app.bosunarr

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade

class BosunarrApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        // Posters come from the arrs' MediaCover / Seerr's image proxy; they barely change, so cache hard.
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.imageHttp })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
        .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("images")).maxSizeBytes(256L * 1024 * 1024).build() }
        .crossfade(180)
        .build()

    companion object {
        lateinit var container: AppContainer
            private set
    }
}

/** The app-wide container (services, HTTP client, store). */
val app: AppContainer get() = BosunarrApp.container
