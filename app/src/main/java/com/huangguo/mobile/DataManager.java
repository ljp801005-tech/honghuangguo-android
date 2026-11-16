package com.huangguo.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** 数据管理：搜索历史、收藏更新检测、收藏/进度/设置的 JSON 备份与恢复。 */
final class DataManager {
    private static final String PREFS = "huangguo-extra";
    private static final int HISTORY_LIMIT = 20;
    private static final int BACKUP_LIMIT_BYTES = 2 * 1024 * 1024;
    private static final long CHECK_INTERVAL_MS = 30 * 60 * 1000L;
    static final String BACKUP_FORMAT = "honghuangguo-backup";

    private DataManager() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------- 搜索历史 ----------

    static JSONArray searchHistory(Context context) {
        try {
            JSONArray result = new JSONArray(prefs(context).getString("searchHistory", "[]"));
            return result;
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    static JSONArray addSearchHistory(Context context, String term) throws Exception {
        String clean = term == null ? "" : term.trim().replaceAll("\\s+", " ");
        if (clean.isEmpty()) return searchHistory(context);
        JSONArray fresh = new JSONArray();
        fresh.put(clean.length() > 80 ? clean.substring(0, 80) : clean);
        JSONArray existing = searchHistory(context);
        for (int i = 0; i < existing.length() && fresh.length() < HISTORY_LIMIT; i++) {
            String value = existing.optString(i, "");
            if (!value.isEmpty() && !value.equalsIgnoreCase(clean)) fresh.put(value);
        }
        prefs(context).edit().putString("searchHistory", fresh.toString()).apply();
        return fresh;
    }

    static void clearSearchHistory(Context context) {
        prefs(context).edit().remove("searchHistory").apply();
    }

    private static void restoreSearchHistory(Context context, JSONArray history) throws Exception {
        JSONArray clean = new JSONArray();
        if (history != null) {
            for (int i = 0; i < history.length() && clean.length() < HISTORY_LIMIT; i++) {
                String value = history.optString(i, "").trim();
                if (!value.isEmpty()) clean.put(value.length() > 80 ? value.substring(0, 80) : value);
            }
        }
        prefs(context).edit().putString("searchHistory", clean.toString()).apply();
    }

    // ---------- 收藏更新提醒 ----------

    /**
     * 串行检查收藏剧集是否有新集。站点有频率风控，这里最多查 20 部、单部失败不影响其余，
     * 且 30 分钟内重复点击直接返回上次结果，避免把出口打成限流。
     */
    static synchronized JSONObject checkBookmarkUpdates(Context context, AppStore store,
                                                         SiteRepository repository, boolean force) throws Exception {
        SharedPreferences preferences = prefs(context);
        long now = System.currentTimeMillis();
        if (!force && now - preferences.getLong("updatesAt", 0L) < CHECK_INTERVAL_MS) {
            String cached = preferences.getString("updatesResult", "");
            if (!cached.isEmpty()) {
                JSONObject result = new JSONObject(cached);
                result.put("cached", true);
                return result;
            }
        }
        JSONArray bookmarks = store.library().getJSONArray("bookmarks");
        JSONObject known = new JSONObject(preferences.getString("episodeCounts", "{}"));
        JSONObject latest = new JSONObject(known.toString());
        JSONArray updates = new JSONArray();
        int checked = 0;
        int failed = 0;
        for (int i = 0; i < bookmarks.length() && checked < 20; i++) {
            JSONObject item = bookmarks.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id");
            if (!id.matches("\\d+")) continue;
            checked++;
            try {
                JSONObject detail = repository.detail(id);
                JSONArray episodes = detail.optJSONArray("episodes");
                int count = episodes == null ? 0 : episodes.length();
                if (count <= 0) continue;
                int previous = known.optInt(id, 0);
                if (previous > 0 && count > previous) {
                    updates.put(new JSONObject().put("id", id)
                            .put("title", item.optString("title", detail.optString("title", "")))
                            .put("episodes", count).put("added", count - previous));
                }
                latest.put(id, count);
            } catch (Exception error) {
                failed++;   // 限流或页面结构变化：跳过这一部，下次再查
            }
        }
        JSONObject result = new JSONObject().put("checked", checked).put("failed", failed)
                .put("updates", updates).put("at", now).put("cached", false);
        preferences.edit().putString("episodeCounts", latest.toString())
                .putString("updatesResult", result.toString())
                .putLong("updatesAt", now).apply();
        return result;
    }

    // ---------- 备份 / 恢复 ----------

    static JSONObject exportBackup(Context context, AppStore store) throws Exception {
        return new JSONObject()
                .put("app", "红黄果")
                .put("format", BACKUP_FORMAT)
                .put("version", 1)
                .put("exportedAt", System.currentTimeMillis())
                .put("library", store.cloudSnapshot())
                .put("searchHistory", searchHistory(context))
                .put("playSettings", PlaySettings.snapshot(context))
                .put("proxy", NetworkRoute.configured(context));
    }

    /** 只恢复收藏/进度/搜索历史/播放设置；代理不自动恢复，避免换了网络反而连不上。 */
    static JSONObject restoreBackup(Context context, AppStore store, String json) throws Exception {
        if (json == null || json.getBytes("UTF-8").length > BACKUP_LIMIT_BYTES) {
            throw new IllegalArgumentException("备份文件超过 2 MB 或内容为空");
        }
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (Exception error) {
            throw new IllegalArgumentException("不是有效的 JSON 备份文件");
        }
        if (!BACKUP_FORMAT.equals(root.optString("format")) || root.optInt("version", 0) != 1) {
            throw new IllegalArgumentException("备份格式或版本不受支持");
        }
        JSONObject library = root.optJSONObject("library");
        if (library == null || library.optJSONObject("bookmarks") == null) {
            throw new IllegalArgumentException("备份缺少片单数据");
        }
        store.applyCloudSnapshot(library);
        restoreSearchHistory(context, root.optJSONArray("searchHistory"));
        JSONObject play = root.optJSONObject("playSettings");
        if (play != null) {
            try { PlaySettings.update(context, play); } catch (Exception ignored) { }
        }
        int saved = library.optJSONObject("bookmarks").length();
        int progress = library.optJSONObject("history") == null ? 0 : library.optJSONObject("history").length();
        return new JSONObject().put("bookmarks", saved).put("history", progress)
                .put("searchHistory", searchHistory(context).length());
    }
}
