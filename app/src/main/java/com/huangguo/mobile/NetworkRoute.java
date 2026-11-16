package com.huangguo.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.util.concurrent.Executor;

/**
 * 统一出口：应用内所有对站点的请求（原生抓取 / 离屏 WebView / 播放器取流）都从这里取代理。
 * <p>
 * 站点按来源 IP 做频率限制，出口被掐时 TLS 握手会被直接重置。支持 HTTP 与 SOCKS5 两种代理：
 * 设置里可填 host:port（默认 HTTP）、http://host:port、socks5://host:port，或选择直连。
 * 代理探测不通时自动回落直连，避免换个网络就整个 App 打不开。
 */
final class NetworkRoute {
    private static final String TAG = "HuangGuo";
    /** 内置默认代理：用户局域网内的 Clash 混合端口。 */
    static final String DEFAULT_PROXY = "192.168.100.160:7897";
    private static final String PREFS = "huangguo-net";
    private static final String KEY = "proxy";
    private static final long PROBE_TTL_MS = 60_000L;
    private static final int PROBE_TIMEOUT_MS = 1200;

    private static volatile long probedAt;
    private static volatile boolean probedOk;
    private static volatile boolean webViewApplied;
    private static volatile boolean logged;
    private static volatile boolean loggedOk;
    /** 代理出口被站点限流时，一段时间内所有通道一起改走直连，10 分钟后自动恢复。 */
    private static volatile long directUntil;
    private static final long AVOID_MS = 10 * 60 * 1000L;

    private NetworkRoute() {}

    static boolean forcedDirect() {
        return System.currentTimeMillis() < directUntil;
    }

    /** 抓取代理路线被 TLS 重置、直连成功时调用：让 WebView 与播放器也一起避开被限流的出口。 */
    static void avoidProxyTemporarily() {
        directUntil = System.currentTimeMillis() + AVOID_MS;
        invalidate();
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            try {
                runOnWebViewThread(() -> ProxyController.getInstance().clearProxyOverride(DIRECT_EXECUTOR, () -> {}));
            } catch (Throwable ignored) { }
        }
        Log.i(TAG, "代理出口疑似被限流，10 分钟内全部通道改走直连");
    }

    /** 结束限流避让，恢复配置里的代理出口（用户手动切回时调用）。 */
    static void clearAvoid() {
        directUntil = 0L;
        invalidate();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 原始配置：空串 = 直连，"default" = 内置默认，其余为代理地址。 */
    static String configured(Context context) {
        String raw = prefs(context).getString(KEY, "default");
        return raw == null ? "default" : raw.trim();
    }

    /**
     * 规范化配置：保留 scheme，返回 "http://host:port" / "socks5://host:port" / "host:port"；
     * 空串表示直连。带账号密码的 SOCKS5 暂不支持（Java 层代理认证需全局 Authenticator）。
     */
    static String normalize(String raw) {
        if (raw == null) return DEFAULT_PROXY;
        String text = raw.trim();
        if (text.isEmpty()) return "";
        if ("default".equalsIgnoreCase(text)) return DEFAULT_PROXY;
        if ("direct".equalsIgnoreCase(text) || "off".equalsIgnoreCase(text)) return "";
        if (text.contains("@")) throw new IllegalArgumentException("暂不支持带账号密码的代理");
        if (text.contains("://")) {
            Uri uri = Uri.parse(text);
            String parsed = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!parsed.equals("http") && !parsed.equals("socks5") && !parsed.equals("socks")) {
                throw new IllegalArgumentException("仅支持 HTTP 或 SOCKS5 代理");
            }
            String scheme = parsed.equals("socks") ? "socks5" : parsed;
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return "";
            int port = uri.getPort() > 0 ? uri.getPort() : (scheme.equals("socks5") ? 1080 : 7897);
            return scheme + "://" + host + ":" + port;
        }
        return text;
    }

    /** 去掉 scheme 后的 host:port。 */
    static String bare(String normalized) {
        if (normalized == null || normalized.isEmpty()) return "";
        int mark = normalized.indexOf("://");
        return mark >= 0 ? normalized.substring(mark + 3) : normalized;
    }

    static boolean socks5(Context context) {
        return normalize(configured(context)).startsWith("socks5://");
    }

    static String host(Context context) {
        String value = bare(normalize(configured(context)));
        if (value.isEmpty()) return "";
        int colon = value.lastIndexOf(':');
        return colon > 0 ? value.substring(0, colon) : value;
    }

    static int port(Context context) {
        String value = bare(normalize(configured(context)));
        if (value.isEmpty()) return 0;
        int colon = value.lastIndexOf(':');
        if (colon <= 0) return socks5(context) ? 1080 : 7897;
        try {
            return Integer.parseInt(value.substring(colon + 1).trim());
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    /** 代理地址是否合法，供设置页校验输入。 */
    static boolean valid(String raw) {
        String normalized;
        try {
            normalized = normalize(raw);
        } catch (IllegalArgumentException error) {
            return false;
        }
        if (normalized.isEmpty()) return true;   // 直连
        String value = bare(normalized);
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || colon == value.length() - 1) return false;
        if (!value.substring(0, colon).matches("[A-Za-z0-9.\\-]{1,253}")) return false;
        try {
            int valuePort = Integer.parseInt(value.substring(colon + 1));
            return valuePort > 0 && valuePort <= 65535;
        } catch (NumberFormatException error) {
            return false;
        }
    }

    static void setProxy(Context context, String raw) {
        String value = raw == null ? "default" : raw.trim();
        prefs(context).edit().putString(KEY, value.isEmpty() ? "" : value).apply();
        directUntil = 0L;   // 用户主动改配置，取消临时避让
        invalidate();
    }

    static void invalidate() {
        probedAt = 0L;
        webViewApplied = false;
    }

    /** 立刻按新配置重新挂 WebView 代理（必须在后台线程调用）。 */
    static void refresh(Context context) {
        invalidate();
        applyToWebView(context);
    }

    /** 单次探测指定地址，返回耗时毫秒；-1 表示不通，0 表示直连。不写缓存。 */
    static long probe(String raw) {
        String normalized;
        try {
            normalized = normalize(raw);
        } catch (IllegalArgumentException error) {
            return -1;
        }
        if (normalized.isEmpty()) return 0;
        String value = bare(normalized);
        int colon = value.lastIndexOf(':');
        if (colon <= 0) return -1;
        String targetHost = value.substring(0, colon);
        int targetPort;
        try {
            targetPort = Integer.parseInt(value.substring(colon + 1));
        } catch (NumberFormatException error) {
            return -1;
        }
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(targetHost, targetPort), PROBE_TIMEOUT_MS);
            return System.currentTimeMillis() - start;
        } catch (IOException error) {
            return -1;
        }
    }

    /**
     * 代理端口能否连通。主线程只读缓存结果（探测放后台），避免 NetworkOnMainThreadException；
     * 后台线程探测并缓存 60 秒。
     */
    static boolean available(Context context) {
        if (forcedDirect()) return false;
        String currentHost = host(context);
        int currentPort = port(context);
        if (currentHost.isEmpty() || currentPort <= 0) return false;
        long now = System.currentTimeMillis();
        if (probedAt != 0L && now - probedAt < PROBE_TTL_MS) return probedOk;
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            probeAsync(context);
            return probedOk;
        }
        synchronized (NetworkRoute.class) {
            now = System.currentTimeMillis();
            if (probedAt != 0L && now - probedAt < PROBE_TTL_MS) return probedOk;
            boolean ok = false;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(currentHost, currentPort), PROBE_TIMEOUT_MS);
                ok = true;
            } catch (IOException error) {
                Log.w(TAG, "代理不可达，" + currentHost + ":" + currentPort + " → 回落直连");
            }
            probedOk = ok;
            probedAt = now;
            if (ok != loggedOk || !logged) {
                logged = true;
                loggedOk = ok;
                Log.i(TAG, ok ? ("出口：" + (socks5(context) ? "SOCKS5 " : "HTTP ") + currentHost + ":" + currentPort)
                        : "出口：直连（代理不可达）");
            }
            return ok;
        }
    }

    /**
     * 单例探测线程 + 去重标记：界面每次读出口状态都会走到这里，
     * 之前每次新建线程池会在缓存过期瞬间并发开一堆 TCP 连接。
     */
    private static final java.util.concurrent.ExecutorService PROBE_POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private static volatile boolean probing;

    private static void probeAsync(Context context) {
        if (probing) return;
        probing = true;
        final Context app = context.getApplicationContext();
        try {
            PROBE_POOL.execute(() -> {
                try {
                    available(app);
                    applyToWebView(app);
                } catch (Throwable ignored) {
                    // 后台探测失败不影响主流程
                } finally {
                    probing = false;
                }
            });
        } catch (Throwable error) {
            probing = false;   // 线程池异常时不能让标记卡死
        }
    }

    /** 可用的代理；返回 null 表示直连。 */
    static Proxy proxy(Context context) {
        if (forcedDirect() || !available(context)) return null;
        return new Proxy(socks5(context) ? Proxy.Type.SOCKS : Proxy.Type.HTTP,
                new InetSocketAddress(host(context), port(context)));
    }

    /** 给界面显示的出口描述。 */
    static String label(Context context) {
        String normalized;
        try {
            normalized = normalize(configured(context));
        } catch (IllegalArgumentException error) {
            return "代理配置有误";
        }
        if (normalized.isEmpty()) return "直连";
        if (forcedDirect()) return "直连（代理出口限流，临时避让）";
        if (proxy(context) == null) return "直连（代理不可达）";
        return "代理 " + (socks5(context) ? "SOCKS5 " : "HTTP ") + host(context) + ":" + port(context);
    }

    static HttpURLConnection open(Context context, URL url, boolean useProxy) throws IOException {
        Proxy currentProxy = useProxy ? proxy(context) : null;
        HttpURLConnection connection = (HttpURLConnection) (currentProxy == null
                ? url.openConnection() : url.openConnection(currentProxy));
        connection.setConnectTimeout(currentProxy == null ? 15_000 : 12_000);
        connection.setReadTimeout(25_000);
        return connection;
    }

    /** WebView 用的代理规则：SOCKS5 需要带 scheme，末尾 addDirect() 让 WebView 在代理失效时回落直连。 */
    private static String webViewRule(Context context) {
        String address = host(context) + ":" + port(context);
        return socks5(context) ? "socks5://" + address : address;
    }

    static void applyToWebView(Context context) {
        if (forcedDirect()) {
            // 限流避让期内保持直连；不标记 applied，过期后自动重新挂回代理
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                try {
                    runOnWebViewThread(() -> ProxyController.getInstance().clearProxyOverride(DIRECT_EXECUTOR, () -> {}));
                } catch (Throwable ignored) { }
            }
            return;
        }
        if (webViewApplied) return;
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            Log.w(TAG, "当前 WebView 不支持 PROXY_OVERRIDE，取源将直连");
            return;
        }
        try {
            if (available(context)) {
                String rule = webViewRule(context);
                ProxyConfig config = new ProxyConfig.Builder().addProxyRule(rule).addDirect().build();
                runOnWebViewThread(() -> ProxyController.getInstance().setProxyOverride(config, DIRECT_EXECUTOR, () -> {}));
                Log.i(TAG, "WebView 代理规则：" + rule);
            } else {
                runOnWebViewThread(() -> ProxyController.getInstance().clearProxyOverride(DIRECT_EXECUTOR, () -> {}));
            }
            webViewApplied = true;
        } catch (Throwable error) {
            // 某些 WebView 版本不认 socks5:// 规则，降级为直连，不能让它把 App 弄崩
            Log.w(TAG, "WebView 代理设置失败，改用直连", error);
            try {
                runOnWebViewThread(() -> ProxyController.getInstance().clearProxyOverride(DIRECT_EXECUTOR, () -> {}));
            } catch (Throwable ignored) { }
        }
    }

    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    private static void runOnWebViewThread(Runnable action) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            action.run();
            return;
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).post(action);
    }
}
