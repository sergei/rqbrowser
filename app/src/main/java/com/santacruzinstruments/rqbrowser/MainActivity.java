package com.santacruzinstruments.rqbrowser;

import static android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    static class MyWebViewClient extends WebViewClient {
        @Override
        public void onScaleChanged(WebView view, float oldScale, float newScale) {
            super.onScaleChanged(view, oldScale, newScale);
            if (D)
                Log.d(TAG, "onScaleChanged() called with: view = [" + view + "], oldScale = [" + oldScale + "], newScale = [" + newScale + "]");
        }
    }

    class MyWebChromeClient extends WebChromeClient {
        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (D)
                Log.d(TAG, "onShowCustomView() called with: view = [" + view + "], callback = [" + callback + "]");
            // if a view already exists then immediately terminate the new one
            if (mCustomView != null) {
                callback.onCustomViewHidden();
                return;
            }
            mCustomView = view;
            mWebView.setVisibility(View.GONE);
            mCustomViewContainer.setVisibility(View.VISIBLE);
            mCustomViewContainer.addView(view);
            mCustomViewCallback = callback;
            hideSystemUI();
        }

        @Override
        public void onHideCustomView() {
            if (D) Log.d(TAG, "onHideCustomView() called");
            super.onHideCustomView();    //To change body of overridden methods use File | Settings | File Templates.
            if (mCustomView == null)
                return;

            mWebView.setVisibility(View.VISIBLE);
            mCustomViewContainer.setVisibility(View.GONE);

            // Hide the custom view.
            mCustomView.setVisibility(View.GONE);

            // Remove the custom view from its container.
            mCustomViewContainer.removeView(mCustomView);
            mCustomViewCallback.onCustomViewHidden();

            mCustomView = null;
            showSystemUI();
        }


        private void hideSystemUI() {
            // Enables regular immersive mode.
            // For "lean back" mode, remove SYSTEM_UI_FLAG_IMMERSIVE.
            // Or for "sticky immersive," replace it with SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE
                            // Set the content to appear under the system bars so that the
                            // content doesn't resize when the system bars hide and show.
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            // Hide the nav bar and status bar
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }

        // Shows the system bars by removing all the flags
        // except for the ones that make the content appear under the system bars.
        private void showSystemUI() {
            View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    private static final boolean D = true;
    private static final String TAG = "MainActivity";

    // Web stuff
    private static final String RACEQS_URL = "http://raceqs.org";
//    private static final String RACEQS_URL = "https://test.raceqs.com";

    private WebView mWebView;
    private FrameLayout mCustomViewContainer;
    private WebChromeClient.CustomViewCallback mCustomViewCallback;
    private View mCustomView;
    private MyWebChromeClient mWebChromeClient;

    // WiFi stuff
    private static final String OTTOPI_SSID = "OTTOPI";
    private WifiManager mWifiManager;
    WifiManager.WifiLock mWifiLock = null;
    private static final String SIGNALK_HOST = "192.168.4.1";
    private static final int CHECK_CONNECTION_TIMEOUT_MS = 5000;
    Thread mNetworkCheckerThread;
    boolean mKeepRunning = true;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate() called with: savedInstanceState = [" + savedInstanceState + "]");
        super.onCreate(savedInstanceState);
        holdWifiLock();

        setContentView(R.layout.activity_main);
        mWebView = findViewById(R.id.webview); // Non full screen view
        mCustomViewContainer = findViewById(R.id.customViewContainer); // Full screen view

        MyWebViewClient webClient = new MyWebViewClient();
        mWebView.setWebViewClient(webClient);

        mWebChromeClient = new MyWebChromeClient();
        mWebView.setWebChromeClient(mWebChromeClient);
        mWebView.getSettings().setJavaScriptEnabled(true);
        mWebView.getSettings().setAppCacheEnabled(true);
        mWebView.getSettings().setMixedContentMode(MIXED_CONTENT_ALWAYS_ALLOW);
        mWebView.loadUrl(RACEQS_URL);

        mWebChromeClient.hideSystemUI();

        mNetworkCheckerThread = new Thread(() -> {
            while (mKeepRunning) {
                checkWiFi();
                try {
                    //noinspection BusyWait
                    Thread.sleep(CHECK_CONNECTION_TIMEOUT_MS);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
        mNetworkCheckerThread.start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mWebChromeClient.showSystemUI();
        releaseWifiLock();
        mKeepRunning = false;
        mNetworkCheckerThread.interrupt();
        try {
            mNetworkCheckerThread.join();
        } catch (InterruptedException ignore) {
        }
    }


    /***
     * Calling this method will aquire the lock on wifi. This is avoid wifi
     * from going to sleep as long as <code>releaseWifiLock</code> method is called.
     **/
    private void holdWifiLock() {
        mWifiManager = (WifiManager) getBaseContext().getSystemService(Context.WIFI_SERVICE);

        if (mWifiLock == null)
            mWifiLock = mWifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, TAG);

        mWifiLock.setReferenceCounted(false);

        if (!mWifiLock.isHeld())
            mWifiLock.acquire();
    }

    /***
     * Calling this method will release if the lock is already help. After this method is called,
     * the Wifi on the device can goto sleep.
     **/
    private void releaseWifiLock() {

        if (mWifiLock == null)
            Log.w(TAG, "#releaseWifiLock mWifiLock was not created previously");

        if (mWifiLock != null && mWifiLock.isHeld()) {
            mWifiLock.release();
            mWifiLock = null;
        }

    }

    private void checkWiFi() {
        if (!isHostAvailable(SIGNALK_HOST, 80, CHECK_CONNECTION_TIMEOUT_MS)) {
            Log.d(TAG, "Can not connect to " + SIGNALK_HOST);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            List<WifiConfiguration> list = mWifiManager.getConfiguredNetworks();
            for( WifiConfiguration i : list ) {
                if(i.SSID != null && i.SSID.equals("\"" + OTTOPI_SSID + "\"")) {
                    Log.d(TAG, "Disconnecting from WiFi");
                    mWifiManager.disconnect();
                    mWifiManager.enableNetwork(i.networkId, true);
                    Log.d(TAG, "Connecting back...");
                    mWifiManager.reconnect();
                    break;
                }
            }
        }else{
            Log.d(TAG, "Signal K is available");
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static boolean isHostAvailable(final String host, final int port, final int timeout) {

        try (final Socket socket = new Socket()) {
            final InetAddress inetAddress = InetAddress.getByName(host);
            final InetSocketAddress inetSocketAddress = new InetSocketAddress(inetAddress, port);

            socket.connect(inetSocketAddress, timeout);
            return true;
        } catch (java.io.IOException e) {
            e.printStackTrace();
            return false;
        }
    }
}
