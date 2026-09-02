package eu.kanade.tachiyomi.animeextension.pt.redecanais.videolist

import okhttp3.Interceptor
import okhttp3.Response

class VideoListBootstrapInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(HEADER) == null) return chain.proceed(request)

        val originalRequest = request.newBuilder()
            .removeHeader(HEADER)
            .build()
        val cleanUrl = request.url.newBuilder()
            .removeAllQueryParameters(DUBBED_AUDIO_PARAM)
            .removeAllQueryParameters(SUBBED_AUDIO_PARAM)
            .removeAllQueryParameters(PARENT_PAGE_PARAM)
            .build()
        val cleanRequest = originalRequest.newBuilder()
            .url(cleanUrl)
            .build()

        return chain.proceed(cleanRequest)
            .newBuilder()
            .request(originalRequest)
            .build()
    }

    companion object {
        const val HEADER = "X-RedeCanais-Video-List"
        private const val DUBBED_AUDIO_PARAM = "rc_dublado"
        private const val SUBBED_AUDIO_PARAM = "rc_legendado"
        private const val PARENT_PAGE_PARAM = "rc_parent"
    }
}
