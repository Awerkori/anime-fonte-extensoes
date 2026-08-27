package eu.kanade.tachiyomi.animeextension.pt.redecanais.videolist

import okhttp3.Interceptor
import okhttp3.Response

class VideoListBootstrapInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(HEADER) == null) return chain.proceed(request)

        val cleanRequest = request.newBuilder()
            .removeHeader(HEADER)
            .build()

        return chain.proceed(cleanRequest)
    }

    companion object {
        const val HEADER = "X-RedeCanais-Video-List"
    }
}
