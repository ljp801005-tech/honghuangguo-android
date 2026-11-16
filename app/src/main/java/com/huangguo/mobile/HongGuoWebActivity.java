package com.huangguo.mobile;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 官方红果网页版播放容器。
 *
 * 播放、登录、试看与广告完全由官方 H5 页面负责；这里只提供原生外壳能力：
 * 全屏视频、返回栈、代理出口、加载诊断、外链分流与系统浏览器兜底。
 */
public final class HongGuoWebActivity extends Activity {
    private static final String TAG = "HuangGuo";
    static final String EXTRA_URL = "url";
    static final String EXTRA_TITLE = "title";
    /** 播放页用桌面版：官方 PC 页面才给出完整播放器与 5 集免费额度，手机页只给 3 集且弹「打开App」。 */
    static final String EXTRA_DESKTOP = "desktop";

    private FrameLayout root;
    private LinearLayout chrome;
    private WebView web;
    private ProgressBar progress;
    private TextView titleView;
    private LinearLayout errorPanel;
    private WebChromeClient chromeClient;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private String currentUrl;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int probeCount;
    private boolean renderGone;
    private static long lastRenderGoneAt;
    private Button routeButton;
    private int surfaceColor;
    private int textColor;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        currentUrl = getIntent().getStringExtra(EXTRA_URL);
        if (currentUrl == null || !HongGuoRepository.allowedTarget(this, currentUrl)) {
            finish();
            return;
        }
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title == null || title.isEmpty()) title = "红果短剧";
        boolean desktopMode = getIntent().getBooleanExtra(EXTRA_DESKTOP, false);

        boolean dark = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int surface = dark ? Color.parseColor("#171b1d") : Color.parseColor("#f5f2e9");
        int text = dark ? Color.parseColor("#f5f1e7") : Color.parseColor("#222019");
        surfaceColor = surface;
        textColor = text;

        root = new FrameLayout(this);
        root.setBackgroundColor(surface);
        chrome = new LinearLayout(this);
        chrome.setOrientation(LinearLayout.VERTICAL);
        chrome.setBackgroundColor(surface);
        // targetSdk 36 起强制 edge-to-edge，不补 inset 的话标题栏会被状态栏压住
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.statusBars()).top
                    : insets.getSystemWindowInsetTop();
            view.setPadding(0, top, 0, 0);
            return insets;
        });

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(10), dp(8), dp(8));
        Button back = textButton("←", text);
        back.setContentDescription("返回");
        back.setOnClickListener(view -> onBackPressed());
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(40)));

        titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(text);
        titleView.setTextSize(14f);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(titleView, new LinearLayout.LayoutParams(0, dp(40), 1f));

        Button refresh = textButton("⟳", text);
        refresh.setContentDescription("刷新");
        refresh.setOnClickListener(view -> { dismissError(); web.reload(); });
        bar.addView(refresh, new LinearLayout.LayoutParams(dp(40), dp(40)));

        // 部分接口（如官方搜索）在代理出口下会被拦，这里允许本页一键切换出口
        routeButton = textButton("直连", text);
        routeButton.setTextSize(12f);
        routeButton.setContentDescription("切换本页网络出口");
        routeButton.setOnClickListener(view -> toggleRoute());
        bar.addView(routeButton, new LinearLayout.LayoutParams(dp(50), dp(40)));
        updateRouteButton();

        Button app = textButton("App", text);
        app.setContentDescription("打开官方 App");
        app.setOnClickListener(view -> openOfficialApp());
        bar.addView(app, new LinearLayout.LayoutParams(dp(48), dp(40)));

        Button external = textButton("↗", text);
        external.setContentDescription("用浏览器打开");
        external.setOnClickListener(view -> openInBrowser(currentUrl));
        bar.addView(external, new LinearLayout.LayoutParams(dp(40), dp(40)));
        chrome.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        chrome.addView(progress, new LinearLayout.LayoutParams(-1, dp(2)));

        web = new WebView(this);
        web.setBackgroundColor(surface);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (desktopMode) {
            // 桌面 UA + 980px 逻辑宽度：官方 PC 播放页按桌面布局渲染，视频区才是完整播放器
            settings.setUserAgentString(HongGuoRepository.DESKTOP_UA);
            settings.setUseWideViewPort(true);
        } else {
            settings.setUserAgentString(HongGuoRepository.userAgent(this));
        }
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String url = uri.toString();
                String scheme = uri.getScheme();
                if ("https".equals(scheme) || "http".equals(scheme)) {
                    if (HongGuoRepository.internalNavigation(HongGuoWebActivity.this, url)) return false;
                    openInBrowser(url);
                    return true;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception error) {
                    Log.w(TAG, "无法处理外部协议：" + scheme);
                }
                return true;
            }

            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                currentUrl = url;
                progress.setVisibility(View.VISIBLE);
                dismissError();
            }

            @Override public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                CharSequence pageTitle = view.getTitle();
                if (pageTitle != null && pageTitle.length() > 0) {
                    titleView.setText(pageTitle.toString().replaceAll("\\s*\\|.*$", ""));
                }
                startVideoProbe();
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    String reason = Build.VERSION.SDK_INT >= 23 ? String.valueOf(error.getDescription()) : "网络异常";
                    showError("官方页面加载失败：" + reason);
                }
            }

            @Override public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                // 渲染进程被系统回收（低内存 / 内核崩溃）时默认会连带杀掉整个 App，这里只重建这一页
                Log.w(TAG, "WebView 渲染进程退出，重建页面");
                handler.removeCallbacks(videoProbe);
                if (web != null) {
                    root.removeView(web);
                    web.destroy();
                    web = null;
                }
                long now = System.currentTimeMillis();
                if (renderGone || now - lastRenderGoneAt < 15_000L) finish();   // 短时间内连续崩溃就退出，避免无限重建
                else { renderGone = true; lastRenderGoneAt = now; recreate(); }
                return true;
            }
        });

        chromeClient = new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) {
                progress.setProgress(value);
                if (value >= 100) progress.setVisibility(View.GONE);
            }

            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customCallback = callback;
                chrome.setVisibility(View.GONE);
                root.addView(view, new FrameLayout.LayoutParams(-1, -1));
                root.setBackgroundColor(Color.BLACK);
                hideSystemBars();
            }

            @Override public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                chrome.setVisibility(View.VISIBLE);
                chrome.setBackgroundColor(surface);
                showSystemBars();
                if (customCallback != null) {
                    try { customCallback.onCustomViewHidden(); } catch (Exception ignored) { }
                    customCallback = null;
                }
            }
        };
        web.setWebChromeClient(chromeClient);
        chrome.addView(web, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(chrome, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        NetworkRoute.applyToWebView(this);
        web.loadUrl(currentUrl);
        Log.i(TAG, "hongguo-web open " + currentUrl);
    }

    private Button textButton(String label, int color) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(color);
        button.setTextSize(label.length() > 2 ? 12f : 18f);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setPadding(0, 0, 0, 0);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
    }

    private void showSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().getInsetsController().show(WindowInsets.Type.systemBars());
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
    }

    private boolean proxied() {
        return !NetworkRoute.forcedDirect() && NetworkRoute.available(this);
    }

    private void updateRouteButton() {
        if (routeButton == null) return;
        boolean onProxy = proxied();
        routeButton.setText(onProxy ? "直连" : "代理");
        routeButton.setContentDescription(onProxy ? "本页改走直连" : "本页改回代理");
    }

    /**
     * 官方部分接口（搜索等）在代理出口下会返回加载失败，而主站能打开。
     * 这里给用户一个本页级别的出口切换：切直连走 10 分钟临时避让，切回代理则清除避让。
     */
    private void toggleRoute() {
        boolean onProxy = proxied();
        if (onProxy) {
            NetworkRoute.avoidProxyTemporarily();
            toastText("本页已切换到直连，10 分钟后自动恢复代理");
        } else {
            NetworkRoute.clearAvoid();
            NetworkRoute.refresh(this);
            toastText("已恢复设置里的代理出口");
        }
        updateRouteButton();
        handler.postDelayed(() -> { if (web != null) web.reload(); }, 400L);
    }

    private void toastText(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void openInBrowser(String url) {        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception error) {
            Log.w(TAG, "浏览器打开失败：" + error.getMessage());
        }
    }

    private void openOfficialApp() {
        // 全集只在官方 App 内免费：已装就直接拉起，没装走官方分发渠道
        OfficialAppLauncher.Result route = OfficialAppLauncher.open(this);
        if (route == OfficialAppLauncher.Result.NONE) {
            Toast.makeText(this, "没能打开红果短剧，请到应用商店搜索「红果免费短剧」", Toast.LENGTH_LONG).show();
        }
    }

    private void showError(String message) {
        if (errorPanel != null || isDestroyed()) return;
        errorPanel = new LinearLayout(this);
        errorPanel.setOrientation(LinearLayout.VERTICAL);
        errorPanel.setGravity(Gravity.CENTER);
        errorPanel.setPadding(dp(26), dp(26), dp(26), dp(26));
        errorPanel.setBackgroundColor(surfaceColor);
        TextView label = new TextView(this);
        label.setText(message);
        label.setTextColor(textColor);
        label.setTextSize(14f);
        label.setGravity(Gravity.CENTER);
        errorPanel.addView(label, new LinearLayout.LayoutParams(-1, -2));
        TextView hint = new TextView(this);
        hint.setText("若一直失败，可点顶部「直连」换一个出口再重试");
        hint.setTextColor(textColor);
        hint.setAlpha(0.6f);
        hint.setTextSize(12f);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(8), 0, 0);
        errorPanel.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        Button retry = textButton("重试", textColor);
        retry.setTextSize(13f);
        retry.setOnClickListener(view -> { dismissError(); web.reload(); });
        Button browser = textButton("用浏览器打开", textColor);
        browser.setTextSize(13f);
        browser.setOnClickListener(view -> openInBrowser(currentUrl));
        actions.addView(retry);
        actions.addView(browser);
        errorPanel.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        root.addView(errorPanel, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
    }

    private void dismissError() {
        if (errorPanel == null) return;
        root.removeView(errorPanel);
        errorPanel = null;
    }

    /** 只观测官方页面自己的 video 状态，便于确认是否真的出流；不注入、不改写播放逻辑。 */
    private void startVideoProbe() {
        probeCount = 0;
        handler.removeCallbacks(videoProbe);
        handler.postDelayed(videoProbe, 1200L);
    }

    private final Runnable videoProbe = new Runnable() {
        @Override public void run() {
            if (isDestroyed() || web == null) return;
            probeCount++;
            web.evaluateJavascript(
                    "(function(){var v=document.querySelector('video');return JSON.stringify({"
                            + "src:v?(v.currentSrc||v.src||''):'',ready:v?v.readyState:-1,"
                            + "time:v?Math.round(v.currentTime*10)/10:0,paused:v?v.paused:true,"
                            + "title:document.title});})()",
                    value -> {
                        if (value == null) return;
                        Log.i(TAG, "hongguo-web video " + value);
                        if (value.contains("paused\\\":false")) {
                            Log.i(TAG, "hongguo-web playing " + value);
                            handler.removeCallbacks(this);
                        }
                    });
            if (probeCount < 40) handler.postDelayed(this, 2000L);
        }
    };

    @Override public void onBackPressed() {
        if (customView != null) { chromeClient.onHideCustomView(); return; }
        if (web != null && web.canGoBack()) { web.goBack(); return; }
        super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override protected void onPause() {
        if (web != null) web.onPause();
        // 官方登录态与试看进度都在 Cookie 里，不落盘下次进来就白看了
        try { CookieManager.getInstance().flush(); } catch (Throwable ignored) { }
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try { CookieManager.getInstance().flush(); } catch (Throwable ignored) { }
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
