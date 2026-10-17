package app.vercel.uxgpt;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.CookieManager;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(UxgptBgPlugin.class);
        // First-party marker cookie so every request from the app (RSC fetches, OAuth returns) passes the app gate.
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setCookie("https://uxgpt.vercel.app", "uxgpt_app=1; Max-Age=31536000; Path=/; Secure; SameSite=Lax");
            cm.flush();
        } catch (Exception ignored) {}
        super.onCreate(savedInstanceState);
        clearCacheOnUpdate();
        PollWorker.schedule(this, false);
        openFromNotification(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        openFromNotification(intent);
    }

    /** A new app build must never run a stale cached web build: wipe the WebView HTTP cache when versionCode changes
     *  (login session / localStorage are kept). */
    private void clearCacheOnUpdate() {
        try {
            long code = Build.VERSION.SDK_INT >= 28 ? getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode() : getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            android.content.SharedPreferences sp = getSharedPreferences("uxgpt_app", MODE_PRIVATE);
            if (sp.getLong("vc", -1) != code) {
                getBridge().getWebView().clearCache(true);
                sp.edit().putLong("vc", code).apply();
                getBridge().getWebView().reload();
            }
        } catch (Exception ignored) {}
    }

    private void openFromNotification(Intent i) {
        Uri data = i == null ? null : i.getData();
        if (data != null && "uxgpt".equals(data.getScheme()) && "oauth".equals(data.getHost())) {
            String q = data.getEncodedQuery();
            i.setData(null);
            final String target = "https://uxgpt.vercel.app/chat" + (q != null ? "?" + q : "");
            if (getBridge() != null) getBridge().getWebView().post(() -> getBridge().getWebView().loadUrl(target));
            return;
        }
        String u = i == null ? null : i.getStringExtra("uxgpt_url");
        if (u == null || !u.startsWith("/") || u.startsWith("//") || getBridge() == null) return;
        i.removeExtra("uxgpt_url");
        getBridge().getWebView().postDelayed(() -> getBridge().getWebView().loadUrl("https://uxgpt.vercel.app" + u), 300);
    }
}
