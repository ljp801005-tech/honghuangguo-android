package com.huangguo.mobile;

import android.content.Context;

import androidx.media3.common.C;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.hls.HlsMediaSource;

import java.io.File;
import java.net.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/** Shared by playback, the next episode and seek previews; never caches mutable playlists. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class VideoPlaybackCache {
    static final long MAX_BYTES = 128L * 1024 * 1024;
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36";
    private static SimpleCache cache;
    /** CDN 上的 m3u8/分片按来源站点防盗链，取流时要带上与页面一致的 Referer。 */
    private static volatile String referer = "";
    private static volatile String origin = "";

    static void setReferer(String pageOrigin) {
        if (pageOrigin == null || pageOrigin.isEmpty()) return;
        referer = pageOrigin;
        origin = pageOrigin.endsWith("/") ? pageOrigin.substring(0, pageOrigin.length() - 1) : pageOrigin;
    }

    private static synchronized SimpleCache get(Context context) {
        if (cache == null) {
            Context app = context.getApplicationContext();
            cache = new SimpleCache(new File(app.getCacheDir(), "video-segments"),
                    new LeastRecentlyUsedCacheEvictor(MAX_BYTES), new StandaloneDatabaseProvider(app));
        }
        return cache;
    }

    /**
     * 取流走 OkHttp：DefaultHttpDataSource 不支持指定代理，而站点分片域名（hzjvlo.cn）
     * 在家里直连是通的、在部分网络中不通，必须和页面请求走同一个出口。
     */
    private static DataSource.Factory http(Context context) {
        OkHttpClient.Builder client = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true);
        Proxy proxy = NetworkRoute.proxy(context);
        if (proxy != null) client.proxy(proxy);
        OkHttpDataSource.Factory factory = new OkHttpDataSource.Factory(client.build())
                .setUserAgent(USER_AGENT);
        if (!referer.isEmpty()) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", referer);
            headers.put("Origin", origin);
            factory.setDefaultRequestProperties(headers);
        }
        return factory;
    }

    static HlsMediaSource.Factory sources(Context context) {
        DataSource.Factory upstream = new DefaultDataSource.Factory(context, http(context));
        CacheDataSource.Factory cached = new CacheDataSource.Factory().setCache(get(context))
                .setUpstreamDataSourceFactory(upstream).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
        return new HlsMediaSource.Factory(type -> type == C.DATA_TYPE_MANIFEST
                ? upstream.createDataSource() : cached.createDataSource());
    }
}
