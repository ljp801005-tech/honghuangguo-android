package com.huangguo.mobile;

import android.app.Activity;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.RoundedCorner;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JsResult;
import com.parse.ParseUser;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.FrameLayout;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceResponse;
import android.webkit.WebResourceError;
import android.webkit.RenderProcessGoneDetail;
import org.json.JSONTokener;

import org.json.JSONObject;
import java.io.File;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String TAG = "HuangGuo";
    private static final int AVATAR_PICKER = 7101;
    private static final int BACKUP_EXPORT = 7102;
    private static final int BACKUP_IMPORT = 7103;
    private String pendingBackupJson;
    private final ExecutorService requests = Executors.newFixedThreadPool(4);
    private WebView webView;
    private FrameLayout root;
    private SiteRepository repository;
    private AppStore store;
    private AccountService account;
    private UpdateManager updates;
    private HotUpdateManager hotUpdates;
    private HotUpdateManager.Session hotSession;
    private boolean migratingOrigin;
    private boolean resourceReady;
    private final Handler hotHandler = new Handler(Looper.getMainLooper());
    private final Runnable resourceTimeout = () -> recoverResources();
    private File pendingUpdateApk;
    private OnBackInvokedCallback backCallback;
    private boolean backRegistered;
    private int backProgressGeneration;
    private float pendingBackProgress = -1f;
    private boolean backProgressInFlight;
    private int backViewLeft;
    private int backViewWidth;
    private float backLeadPx;
    private volatile float statusBarInsetDp;
    private boolean detailSystemBars;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        hotUpdates = HotUpdateManager.get(this);
        hotSession = hotUpdates.beginSession();
        repository = new SiteRepository(this, hotSession.site);
        store = new AppStore(this);
        android.content.SharedPreferences hotPrefs = getSharedPreferences("hot-updates", MODE_PRIVATE);
        String previousRules = hotPrefs.getString("siteFingerprint", "");
        if (!previousRules.isEmpty() && !previousRules.equals(hotSession.site.fingerprint)) store.clearCache();
        hotPrefs.edit().putString("siteFingerprint", hotSession.site.fingerprint).apply();
        migratingOrigin = !hotPrefs.getBoolean("originMigrated", false);
        account = new AccountService(this, store);
        try {
            ParseUser current = ParseUser.getCurrentUser();
            store.activateUser(current == null ? "" : current.getObjectId());
        } catch (Exception error) { Log.w(TAG, "无法恢复账号数据", error); }
        updates = new UpdateManager(this);
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        root = new FrameLayout(this);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.TRANSPARENT);
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        UiRefreshRate.apply(this, webView);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attributes);
        }
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars());
                statusBarInsetDp = safe.top / getResources().getDisplayMetrics().density;
                root.setPadding(safe.left, 0, safe.right, safe.bottom);
            } else {
                statusBarInsetDp = insets.getSystemWindowInsetTop() / getResources().getDisplayMetrics().density;
                root.setPadding(insets.getSystemWindowInsetLeft(), 0,
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            publishWindowInsets();
            return insets;
        });
        applyTheme();
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(migratingOrigin);
        webView.getSettings().setAllowFileAccessFromFileURLs(false);
        webView.getSettings().setAllowUniversalAccessFromFileURLs(false);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(true);
        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", path -> hotUpdates.resource(hotSession, path)).build();
        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (HotUpdateManager.trusted(Uri.parse(url))) {
                    resourceReady = false;
                    hotHandler.removeCallbacks(resourceTimeout);
                    if (hotSession.trial) hotHandler.postDelayed(resourceTimeout, 15_000);
                }
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (migratingOrigin && "file:///android_asset/app.html".equals(url)) {
                    view.evaluateJavascript("localStorage.getItem('adult-confirmed')", value -> {
                        if (isDestroyed()) return;
                        try {
                            Object confirmed = new JSONTokener(value).nextValue();
                            getSharedPreferences("hot-updates", MODE_PRIVATE).edit().putBoolean("originMigrated", true)
                                    .putBoolean("adultConfirmed", "1".equals(confirmed)).apply();
                        } catch (Exception ignored) { }
                        migratingOrigin = false;
                        view.getSettings().setAllowFileAccess(false);
                        view.loadUrl(HotUpdateManager.UI_URL);
                    });
                    return;
                }
                publishWindowInsets();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !HotUpdateManager.trusted(request.getUrl());
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && hotSession.trial) recoverResources();
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame() && hotSession.trial && response.getStatusCode() >= 400) recoverResources();
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                try { hotUpdates.rollback(hotSession); } catch (Exception error) { Log.w(TAG, "资源回退失败", error); }
                root.removeView(view); view.destroy(); webView = null;
                recreate();
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                Log.d(TAG, message.message() + " @" + message.lineNumber());
                return true;
            }
            @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("清空", (dialog, which) -> result.confirm())
                        .setNegativeButton("取消", (dialog, which) -> result.cancel())
                        .setOnCancelListener(dialog -> result.cancel())
                        .show();
                return true;
            }
        });
        webView.addJavascriptInterface(new Bridge(), "AndroidHost");
        webView.loadUrl(migratingOrigin ? "file:///android_asset/app.html" : HotUpdateManager.UI_URL);
        checkHotUpdates();
    }

    private void checkHotUpdates() {
        requests.execute(() -> {
            try {
                JSONObject status = hotUpdates.check(false);
                runOnUiThread(() -> {
                    if (!isDestroyed() && webView != null) webView.evaluateJavascript(
                            "window.hgHotUpdateStatus && window.hgHotUpdateStatus(" + status + ")", null);
                });
            } catch (Exception error) { Log.d(TAG, "资源更新暂不可用，继续使用本地版本", error); }
        });
    }

    private void recoverResources() {
        if (resourceReady || hotSession == null || !hotSession.trial || isDestroyed()) return;
        hotHandler.removeCallbacks(resourceTimeout);
        try {
            hotUpdates.rollback(hotSession);
            hotSession = hotUpdates.beginSession();
            repository = new SiteRepository(this, hotSession.site);
            store.clearCache();
            getSharedPreferences("hot-updates", MODE_PRIVATE).edit()
                    .putString("siteFingerprint", hotSession.site.fingerprint).apply();
            webView.loadUrl(HotUpdateManager.UI_URL);
            Log.w(TAG, "资源包启动异常，已回退至可用版本");
        } catch (Exception error) { Log.e(TAG, "资源回退失败", error); }
    }

    private void applyPendingResources() {
        if (!hotUpdates.hasPending() || webView == null || isDestroyed()) return;
        webView.evaluateJavascript("window.hgCanApplyHotUpdate ? window.hgCanApplyHotUpdate() : false", safe -> {
            if ("true".equals(safe) && !isDestroyed()) recreate();
        });
    }

    private boolean dark() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private float screenCornerRadius(int position) {
        if (Build.VERSION.SDK_INT >= 31) {
            RoundedCorner corner = getDisplay() == null ? null : getDisplay().getRoundedCorner(position);
            if (corner != null && corner.getRadius() > 0)
                return corner.getRadius() / getResources().getDisplayMetrics().density;
        }
        return 22f;
    }

    private JSONObject screenCorners() throws Exception {
        return new JSONObject()
                .put("topLeft", screenCornerRadius(RoundedCorner.POSITION_TOP_LEFT))
                .put("topRight", screenCornerRadius(RoundedCorner.POSITION_TOP_RIGHT))
                .put("bottomRight", screenCornerRadius(RoundedCorner.POSITION_BOTTOM_RIGHT))
                .put("bottomLeft", screenCornerRadius(RoundedCorner.POSITION_BOTTOM_LEFT));
    }

    private void applyTheme() {
        boolean dark = dark();
        int background = Color.parseColor(dark ? "#171B1D" : "#F5F2E9");
        root.setBackgroundColor(background);
        applySystemBars();
        if (webView != null) webView.evaluateJavascript("window.hgTheme && window.hgTheme(" + dark + ")", null);
    }

    private void publishWindowInsets() {
        if (webView != null) webView.evaluateJavascript("window.hgWindowInsets && window.hgWindowInsets(" + statusBarInsetDp + ")", null);
    }

    private void applySystemBars() {
        boolean dark = dark();
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.parseColor(dark ? "#171B1D" : "#F5F2E9"));
        if (Build.VERSION.SDK_INT >= 29) getWindow().setStatusBarContrastEnforced(false);
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (!dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        if (!dark && !detailSystemBars) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        applyTheme();
    }

    @Override protected void onResume() {
        super.onResume();
        applySystemBars();
        if (webView != null) UiRefreshRate.apply(this, webView);
        if (webView != null) webView.evaluateJavascript("window.hgResume && window.hgResume()", null);
        if (hotUpdates != null && !migratingOrigin) {
            applyPendingResources();
            checkHotUpdates();
        }
        if (account != null && ParseUser.getCurrentUser() != null) requests.execute(() -> {
            try {
                account.sync();
                runOnUiThread(() -> webView.evaluateJavascript("window.hgCloudSynced && window.hgCloudSynced()", null));
            } catch (Exception error) { Log.w(TAG, "云端同步稍后重试", error); }
        });
        if (pendingUpdateApk != null) {
            File apk = pendingUpdateApk;
            pendingUpdateApk = null;
            if (getPackageManager().canRequestPackageInstalls()) openUpdateInstaller(apk);
            else webView.evaluateJavascript("window.hgUpdateInstallPermissionDenied && window.hgUpdateInstallPermissionDenied()", null);
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && webView != null) UiRefreshRate.apply(this, webView);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == BACKUP_EXPORT) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                notifyBackup(false, "已取消导出");
                return;
            }
            final Uri target = data.getData();
            final String json = pendingBackupJson;
            requests.execute(() -> {
                try (java.io.OutputStream out = getContentResolver().openOutputStream(target)) {
                    if (out == null) throw new IllegalStateException("无法写入所选文件");
                    byte[] bytes = json.getBytes("UTF-8");
                    out.write(bytes);
                    out.flush();
                    notifyBackup(true, "备份已保存（" + bytes.length + " 字节）");
                } catch (Exception error) {
                    notifyBackup(false, "导出失败：" + (error.getMessage() == null ? "写入错误" : error.getMessage()));
                }
            });
            return;
        }
        if (requestCode == BACKUP_IMPORT) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                notifyBackup(false, "已取消恢复");
                return;
            }
            final Uri source = data.getData();
            requests.execute(() -> {
                try (java.io.InputStream in = getContentResolver().openInputStream(source);
                     java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream()) {
                    if (in == null) throw new IllegalStateException("无法读取所选文件");
                    byte[] chunk = new byte[8192];
                    int count;
                    long total = 0;
                    while ((count = in.read(chunk)) != -1) {
                        total += count;
                        if (total > 2 * 1024 * 1024) throw new IllegalArgumentException("备份超过 2 MB 限制");
                        buffer.write(chunk, 0, count);
                    }
                    JSONObject result = DataManager.restoreBackup(this, store,
                            new String(buffer.toByteArray(), "UTF-8"));
                    notifyBackup(true, "已恢复 " + result.optInt("bookmarks") + " 部收藏、"
                            + result.optInt("history") + " 条观看记录、" + result.optInt("searchHistory") + " 条搜索历史");
                } catch (Exception error) {
                    notifyBackup(false, "恢复失败：" + (error.getMessage() == null ? "读取错误" : error.getMessage()));
                }
            });
            return;
        }
        if (requestCode != AVATAR_PICKER || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri selected = data.getData();
        requests.execute(() -> {
            try {
                JSONObject profile = account.setAvatar(selected);
                runOnUiThread(() -> webView.evaluateJavascript("window.hgAvatarUpdated && window.hgAvatarUpdated(" + profile + ")", null));
            } catch (Exception error) {
                String script = "window.hgAvatarError && window.hgAvatarError(" + JSONObject.quote(error.getMessage() == null ? "头像保存失败" : error.getMessage()) + ")";
                runOnUiThread(() -> webView.evaluateJavascript(script, null));
            }
        });
    }

    /** 备份/恢复结果回调给设置页。 */
    private void notifyBackup(boolean ok, String message) {
        runOnUiThread(() -> {
            if (isDestroyed() || webView == null) return;
            JSONObject payload = new JSONObject();
            try {
                payload.put("ok", ok).put("message", message == null ? "" : message);
            } catch (Exception ignored) { return; }
            webView.evaluateJavascript("window.hgBackupResult && window.hgBackupResult(" + payload + ")", null);
        });
    }

    private static String routeType(String raw) {
        try {
            String normalized = NetworkRoute.normalize(raw);
            if (normalized.isEmpty()) return "direct";
            return normalized.startsWith("socks5://") ? "socks5" : "http";
        } catch (IllegalArgumentException error) {
            return "invalid";
        }
    }

    private static String proxyHint(String raw) {
        try {
            NetworkRoute.normalize(raw);
        } catch (IllegalArgumentException error) {
            return error.getMessage();
        }
        return "代理格式应为 主机:端口、http://主机:端口 或 socks5://主机:端口";
    }

    private void installUpdate(File apk) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            pendingUpdateApk = apk;
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            startActivity(settings);
            return;
        }
        openUpdateInstaller(apk);
    }

    private void openUpdateInstaller(File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".updates", apk);
            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setData(uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (ActivityNotFoundException | IllegalArgumentException error) {
            Log.e(TAG, "无法打开系统安装程序", error);
            webView.evaluateJavascript("window.hgUpdateInstallError && window.hgUpdateInstallError()", null);
        }
    }

    private void updateBackRegistration(boolean enabled) {
        if (Build.VERSION.SDK_INT < 33 || enabled == backRegistered) return;
        if (enabled) {
            if (Build.VERSION.SDK_INT >= 34) {
                backCallback = new OnBackAnimationCallback() {
                    @Override public void onBackStarted(BackEvent event) {
                        resetBackProgress();
                        captureBackGeometry();
                        webView.evaluateJavascript("window.hgPredictiveBackStart && window.hgPredictiveBackStart(" +
                                (event.getSwipeEdge() == BackEvent.EDGE_RIGHT) + ")", null);
                    }

                    @Override public void onBackProgressed(BackEvent event) {
                        pendingBackProgress = backTouchFraction(event);
                        sendLatestBackProgress();
                    }

                    @Override public void onBackCancelled() {
                        resetBackProgress();
                        webView.evaluateJavascript("window.hgPredictiveBackCancel && window.hgPredictiveBackCancel()", null);
                    }

                    @Override public void onBackInvoked() {
                        resetBackProgress();
                        handleBack(true);
                    }
                };
            } else backCallback = () -> handleBack(false);
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        } else {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
            backCallback = null;
        }
        backRegistered = enabled;
    }

    private void resetBackProgress() {
        backProgressGeneration++;
        pendingBackProgress = -1f;
        backProgressInFlight = false;
    }

    private void captureBackGeometry() {
        int[] location = new int[2];
        webView.getLocationOnScreen(location);
        backViewLeft = location[0];
        backViewWidth = webView.getWidth();
        backLeadPx = getResources().getDisplayMetrics().density * 18f;
    }

    private float backTouchFraction(BackEvent event) {
        float touchX = event.getTouchX();
        int edge = event.getSwipeEdge();
        if (Float.isNaN(touchX) || edge == BackEvent.EDGE_NONE || backViewWidth == 0)
            return event.getProgress();
        float width = backViewWidth;
        float distance = edge == BackEvent.EDGE_RIGHT
                ? backViewLeft + width - touchX : touchX - backViewLeft;
        distance = Math.max(0f, distance);
        float lead = Math.min(backLeadPx, distance * .35f);
        return Math.min(1f, (distance + lead) / width);
    }

    private void sendLatestBackProgress() {
        if (backProgressInFlight || pendingBackProgress < 0 || webView == null) return;
        float progress = pendingBackProgress;
        pendingBackProgress = -1f;
        backProgressInFlight = true;
        int generation = backProgressGeneration;
        webView.evaluateJavascript("window.hgPredictiveBackProgress && window.hgPredictiveBackProgress(" +
                progress + ")", ignored -> {
            if (generation != backProgressGeneration) return;
            backProgressInFlight = false;
            sendLatestBackProgress();
        });
    }

    private void handleBack(boolean predictive) {
        if (webView == null) { finish(); return; }
        String script = predictive
                ? "window.hgPredictiveBackCommit ? window.hgPredictiveBackCommit() : (window.hgBack ? window.hgBack() : false)"
                : "window.hgBack ? window.hgBack() : false";
        webView.evaluateJavascript(script, result -> {
            if (!"true".equals(result) && !moveTaskToBack(true)) finish();
        });
    }

    @Override public void onBackPressed() {
        handleBack(false);
    }

    @Override protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && backRegistered) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        hotHandler.removeCallbacksAndMessages(null);
        requests.shutdownNow();
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidHost");
            webView.destroy();
        }
        super.onDestroy();
    }

    private Object dispatch(String action, JSONObject args) throws Exception {
        switch (action) {
            case "hot-update-status": return hotUpdates.status();
            case "check-hot-update": return hotUpdates.check(true);
            case "apply-hot-update": runOnUiThread(this::applyPendingResources); return true;
            case "age-status": return getSharedPreferences("hot-updates", MODE_PRIVATE).getBoolean("adultConfirmed", false);
            case "confirm-age": getSharedPreferences("hot-updates", MODE_PRIVATE).edit().putBoolean("adultConfirmed", true).apply(); return true;
            case "hot-ready": {
                if (!migratingOrigin && args.optLong("revision", -1) == hotSession.revision
                        && hotSession.bootId.equals(args.optString("bootId"))) {
                    hotUpdates.markHealthy(hotSession);
                    runOnUiThread(() -> { resourceReady = true; hotHandler.removeCallbacks(resourceTimeout); });
                }
                return true;
            }
            case "theme": return dark();
            case "screen-corners": return screenCorners();
            case "window-insets": return statusBarInsetDp;
            case "account": return account.profile();
            case "register": return account.register(args.optString("username"), args.optString("password"));
            case "login": return account.login(args.optString("username"), args.optString("password"));
            case "change-password": return account.changePassword(args.optString("oldPassword"), args.optString("newPassword"));
            case "logout": account.logout(); return account.profile();
            case "sync": return account.sync();
            case "choose-avatar": {
                if (ParseUser.getCurrentUser() == null) throw new IllegalStateException("请先登录");
                runOnUiThread(() -> {
                    Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    picker.addCategory(Intent.CATEGORY_OPENABLE);
                    picker.setType("image/*");
                    startActivityForResult(picker, AVATAR_PICKER);
                });
                return true;
            }
            case "app-version": return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            case "cached-update": return updates.cached();
            case "check-update": return updates.check(args.optBoolean("manual"));
            case "download-update": {
                File apk = updates.download(percent -> runOnUiThread(() ->
                        webView.evaluateJavascript("window.hgUpdateProgress && window.hgUpdateProgress(" + percent + ")", null)));
                runOnUiThread(() -> installUpdate(apk));
                return true;
            }
            case "cached": return store.cached(args.optString("key"));
            case "home": {
                JSONObject result = repository.home();
                store.saveCache("home", result);
                return result;
            }
            case "category": {
                String id = args.optString("id");
                int page = args.optInt("page", 1);
                JSONObject result = repository.category(id, page);
                store.saveCache("category:" + id + ":" + page, result);
                return result;
            }
            case "tags": {
                JSONObject result = repository.tags();
                store.saveCache("tags", result);
                return result;
            }
            case "tag-list": {
                String slug = args.optString("slug");
                int page = args.optInt("page", 1);
                JSONObject result = repository.tagListing(slug, page);
                store.saveCache("tag:" + slug + ":" + page, result);
                return result;
            }
            case "recommend":
            case "newest": {
                int page = args.optInt("page", 1);
                JSONObject result = repository.recommend(page, "newest".equals(action));
                store.saveCache(action + ":" + page, result);
                return result;
            }
            case "search": {
                String term = args.optString("query").trim();
                JSONObject result = repository.search(term);
                store.saveCache("search:" + term, result);
                return result;
            }
            case "detail": {
                String id = args.optString("id");
                JSONObject result = repository.detail(id);
                store.saveCache("detail:" + id, result);
                return result;
            }
            case "episode": return repository.episode(args.optString("id"), args.optInt("episode", 1));
            case "cover": return repository.coverData(args.optString("url"));
            case "library": return store.library();
            case "bookmark": {
                boolean saved = store.toggleBookmark(args);
                if (ParseUser.getCurrentUser() != null) requests.execute(() -> {
                    try { account.sync(); } catch (Exception error) { Log.w(TAG, "收藏稍后同步", error); }
                });
                return saved;
            }
            case "bookmark-state": return store.hasBookmark(args.optString("id"));
            case "get-progress": return store.getProgress(args.optString("id"), args.optInt("episode", 1));
            case "clear-history": store.clearHistory(); if (ParseUser.getCurrentUser() != null) requests.execute(() -> { try { account.sync(); } catch (Exception ignored) { } }); return true;
            case "clear-bookmarks": store.clearBookmarks(); if (ParseUser.getCurrentUser() != null) requests.execute(() -> { try { account.sync(); } catch (Exception ignored) { } }); return true;
            case "clear-content-cache": store.clearCache(); repository.clearCovers(); return true;
            case "quit": runOnUiThread(this::finish); return true;
            case "open-player": {
                Intent intent = new Intent(this, PlayerActivity.class);
                intent.putExtra("payload", args.toString());
                runOnUiThread(() -> startActivity(intent));
                return true;
            }
            // 红果短剧（官方网页版）：只读公开页面 + 官方 H5 播放容器
            case "hongguo-config": return HongGuoRepository.config(this);
            case "hongguo-config-set": return HongGuoRepository.updateConfig(this, args);
            case "hongguo-feed": return HongGuoRepository.feed(this, args.optString("category", "home"));
            case "hongguo-detail": return HongGuoRepository.detail(this, args.optString("id"));
            case "hongguo-open-app": {
                OfficialAppLauncher.Result route = OfficialAppLauncher.open(this);
                switch (route) {
                    case LAUNCHED: return "已打开红果短剧 App，全集在那里免费看";
                    case STORE: return "未安装红果短剧，已打开应用商店";
                    case BROWSER: return "未安装红果短剧，已打开下载页";
                    default: return "没能打开红果短剧，请到应用商店搜索「红果免费短剧」";
                }
            }
            case "hongguo-open": {
                String url = args.optString("url");
                if (!HongGuoRepository.allowedTarget(this, url)) {
                    throw new IllegalArgumentException("不是有效的红果页面地址");
                }
                Intent intent = new Intent(this, HongGuoWebActivity.class);
                intent.putExtra(HongGuoWebActivity.EXTRA_URL, url);
                intent.putExtra(HongGuoWebActivity.EXTRA_TITLE, args.optString("title", "红果短剧"));
                intent.putExtra(HongGuoWebActivity.EXTRA_DESKTOP, args.optBoolean("desktop", false));
                runOnUiThread(() -> startActivity(intent));
                return true;
            }
            case "proxy-get": {
                JSONObject result = new JSONObject();
                String configured = NetworkRoute.configured(this);
                result.put("configured", configured);
                result.put("value", NetworkRoute.normalize(configured));
                result.put("defaultProxy", NetworkRoute.DEFAULT_PROXY);
                result.put("type", routeType(configured));
                result.put("active", NetworkRoute.label(this));
                return result;
            }
            case "proxy-set": {
                String raw = args.optString("proxy", "default");
                if (!NetworkRoute.valid(raw)) throw new IllegalArgumentException(proxyHint(raw));
                NetworkRoute.setProxy(this, raw);
                NetworkRoute.refresh(this);   // 立刻按新配置重挂 WebView 代理
                JSONObject result = new JSONObject();
                result.put("configured", NetworkRoute.configured(this));
                result.put("value", NetworkRoute.normalize(raw));
                result.put("type", routeType(raw));
                result.put("active", NetworkRoute.label(this));
                return result;
            }
            case "proxy-test": {
                String raw = args.optString("proxy", NetworkRoute.configured(this));
                if (!NetworkRoute.valid(raw)) throw new IllegalArgumentException(proxyHint(raw));
                long latency = NetworkRoute.probe(raw);
                JSONObject result = new JSONObject();
                result.put("ok", latency >= 0);
                result.put("latency", latency);
                result.put("type", routeType(raw));
                return result;
            }
            case "play-settings-get": return PlaySettings.snapshot(this);
            case "play-settings-set": return PlaySettings.update(this, args);
            case "search-history-get": return new JSONObject().put("history", DataManager.searchHistory(this));
            case "search-history-add": return new JSONObject().put("history",
                    DataManager.addSearchHistory(this, args.optString("term")));
            case "search-history-clear": DataManager.clearSearchHistory(this); return true;
            case "bookmark-updates": return DataManager.checkBookmarkUpdates(this, store, repository,
                    args.optBoolean("force"));
            case "backup-export": {
                pendingBackupJson = DataManager.exportBackup(this, store).toString();
                runOnUiThread(() -> {
                    Intent picker = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    picker.addCategory(Intent.CATEGORY_OPENABLE);
                    picker.setType("application/json");
                    picker.putExtra(Intent.EXTRA_TITLE, "红黄果-备份-"
                            + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.CHINA)
                                    .format(new java.util.Date()) + ".json");
                    try { startActivityForResult(picker, BACKUP_EXPORT); }
                    catch (ActivityNotFoundException error) { notifyBackup(false, "系统没有可用的文件保存界面"); }
                });
                return true;   // 实际结果由 hgBackupResult 回调
            }
            case "backup-import": {
                runOnUiThread(() -> {
                    Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    picker.addCategory(Intent.CATEGORY_OPENABLE);
                    picker.setType("*/*");
                    try { startActivityForResult(picker, BACKUP_IMPORT); }
                    catch (ActivityNotFoundException error) { notifyBackup(false, "系统没有可用的文件选择界面"); }
                });
                return true;
            }
            default: throw new IllegalArgumentException("未知请求");
        }
    }

    private final class Bridge {
        @JavascriptInterface public void setDetailMode(boolean enabled) {
            runOnUiThread(() -> {
                if (detailSystemBars == enabled) return;
                detailSystemBars = enabled;
                applySystemBars();
            });
        }
        @JavascriptInterface public void setBackEnabled(boolean enabled) {
            runOnUiThread(() -> updateBackRegistration(enabled));
        }

        @JavascriptInterface public void request(String requestId, String action, String argsText) {
            requests.execute(() -> {
                try {
                    JSONObject args = new JSONObject(argsText);
                    Object result = dispatch(action, args);
                    String data;
                    if (result == null) data = "null";
                    else if (result instanceof JSONObject) data = result.toString();
                    else if (result instanceof Boolean || result instanceof Number) data = result.toString();
                    else data = JSONObject.quote(String.valueOf(result));
                    String script = "window.NativeCallbacks.resolve(" + JSONObject.quote(requestId) + "," + data + ")";
                    String authUserId = ("login".equals(action) || "register".equals(action)) && result instanceof JSONObject
                            ? ((JSONObject) result).optString("userId") : "";
                    runOnUiThread(() -> {
                        if (isDestroyed() || webView == null) return;
                        webView.evaluateJavascript(script, null);
                        if (!authUserId.isEmpty()) refreshAccountAfterAuth(authUserId);
                    });
                } catch (Exception error) {
                    Log.e(TAG, action + " failed", error);
                    String script = "window.NativeCallbacks.reject(" + JSONObject.quote(requestId) + "," + JSONObject.quote(error.getMessage() == null ? "请求失败" : error.getMessage()) + ")";
                    runOnUiThread(() -> { if (!isDestroyed() && webView != null) webView.evaluateJavascript(script, null); });
                }
            });
        }
    }

    private void refreshAccountAfterAuth(String userId) {
        requests.execute(() -> {
            try {
                account.sync();
                runOnUiThread(() -> webView.evaluateJavascript("window.hgCloudSynced && window.hgCloudSynced()", null));
            } catch (Exception error) { Log.w(TAG, "登录后片单稍后同步", error); }
            try {
                JSONObject refreshed = account.profile();
                if (!userId.equals(refreshed.optString("userId"))) return;
                String script = "window.hgAccountRefreshed && window.hgAccountRefreshed(" + refreshed + ")";
                runOnUiThread(() -> webView.evaluateJavascript(script, null));
            } catch (Exception error) { Log.w(TAG, "登录后账号资料稍后刷新", error); }
        });
    }
}
