package az.zai.mobile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
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
import android.widget.Toast;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.ArrayList;

/**
 * Z.ai Mobile — chat.z.ai tətbiqinin WebView qabığı.
 *
 * Xüsusiyyətlər:
 *  - Şəkil/fayl göndərmə (file chooser)
 *  - Fayl endirmə (DownloadManager + icazə axını)
 *  - Swipe-to-refresh
 *  - Donma bərpası: yükləmə watchdoqu, render-proses qəzasında WebView-in yenidən yaradılması,
 *    bağlantı bərpa olanda avtomatik reload
 *  - Səsli giriş üçün mikrofon icazəsi
 *  - Tam ekran video
 *  - Geri düyməsi ilə naviqasiya, vəziyyətin saxlanması
 */
public class MainActivity extends Activity {

    private static final String START_URL = "https://chat.z.ai";
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_STORAGE = 1002;
    private static final int REQ_MIC = 1003;
    private static final long WATCHDOG_CHECK_MS = 10_000L;
    private static final long LOAD_TIMEOUT_MS = 45_000L;

    private SwipeRefreshLayout swipeRefresh;
    private FrameLayout webContainer;
    private ProgressBar progressBar;
    private LinearLayout errorView;
    private WebView webView;

    private ValueCallback<Uri[]> filePathCallback;
    private PermissionRequest pendingWebPermissionRequest;

    private String pendingUrl, pendingUa, pendingContentDisposition, pendingMimeType;
    private String lastLoadedUrl;

    private View customView;
    private FrameLayout fullscreenContainer;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private boolean pageLoading = false;
    private long loadStartedAt = 0L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ConnectivityManager.NetworkCallback networkCallback;

    /** Uzun çəkən yükləmələri (donma) aşkar edib səhifəni yenidən yükləyir. */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (pageLoading
                    && errorView != null
                    && errorView.getVisibility() != View.VISIBLE
                    && System.currentTimeMillis() - loadStartedAt > LOAD_TIMEOUT_MS) {
                pageLoading = false;
                if (webView != null) {
                    webView.reload();
                }
            }
            handler.postDelayed(this, WATCHDOG_CHECK_MS);
        }
    };

    private final DownloadListener downloadListener = (url, userAgent, contentDisposition,
                                                       mimeType, contentLength) -> {
        if (url == null) {
            return;
        }
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            Toast.makeText(this, R.string.unsupported_download, Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingUrl = url;
            pendingUa = userAgent;
            pendingContentDisposition = contentDisposition;
            pendingMimeType = mimeType;
            requestPermissions(
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        startDownload(url, userAgent, contentDisposition, mimeType);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.Theme_Zai);
        setContentView(R.layout.activity_main);

        swipeRefresh = findViewById(R.id.swipe_refresh);
        webContainer = findViewById(R.id.web_container);
        progressBar = findViewById(R.id.progress_bar);
        errorView = findViewById(R.id.error_view);
        Button retryButton = findViewById(R.id.btn_retry);

        swipeRefresh.setColorSchemeResources(R.color.brand);
        swipeRefresh.setOnRefreshListener(() -> {
            errorView.setVisibility(View.GONE);
            if (webView != null) {
                webView.reload();
            }
        });

        retryButton.setOnClickListener(v -> {
            errorView.setVisibility(View.GONE);
            if (webView != null) {
                webView.reload();
            }
        });

        createWebView();

        Bundle webState =
                savedInstanceState != null ? savedInstanceState.getBundle("zai_web_state") : null;
        if (webState != null) {
            webView.restoreState(webState);
        } else {
            loadStart(START_URL);
        }

        handler.post(watchdog);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView() {
        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webContainer.addView(webView, 0);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new ZaiWebViewClient());
        webView.setWebChromeClient(new ZaiWebChromeClient());
        webView.setDownloadListener(downloadListener);
    }

    /** Render-proses qəzalandıqdan sonra WebView-i sıfırdan yaradır. */
    private void recreateWebView() {
        try {
            webContainer.removeAllViews();
            if (webView != null) {
                webView.destroy();
            }
        } catch (Exception ignored) {
        }
        createWebView();
        loadStart(lastLoadedUrl != null ? lastLoadedUrl : START_URL);
    }

    private void loadStart(String url) {
        lastLoadedUrl = url;
        if (webView != null) {
            webView.loadUrl(url);
        }
    }

    /** true = biz emal etdik, false = WebView özü açsın. */
    private boolean handleUrl(String url) {
        if (url == null) {
            return false;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            Uri uri = Uri.parse(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            boolean internal = host.equals("z.ai") || host.endsWith(".z.ai")
                    || host.equals("authing.cn") || host.endsWith(".authing.cn");
            if (internal) {
                return false;
            }
            // Xarici keçidlər brauzerdə açılır
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception e) {
                Toast.makeText(this, R.string.no_app_found, Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        // mailto:, tel:, intent: və s.
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
        }
        return true;
    }

    private void showError() {
        progressBar.setVisibility(View.GONE);
        swipeRefresh.setRefreshing(false);
        errorView.setVisibility(View.VISIBLE);
    }

    private void startDownload(String url, String userAgent, String contentDisposition,
                               String mimeType) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) {
                request.addRequestHeader("Cookie", cookie);
            }
            if (userAgent != null) {
                request.addRequestHeader("User-Agent", userAgent);
            }
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            fileName = fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
            try {
                request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, fileName);
            } catch (Exception ignored) {
                // standart endirmə qovluğuna düşəcək
            }
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            dm.enqueue(request);
            Toast.makeText(this,
                    getString(R.string.download_started) + " " + fileName,
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void exitFullscreen() {
        if (customView == null) {
            return;
        }
        try {
            ((ViewGroup) getWindow().getDecorView()).removeView(fullscreenContainer);
            if (fullscreenContainer != null) {
                fullscreenContainer.removeAllViews();
            }
        } catch (Exception ignored) {
        }
        fullscreenContainer = null;
        customView = null;
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
        swipeRefresh.setVisibility(View.VISIBLE);
    }

    private class ZaiWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUrl(request.getUrl().toString());
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            lastLoadedUrl = url;
            pageLoading = true;
            loadStartedAt = System.currentTimeMillis();
            errorView.setVisibility(View.GONE);
            progressBar.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            pageLoading = false;
            progressBar.setVisibility(View.GONE);
            swipeRefresh.setRefreshing(false);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    WebResourceError error) {
            super.onReceivedError(view, request, error);
            if (request.isForMainFrame()) {
                pageLoading = false;
                showError();
            }
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            super.doUpdateVisitedHistory(view, url, isReload);
            if (url != null && url.startsWith("http")) {
                lastLoadedUrl = url;
            }
        }
    }

    private class ZaiWebChromeClient extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            super.onProgressChanged(view, newProgress);
            progressBar.setProgress(newProgress);
            progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);
            }
            filePathCallback = callback;

            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            String[] accept = params.getAcceptTypes();
            if (accept != null && accept.length > 0) {
                ArrayList<String> mimes = new ArrayList<>();
                for (String a : accept) {
                    if (a != null && a.contains("/")) {
                        mimes.add(a);
                    }
                }
                if (!mimes.isEmpty()) {
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
                }
            }
            try {
                startActivityForResult(
                        Intent.createChooser(intent, getString(R.string.choose_file)),
                        REQ_FILE_CHOOSER);
            } catch (Exception e) {
                filePathCallback = null;
                Toast.makeText(MainActivity.this, R.string.no_app_found,
                        Toast.LENGTH_SHORT).show();
                return false;
            }
            return true;
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            runOnUiThread(() -> {
                boolean audio = false;
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
                        audio = true;
                    }
                }
                if (!audio) {
                    request.deny();
                    return;
                }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED) {
                    request.grant(request.getResources());
                } else {
                    pendingWebPermissionRequest = request;
                    requestPermissions(
                            new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                }
            });
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = view;
            customViewCallback = callback;
            fullscreenContainer = new FrameLayout(MainActivity.this);
            fullscreenContainer.setBackgroundColor(Color.BLACK);
            fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            getWindow().addContentView(fullscreenContainer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            swipeRefresh.setVisibility(View.GONE);
        }

        @Override
        public void onHideCustomView() {
            exitFullscreen();
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    recreateWebView();
                }
            });
            return true;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            }
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_STORAGE) {
            if (granted && pendingUrl != null) {
                startDownload(pendingUrl, pendingUa, pendingContentDisposition, pendingMimeType);
            } else if (!granted) {
                Toast.makeText(this, R.string.storage_denied, Toast.LENGTH_SHORT).show();
            }
            pendingUrl = null;
            pendingUa = null;
            pendingContentDisposition = null;
            pendingMimeType = null;
        } else if (requestCode == REQ_MIC) {
            if (pendingWebPermissionRequest != null) {
                if (granted) {
                    pendingWebPermissionRequest.grant(
                            pendingWebPermissionRequest.getResources());
                } else {
                    pendingWebPermissionRequest.deny();
                    Toast.makeText(this, R.string.mic_permission_needed,
                            Toast.LENGTH_SHORT).show();
                }
                pendingWebPermissionRequest = null;
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            exitFullscreen();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerNetworkCallback();
    }

    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    runOnUiThread(() -> {
                        if (errorView.getVisibility() == View.VISIBLE && webView != null) {
                            errorView.setVisibility(View.GONE);
                            webView.reload();
                        }
                    });
                }
            };
            cm.registerNetworkCallback(request, networkCallback);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onStop() {
        if (networkCallback != null) {
            try {
                ConnectivityManager cm =
                        (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                cm.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
            networkCallback = null;
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            outState.putBundle("zai_web_state", webView.saveState());
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
