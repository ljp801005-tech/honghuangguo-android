package com.huangguo.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 红果短剧「官方网页版」的只读接入层。
 *
 * 只读取浏览器本来就能看到的公开 HTML（首页 / 分类 / 详情），不调用任何 App 私有接口、
 * 不还原播放签名、不抓取或缓存视频直链。真正的播放交给官方 H5 页面自行完成，
 * 登录、试看、广告与清晰度策略全部沿用官方逻辑。
 */
final class HongGuoRepository {
    static final String DEFAULT_ORIGIN = "https://hongguoduanju.com";
    private static final String TAG = "HuangGuo";
    private static final String PREFS = "hongguo-site";
    private static final String MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/130.0.0.0 Mobile Safari/537.36";
    static final String DESKTOP_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/130.0.0.0 Safari/537.36";

    /** 官方网页 QPS 要压住：连续突发请求容易被判为异常流量。 */
    private static final long MIN_GAP_MS = 900L;
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final Object PACE = new Object();
    private static long lastRequestAt;
    private static final Map<String, Cached> CACHE = new LinkedHashMap<>();

    private static final class Cached {
        final long at = System.currentTimeMillis();
        final String body;
        Cached(String body) { this.body = body; }
    }

    private HongGuoRepository() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String origin(Context context) {
        String raw = prefs(context).getString("origin", DEFAULT_ORIGIN);
        return validOrigin(raw) ? trimSlash(raw) : DEFAULT_ORIGIN;
    }

    static String userAgent(Context context) {
        return prefs(context).getBoolean("desktopUa", false) ? DESKTOP_UA : MOBILE_UA;
    }

    private static String trimSlash(String raw) {
        return raw.trim().replaceAll("/+$", "");
    }

    private static boolean validOrigin(String raw) {
        try {
            URL url = new URL(raw.trim());
            return "https".equals(url.getProtocol())
                    && url.getHost() != null && url.getHost().contains(".")
                    && (url.getPath() == null || url.getPath().isEmpty() || "/".equals(url.getPath()))
                    && url.getQuery() == null && url.getRef() == null;
        } catch (Exception error) {
            return false;
        }
    }

    /** 只允许本应用把用户送到官方站点，避免桥接参数被用来打开任意地址。 */
    static boolean allowedTarget(Context context, String raw) {
        try {
            URL url = new URL(raw);
            if (!"https".equals(url.getProtocol())) return false;
            String host = originHost(context);
            return url.getHost().equalsIgnoreCase(host) || url.getHost().toLowerCase().endsWith("." + host);
        } catch (Exception error) {
            return false;
        }
    }

    private static String originHost(Context context) {
        try {
            return new URL(origin(context)).getHost().toLowerCase();
        } catch (Exception error) {
            return "hongguoduanju.com";
        }
    }

    /** 页面内链：官方站点与字节系静态资源留在 WebView，其余跳系统浏览器。 */
    static boolean internalNavigation(Context context, String raw) {
        try {
            String host = new URL(raw).getHost().toLowerCase();
            if (host.equals(originHost(context)) || host.endsWith("." + originHost(context))) return true;
            String[] suffixes = {".byteimg.com", ".bytetos.com", ".bytegecko.com", ".fqnovelstatic.com",
                    ".fqnovel.com", ".snssdk.com", ".douyin.com", ".bytedance.com", ".quark.cn",
                    ".yhgfb-cn-static.com", ".novelstatic.com"};
            for (String suffix : suffixes) if (host.endsWith(suffix)) return true;
            return false;
        } catch (Exception error) {
            return false;
        }
    }

    static JSONArray categories() {
        JSONArray list = new JSONArray();
        String[][] rows = {
                {"home", "推荐", "/"},
                {"real-drama", "真人剧", "/category/real-drama"},
                {"comic-drama", "漫剧", "/category/comic-drama"},
                {"ai-drama", "AI 剧", "/category/ai-drama"},
                {"comic", "漫画", "/category/comic"}
        };
        for (String[] row : rows) {
            try {
                list.put(new JSONObject().put("id", row[0]).put("label", row[1]).put("path", row[2]));
            } catch (Exception ignored) { }
        }
        return list;
    }

    private static String pathFor(String id) {
        try {
            JSONArray list = categories();
            for (int i = 0; i < list.length(); i++) {
                JSONObject row = list.getJSONObject(i);
                if (id.equals(row.optString("id"))) return row.optString("path");
            }
        } catch (Exception ignored) { }
        return "/";
    }

    private static String categoryId(String raw) {
        String id = raw == null ? "" : raw.trim();
        try {
            JSONArray list = categories();
            for (int i = 0; i < list.length(); i++) {
                if (id.equals(list.getJSONObject(i).optString("id"))) return id;
            }
        } catch (Exception ignored) { }
        return "home";
    }

    static JSONObject config(Context context) throws Exception {
        JSONObject result = new JSONObject();
        result.put("origin", origin(context));
        result.put("defaultOrigin", DEFAULT_ORIGIN);
        result.put("desktopUa", prefs(context).getBoolean("desktopUa", false));
        result.put("categories", categories());
        return result;
    }

    static JSONObject updateConfig(Context context, JSONObject args) throws Exception {
        String raw = args.optString("origin", "").trim();
        SharedPreferences.Editor editor = prefs(context).edit();
        if (!raw.isEmpty()) {
            if (!validOrigin(raw)) throw new IllegalArgumentException("入口必须是 https 域名，例如 https://hongguoduanju.com");
            editor.putString("origin", trimSlash(raw));
        }
        if (args.has("desktopUa")) editor.putBoolean("desktopUa", args.optBoolean("desktopUa", false));
        editor.apply();
        synchronized (CACHE) { CACHE.clear(); }
        return config(context);
    }

    /** 首页 / 分类页卡片。字段与前端 film-card 对齐，但不走黄果的封面解密通道。 */
    static JSONObject feed(Context context, String requested) throws Exception {
        String id = categoryId(requested);
        String url = origin(context) + pathFor(id);
        String html = page(context, url);
        Document doc = Jsoup.parse(html, url);
        JSONArray items = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        Elements cards = doc.select("a[href*=\"/detail?series_id=\"]");
        for (Element card : cards) {
            String href = card.attr("href");
            String sid = queryParam(href, "series_id");
            if (sid.isEmpty() || !seen.add(sid)) continue;
            String title = cleanTitle(card.attr("aria-label"));
            if (title.isEmpty()) {
                Element named = card.selectFirst("[class*=m-title] p, [class*=m-title], p[class*=title]");
                title = cleanTitle(named == null ? "" : named.text());
            }
            if (title.isEmpty()) {
                Element alt = card.selectFirst("img[alt]");
                title = cleanTitle(alt == null ? "" : alt.attr("alt"));
            }
            if (title.isEmpty()) title = "红果短剧 " + sid.substring(Math.max(0, sid.length() - 4));
            // 分类页会先放一张 loading 占位图，优先取 <picture> 里的真实海报
            Element image = card.selectFirst("picture img[src]");
            if (image == null || image.attr("src").isEmpty()) {
                image = card.selectFirst("img[src]:not([class*=empty])");
            }
            // 只认真正的简介节点：裸 p 会先命中标题段落，导致简介显示成剧名
            Element desc = card.selectFirst("p[class*=m-desc], p[class*=desc], p[class*=subtitle]");
            JSONArray tags = new JSONArray();
            for (Element tag : card.select("[class*=m-tag-text]")) {
                String value = tag.text().trim();
                if (!value.isEmpty() && tags.length() < 4) tags.put(value);
            }
            JSONObject item = new JSONObject();
            item.put("id", sid);
            item.put("title", title);
            item.put("cover", image == null ? "" : image.absUrl("src"));
            item.put("description", desc == null ? "" : desc.text().trim());
            item.put("tags", tags);
            item.put("detailUrl", origin(context) + "/detail?series_id=" + sid);
            item.put("playerUrl", origin(context) + "/player/" + sid);
            items.put(item);
            if (items.length() >= 48) break;
        }
        Log.i(TAG, "hongguo feed " + id + " bytes=" + html.length()
                + " cards=" + cards.size() + " items=" + items.length());
        if (items.length() == 0) throw new IllegalStateException("红果页面结构有变化，暂时没读到剧集");
        JSONObject result = new JSONObject();
        result.put("category", id);
        result.put("items", items);
        result.put("origin", origin(context));
        result.put("pageUrl", url);
        return result;
    }

    /** 详情页只取 SSR 已输出的几集；完整选集由官方播放页自己渲染。 */
    static JSONObject detail(Context context, String id) throws Exception {
        if (id == null || !id.matches("\\d{6,25}")) throw new IllegalArgumentException("剧集编号不合法");
        String url = origin(context) + "/detail?series_id=" + id;
        // 桌面版页面里带一份完整的剧集数据（vid_list + 官方给的免费集数），手机版没有，
        // 所以详情优先用桌面版解析；失败再退回手机版 DOM。
        String desktop = null;
        try {
            desktop = page(context, url, DESKTOP_UA);
        } catch (Exception error) {
            Log.w(TAG, "红果桌面版详情获取失败：" + error.getMessage());
        }
        Document doc;
        JSONObject embedded = desktop == null ? null : embeddedSeries(desktop, id);
        if (embedded != null && embedded.optJSONArray("episodes") != null) {
            return detailFromEmbedded(context, id, url, desktop, embedded);
        }
        doc = Jsoup.parse(page(context, url), url);
        Element h1 = doc.selectFirst("h1");
        Element ogImage = doc.selectFirst("meta[property=og:image]");
        Element ogDesc = doc.selectFirst("meta[name=description], meta[property=og:description]");
        String title = h1 == null ? "" : h1.text().trim();
        if (title.isEmpty()) title = doc.title().replaceAll("_.*$", "").trim();
        String description = ogDesc == null ? "" : ogDesc.attr("content").trim();
        description = description.replaceAll("^《.*?》高清在线观看，剧情:", "");

        JSONArray episodes = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        for (Element link : doc.select("a[href^=\"/player/\"]")) {
            String href = link.attr("href");
            if (!seen.add(href)) continue;
            String label = link.text().trim();
            if (label.isEmpty()) label = "第 " + (episodes.length() + 1) + " 集";
            else if (label.matches("\\d+")) label = "第 " + label + " 集";
            episodes.put(new JSONObject().put("label", label).put("url", origin(context) + href));
        }
        // 官方网页版每部剧只放前几集，其余是带锁的 div；总集数写在「共 N 集」里
        Matcher total = Pattern.compile("共(\\d+)集").matcher(doc.text());
        int totalEpisodes = total.find() ? Integer.parseInt(total.group(1)) : episodes.length();
        int lockedEpisodes = doc.select("div.m-episodes-pXr5Cl .m-lock-container-_IO6_m").size();

        JSONObject result = new JSONObject();
        result.put("id", id);
        result.put("title", title);
        result.put("cover", ogImage == null ? "" : ogImage.attr("content"));
        result.put("description", description);
        result.put("episodes", episodes);
        result.put("totalEpisodes", Math.max(totalEpisodes, episodes.length()));
        result.put("freeEpisodes", episodes.length());
        result.put("lockedEpisodes", lockedEpisodes);
        result.put("gateHint", lockedEpisodes > 0 || totalEpisodes > episodes.length()
                ? "官方网页版只放前 " + episodes.length() + " 集，全集在红果短剧 App 里免费看"
                : "");
        result.put("appInstalled", OfficialAppLauncher.installed(context));
        result.put("firstUrl", origin(context) + "/player/" + id);
        result.put("pageUrl", url);
        result.put("origin", origin(context));
        return result;
    }

    /**
     * 桌面版页面把整部剧的数据以 JSON 内嵌在 HTML 里：vid_list 是全集（每集一个 vid），
     * accessible_episode_cnt 是官方自己声明的免费集数。这是公开页面内容，不涉及私有接口。
     */
    private static JSONObject embeddedSeries(String html, String id) {
        try {
            int anchor = html.indexOf("\"series_id\":\"" + id + "\"");
            if (anchor < 0) anchor = html.indexOf("\"vid_list\"");
            if (anchor < 0) return null;
            int start = Math.max(0, anchor - 8000);
            int end = Math.min(html.length(), anchor + 60000);
            String window = html.substring(start, end);

            Matcher list = Pattern.compile("\"vid_list\":\\[([^\\]]*)\\]").matcher(window);
            if (!list.find()) return null;
            JSONArray vids = new JSONArray();
            Matcher one = Pattern.compile("\"(\\d{10,25})\"").matcher(list.group(1));
            Set<String> seen = new LinkedHashSet<>();
            while (one.find() && seen.add(one.group(1))) vids.put(one.group(1));
            if (vids.length() == 0) return null;

            JSONArray episodes = new JSONArray();
            for (int index = 0; index < vids.length(); index++) {
                episodes.put(new JSONObject()
                        .put("label", "第 " + (index + 1) + " 集")
                        .put("vid", vids.optString(index))
                        .put("index", index + 1));
            }
            JSONObject result = new JSONObject();
            result.put("episodes", episodes);
            result.put("totalEpisodes", vids.length());
            result.put("freeEpisodes", intField(window, "accessible_episode_cnt", vids.length()));
            result.put("title", stringField(window, "series_name"));
            result.put("cover", stringField(window, "series_cover").replace("\\u002F", "/"));
            return result;
        } catch (Exception error) {
            Log.w(TAG, "红果内嵌数据解析失败：" + error.getMessage());
            return null;
        }
    }

    private static int intField(String window, String key, int fallback) {
        Matcher matcher = Pattern.compile("\"" + key + "\":(\\d+)").matcher(window);
        if (!matcher.find()) return fallback;
        try { return Integer.parseInt(matcher.group(1)); } catch (NumberFormatException error) { return fallback; }
    }

    private static String stringField(String window, String key) {
        Matcher matcher = Pattern.compile("\"" + key + "\":\"([^\"]{0,200})\"").matcher(window);
        return matcher.find() ? matcher.group(1) : "";
    }

    /** 用桌面版内嵌数据组装详情：全集可选，前 freeEpisodes 集由官方网页直接放行。 */
    private static JSONObject detailFromEmbedded(Context context, String id, String url,
                                                 String html, JSONObject embedded) throws Exception {
        JSONArray all = embedded.getJSONArray("episodes");
        int free = embedded.optInt("freeEpisodes", 0);
        if (free <= 0 || free > all.length()) free = Math.min(5, all.length());

        JSONArray playable = new JSONArray();
        for (int index = 0; index < free; index++) {
            JSONObject episode = all.getJSONObject(index);
            playable.put(new JSONObject()
                    .put("label", episode.optString("label"))
                    .put("url", origin(context) + "/player/" + id + "/" + episode.optString("vid")));
        }

        Document doc = Jsoup.parse(html, url);
        Element ogImage = doc.selectFirst("meta[property=og:image]");
        Element ogDesc = doc.selectFirst("meta[name=description], meta[property=og:description]");
        String title = embedded.optString("title");
        if (title.isEmpty()) {
            Element h1 = doc.selectFirst("h1");
            title = h1 == null ? doc.title().replaceAll("_.*$", "").trim() : h1.text().trim();
        }
        String description = ogDesc == null ? "" : ogDesc.attr("content").trim();
        description = description.replaceAll("^《.*?》高清在线观看，剧情:", "");
        String cover = embedded.optString("cover");
        if (cover.isEmpty() && ogImage != null) cover = ogImage.attr("content");

        JSONObject result = new JSONObject();
        result.put("id", id);
        result.put("title", title);
        result.put("cover", cover);
        result.put("description", description);
        result.put("episodes", playable);
        result.put("totalEpisodes", all.length());
        result.put("freeEpisodes", free);
        result.put("lockedEpisodes", Math.max(0, all.length() - free));
        result.put("gateHint", all.length() > free
                ? "官方网页版免费 " + free + " 集（共 " + all.length() + " 集），其余在红果短剧 App 里免费看"
                : "");
        result.put("appInstalled", OfficialAppLauncher.installed(context));
        result.put("firstUrl", playable.length() > 0 ? playable.getJSONObject(0).optString("url")
                : origin(context) + "/player/" + id);
        result.put("pageUrl", url);
        result.put("origin", origin(context));
        result.put("desktopUa", true);
        return result;
    }

    private static String cleanTitle(String raw) {
        String text = raw == null ? "" : raw.trim();
        return text.replaceAll("(海报|剧名)$", "").replaceAll("短剧$", "").trim();
    }

    private static String queryParam(String href, String key) {
        int start = href.indexOf(key + "=");
        if (start < 0) return "";
        start += key.length() + 1;
        int end = href.indexOf('&', start);
        String value = end < 0 ? href.substring(start) : href.substring(start, end);
        return value.matches("\\d{6,25}") ? value : "";
    }

    private static String page(Context context, String url) throws Exception {
        return page(context, url, null);
    }

    private static String page(Context context, String url, String userAgent) throws Exception {
        String key = url + (userAgent == null ? "" : "#" + Integer.toHexString(userAgent.hashCode()));
        synchronized (CACHE) {
            Cached hit = CACHE.get(key);
            if (hit != null && System.currentTimeMillis() - hit.at < CACHE_TTL_MS) return hit.body;
        }
        String body = fetch(context, url, userAgent);
        if (body.length() < 2000) throw new IOException("红果页面返回空内容");
        synchronized (CACHE) {
            CACHE.put(key, new Cached(body));
            if (CACHE.size() > 24) CACHE.remove(CACHE.keySet().iterator().next());
        }
        return body;
    }

    private static void pace() {
        synchronized (PACE) {
            long wait = lastRequestAt + MIN_GAP_MS - System.currentTimeMillis();
            if (wait > 0) {
                try { Thread.sleep(wait); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
            lastRequestAt = System.currentTimeMillis();
        }
    }

    private static boolean throttled(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase();
            if (cause instanceof javax.net.ssl.SSLException || cause instanceof java.io.EOFException
                    || message.contains("connection closed") || message.contains("reset")) return true;
        }
        return false;
    }

    private static String fetch(Context context, String url) throws Exception {
        return fetch(context, url, null);
    }

    private static String fetch(Context context, String url, String userAgent) throws Exception {
        Exception last = null;
        // 代理不可达时别再拿它去撞 12 秒连接超时，直接走直连；可达则两条路线各给两次机会
        boolean[] routes = NetworkRoute.available(context) ? new boolean[]{true, false} : new boolean[]{false};
        boolean proxyThrottled = false;
        for (int i = 0; i < routes.length; i++) {
            for (int retry = 0; retry < 2; retry++) {
                try {
                    String body = fetchOnce(context, url, routes[i], userAgent);
                    if (!routes[i] && proxyThrottled) NetworkRoute.avoidProxyTemporarily();
                    return body;
                } catch (IOException error) {
                    last = error;
                    if (routes[i] && throttled(error)) proxyThrottled = true;
                    Log.w(TAG, "红果页面请求失败（" + (routes[i] ? "代理" : "直连")
                            + " 第 " + (retry + 1) + " 次）：" + error.getMessage());
                    if (proxyThrottled) break;   // 出口被掐，本条路线不再重试
                    if (retry == 0) Thread.sleep(600L);
                }
            }
        }
        throw new IOException(throttled(last) ? "红果页面被限流，请稍后重试" : "红果页面连接失败", last);
    }

    private static String fetchOnce(Context context, String url, boolean useProxy) throws IOException {
        return fetchOnce(context, url, useProxy, null);
    }

    private static String fetchOnce(Context context, String url, boolean useProxy, String userAgent) throws IOException {
        if (!"https".equals(new URL(url).getProtocol())) throw new IOException("仅支持 HTTPS");
        pace();
        HttpURLConnection connection = NetworkRoute.open(context, new URL(url), useProxy);
        // 设置页的「网页标识」要同时作用于抓取：桌面 UA 下官方会给出不同的页面结构
        connection.setRequestProperty("User-Agent", userAgent == null ? userAgent(context) : userAgent);
        connection.setRequestProperty("Referer", origin(context) + "/");
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        // 官方站点 vary: Accept-Encoding；显式要明文，避免代理链路上 gzip 解码不一致导致 HTML 被截断
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setInstanceFollowRedirects(true);
        connection.setUseCaches(false);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
            InputStream raw = connection.getInputStream();
            InputStream decoded = "gzip".equalsIgnoreCase(connection.getContentEncoding())
                    ? new java.util.zip.GZIPInputStream(raw) : raw;
            try (InputStream stream = decoded; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }
}
