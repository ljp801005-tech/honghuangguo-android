package com.huangguo.mobile;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 站点把播放地址改成页面 JS 运行时插入：服务端 HTML 里 videoSrc 是空串，也没有 m3u8，
 * 地址由页面脚本向 /api/playback/request 换回来再塞进 &lt;video&gt;&lt;source&gt;。
 * 直接抓 HTML 必然拿不到，所以这里用一块藏在界面最底层的 WebView 真实跑一遍页面脚本
 * （和浏览器行为完全一致，不发任何伪造请求），从 .m3u8 网络请求或 &lt;source src&gt; 取回地址。
 */
final class EpisodeSourceResolver {
    private static final String TAG = "HuangGuo";
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final long POLL_INTERVAL_MS = 700L;
    private static final Map<String, Entry> cache = new ConcurrentHashMap<>();
    /** 同一时刻只跑一块 WebView：站点有频率风控，并行多个反而更容易被掐。 */
    private static final Object gate = new Object();

    private static final String PROBE_JS = "(function(){var v=document.querySelector('video');"
            + "if(!v)return '';var s=v.querySelector('source');"
            + "return (s&&s.src)||v.currentSrc||v.src||'';})()";

    private EpisodeSourceResolver() {}

    private static final class Entry {
        final String url;
        final long at;

        Entry(String url) {
            this.url = url;
            this.at = System.currentTimeMillis();
        }
    }

    static String cached(String pageUrl) {
        Entry entry = cache.get(pageUrl);
        if (entry == null) return null;
        if (System.currentTimeMillis() - entry.at > CACHE_TTL_MS) {
            cache.remove(pageUrl);
            return null;
        }
        return entry.url;
    }

    static void forget(String pageUrl) { cache.remove(pageUrl); }

    static Map<String, String> browserHeaders(String userAgent, String referer) {
        Map<String, String> headers = new HashMap<>();
        if (userAgent != null && !userAgent.isEmpty()) headers.put("User-Agent", userAgent);
        if (referer != null && !referer.isEmpty()) headers.put("Referer", referer);
        return headers;
    }

    /**
     * 阻塞直到拿到播放地址或超时。必须在非主线程调用。
     *
     * @return 可直接交给 ExoPlayer 的 HLS 地址，失败返回 null
     */
    static String resolve(Context host, String pageUrl, Map<String, String> headers, long timeoutMs) {
        String hit = cached(pageUrl);
        if (hit != null) return hit;
        synchronized (gate) {
            hit = cached(pageUrl);
            if (hit != null) return hit;
            long start = System.currentTimeMillis();
            String resolved = load(host, pageUrl, headers, timeoutMs);
            if (resolved != null) {
                cache.put(pageUrl, new Entry(resolved));
                Log.d(TAG, "离屏 WebView 取到播放地址（" + (System.currentTimeMillis() - start) + "ms）");
            } else {
                Log.w(TAG, "离屏 WebView 未能取到播放地址：" + pageUrl);
            }
            return resolved;
        }
    }

    private static String load(Context host, String pageUrl, Map<String, String> headers, long timeoutMs) {
        Handler main = new Handler(Looper.getMainLooper());
        CountDownLatch created = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<WebView> holder = new AtomicReference<>();
        AtomicReference<String> found = new AtomicReference<>();
        AtomicBoolean settled = new AtomicBoolean(false);
        long deadline = System.currentTimeMillis() + timeoutMs;

        main.post(() -> {
            try {
                // WebView 不读应用层代理，先把代理挂上，否则分集页和 /api/playback 请求会走直连被掐
                NetworkRoute.applyToWebView(host);
                WebView view = new WebView(host);
                holder.set(view);
                WebSettings settings = view.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setMediaPlaybackRequiresUserGesture(false);
                // 只要播放地址：不拉封面图，省流量也更快
                settings.setLoadsImagesAutomatically(false);
                settings.setBlockNetworkImage(true);
                String userAgent = headers.get("User-Agent");
                if (userAgent != null && !userAgent.isEmpty()) settings.setUserAgentString(userAgent);
                view.setBackgroundColor(Color.TRANSPARENT);
                view.setFocusable(false);
                view.setFocusableInTouchMode(false);
                view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                view.setSoundEffectsEnabled(false);
                // 挡掉 alert/confirm，避免页面弹框卡死
                view.setWebChromeClient(new WebChromeClient());
                view.setWebViewClient(new WebViewClient() {
                    private void settle(String url) {
                        if (url == null || url.isEmpty()) return;
                        if (!settled.compareAndSet(false, true)) return;
                        found.set(url);
                        done.countDown();
                    }

                    @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                        String url = request.getUrl().toString();
                        if (url.contains(".m3u8")) settle(url);
                        return null;
                    }

                    @Override public void onPageFinished(WebView v, String url) { poll(v); }

                    private void poll(WebView v) {
                        if (settled.get() || System.currentTimeMillis() >= deadline) return;
                        v.evaluateJavascript(PROBE_JS, value -> {
                            if (settled.get()) return;
                            String src = unquote(value);
                            if (src.startsWith("http") && src.contains("m3u8")) {
                                if (settled.compareAndSet(false, true)) {
                                    found.set(src);
                                    done.countDown();
                                }
                                return;
                            }
                            if (System.currentTimeMillis() < deadline) {
                                main.postDelayed(() -> poll(v), POLL_INTERVAL_MS);
                            }
                        });
                    }
                });
                // 放在界面最底层（被不透明的应用界面盖住）：既能正常布局、跑脚本，用户也看不到
                if (host instanceof Activity) {
                    Activity activity = (Activity) host;
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        ViewGroup content = activity.findViewById(android.R.id.content);
                        if (content != null) {
                            content.addView(view, 0, new FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                        }
                    }
                }
                view.loadUrl(pageUrl, new HashMap<>(headers));
            } catch (Throwable error) {
                Log.e(TAG, "离屏 WebView 创建失败", error);
                settled.set(true);
                done.countDown();
            } finally {
                created.countDown();
            }
        });

        try {
            if (!created.await(timeoutMs, TimeUnit.MILLISECONDS)) return null;
            done.await(Math.max(0L, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            WebView view = holder.get();
            if (view != null) main.post(() -> destroy(view));
        }
        return found.get();
    }

    private static void destroy(WebView view) {
        try {
            View parent = (View) view.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(view);
            view.stopLoading();
            view.loadUrl("about:blank");
            view.destroy();
        } catch (Throwable error) {
            Log.w(TAG, "离屏 WebView 释放失败", error);
        }
    }

    private static String unquote(String value) {
        if (value == null) return "";
        String text = value.trim();
        if ("null".equals(text)) return "";
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1);
        }
        return text.replace("\\u003d", "=").replace("\\u0026", "&").replace("\\/", "/");
    }
}
