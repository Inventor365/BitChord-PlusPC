package com.music.bitchord.data

import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.music.bitchord.data.model.youTubeThumbnailFallback
import java.util.concurrent.ConcurrentHashMap

/**
 * Falls back to a YouTube video's `hqdefault` when the larger cover
 * [artworkAt][com.music.bitchord.data.model.artworkAt] asked for isn't there.
 *
 * A URL YouTube answers 404 for is remembered for the process, so the same cover isn't
 * asked for in vain on every recomposition. Every other request passes through
 * untouched.
 */
class YouTubeThumbnailFallback : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = request.data as? String ?: return chain.proceed()
        val fallback = youTubeThumbnailFallback(url) ?: return chain.proceed()
        if (url !in missing) {
            val result = chain.proceed()
            if (result !is ErrorResult) return result
            if ((result.throwable as? HttpException)?.response?.code == 404) missing.add(url)
        }
        return chain.withRequest(request.newBuilder().data(fallback).build()).proceed()
    }

    private companion object {
        val missing: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
}
