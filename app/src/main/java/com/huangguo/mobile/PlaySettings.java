package com.huangguo.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** 全局播放偏好：倍速、默认清晰度、跳过片头片尾。设置页写入，播放器启动时读取。 */
final class PlaySettings {
    private static final String PREFS = "huangguo-play";
    private static final float[] SPEEDS = {0.75f, 1f, 1.25f, 1.5f, 2f};

    private PlaySettings() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static float speed(Context context) {
        float value = prefs(context).getFloat("speed", 1f);
        return isValidSpeed(value) ? value : 1f;
    }

    /** auto / 1080 / 720 / 480 / 360，代表允许的最高清晰度。 */
    static String quality(Context context) {
        String value = prefs(context).getString("quality", "auto");
        return value == null ? "auto" : value;
    }

    static int introSkip(Context context) {
        return prefs(context).getInt("introSkip", 0);
    }

    static int outroSkip(Context context) {
        return prefs(context).getInt("outroSkip", 0);
    }

    private static boolean isValidSpeed(float value) {
        for (float candidate : SPEEDS) if (Math.abs(candidate - value) < 0.001f) return true;
        return false;
    }

    static JSONObject snapshot(Context context) throws Exception {
        return new JSONObject().put("speed", speed(context)).put("quality", quality(context))
                .put("introSkip", introSkip(context)).put("outroSkip", outroSkip(context));
    }

    /** 只接受已知字段并做范围校验，避免备份文件写入非法值。 */
    static JSONObject update(Context context, JSONObject args) throws Exception {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (args.has("speed")) {
            float value = (float) args.optDouble("speed", 1d);
            if (!isValidSpeed(value)) throw new IllegalArgumentException("倍速仅支持 0.75/1/1.25/1.5/2");
            editor.putFloat("speed", value);
        }
        if (args.has("quality")) {
            String value = args.optString("quality", "auto");
            if (!java.util.Arrays.asList("auto", "1080", "720", "480", "360").contains(value)) {
                throw new IllegalArgumentException("清晰度选项无效");
            }
            editor.putString("quality", value);
        }
        if (args.has("introSkip")) editor.putInt("introSkip", clampSeconds(args.optInt("introSkip", 0)));
        if (args.has("outroSkip")) editor.putInt("outroSkip", clampSeconds(args.optInt("outroSkip", 0)));
        editor.apply();
        return snapshot(context);
    }

    private static int clampSeconds(int value) {
        return Math.max(0, Math.min(120, value));
    }
}
