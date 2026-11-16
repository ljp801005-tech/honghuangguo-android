package com.huangguo.mobile;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

/**
 * 红果短剧网页版每部剧只放前 3 集，第 4 集起官方就上锁并明示「前往红果短剧 App 端免费看全集」。
 * 这里只做「把用户送到官方 App」，不抓私有接口、不绕官方访问控制。
 */
final class OfficialAppLauncher {

    static final String PACKAGE = "com.phoenix.read";
    private static final String STORE_WEB = "https://sj.qq.com/appdetail/" + PACKAGE;

    private OfficialAppLauncher() {
    }

    enum Result { LAUNCHED, STORE, BROWSER, NONE }

    static boolean installed(Context context) {
        try {
            context.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }

    /** 返回实际走通的通道，界面据此给出不同提示。 */
    static Result open(Context context) {
        Context app = context.getApplicationContext();

        Intent launch = app.getPackageManager().getLaunchIntentForPackage(PACKAGE);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (start(app, launch)) return Result.LAUNCHED;
        }

        // 未安装：优先商店，退到腾讯应用宝详情页（官方分发渠道）
        Intent market = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + PACKAGE));
        market.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (start(app, market)) return Result.STORE;

        Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse(STORE_WEB));
        web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (start(app, web)) return Result.BROWSER;

        return Result.NONE;
    }

    private static boolean start(Context context, Intent intent) {
        try {
            context.startActivity(intent);
            return true;
        } catch (Throwable error) {
            android.util.Log.w("HuangGuo", "拉起红果 App 失败：" + error.getMessage());
            return false;
        }
    }
}
