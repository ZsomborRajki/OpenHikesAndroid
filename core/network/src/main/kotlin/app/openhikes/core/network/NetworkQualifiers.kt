package app.openhikes.core.network

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TileOkHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PreviewTileOkHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ManifestOkHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadOkHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TileServerBaseUrl

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VectorArchivesFile

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RoutingSquaresDir

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlaceSquaresDir

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OverpassPlacesDir

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OverpassEndpointUrl

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ElevationSquaresDir
