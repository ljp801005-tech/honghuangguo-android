package com.huangguo.mobile;

import android.app.Application;

import com.parse.Parse;

public final class HuangGuoApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        // 全应用统一出口：首页封面、详情、取源、取流都走同一个代理（探测不通则自动直连）
        new Thread(() -> NetworkRoute.applyToWebView(this), "network-route").start();
        Parse.initialize(new Parse.Configuration.Builder(this)
                .applicationId(getString(R.string.back4app_app_id))
                .clientKey(getString(R.string.back4app_client_key))
                .server("https://parseapi.back4app.com/")
                .build());
    }
}
