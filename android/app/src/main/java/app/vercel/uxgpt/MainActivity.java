package app.vercel.uxgpt;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.CookieManager;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(UxgptBgPlugin.class);
        registerPlugin(UxgptUpdaterPlugin.class);
        // First-party marker cookie so every request from the app (RSC fetches, OAuth returns) passes the app gate.
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setCookie("https://uxgpt.vercel.app", "uxgpt_app=1; Max-Age=31536000; Path=/; Secure; SameSite=Lax");
            cm.flush();
        } catch (Exception ignored) {}
        super.onCreate(savedInstanceState);
        // Smoothness: force GPU compositing for the WebView layer.
        try { getBridge().getWebView().setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null); } catch (Exception ignored) {}
        fitKeyboard();
        clearCacheOnUpdate();
        PollWorker.schedule(this, false);
        openFromNotification(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        openFromNotification(intent);
    }

    /** Keyboard: draw edge-to-edge on every Android version and size the WebView to the space above the soft keyboard
     *  (and above the system bars / cutouts). The web layout (h-dvh) then shrinks, so the composer and every sheet input
     *  sit directly on top of the keyboard. Replaces Capacitor's system-bar-only margin listener. */
    private void fitKeyboard() {
        try {
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            final android.view.View wv = getBridge().getWebView();
            ViewCompat.setOnApplyWindowInsetsListener(wv, (v, wi) -> {
                Insets bars = wi.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                Insets ime = wi.getInsets(WindowInsetsCompat.Type.ime());
                android.view.ViewGroup.MarginLayoutParams mlp = (android.view.ViewGroup.MarginLayoutParams) v.getLayoutParams();
                int bottom = Math.max(bars.bottom, ime.bottom);
                if (mlp.leftMargin != bars.left || mlp.topMargin != bars.top || mlp.rightMargin != bars.right || mlp.bottomMargin != bottom) {
                    mlp.leftMargin = bars.left; mlp.topMargin = bars.top; mlp.rightMargin = bars.right; mlp.bottomMargin = bottom;
                    v.setLayoutParams(mlp);
                }
                return WindowInsetsCompat.CONSUMED;
            });
            ViewCompat.requestApplyInsets(wv);
        } catch (Exception ignored) {}
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
        String link = null;
        if (data != null && "uxgpt".equals(data.getScheme()) && "share".equals(data.getHost())) {
            String code = data.getLastPathSegment();
            if (code != null && code.matches("[A-Za-z0-9_-]{1,64}")) link = "https://uxgpt.vercel.app/s/" + code;
        } else if (data != null && "uxgpt".equals(data.getScheme()) && "chat".equals(data.getHost())) {
            String id = data.getLastPathSegment();
            if (id != null && id.matches("[A-Za-z0-9_-]{6,40}")) link = "https://uxgpt.vercel.app/c/" + id;
        } else if (data != null && "uxgpt".equals(data.getScheme()) && "invite".equals(data.getHost())) link = "https://uxgpt.vercel.app/invite";
        else if (data != null && "https".equals(data.getScheme()) && "uxgpt.vercel.app".equals(data.getHost())) link = data.toString();
        if (link != null) {
            i.setData(null);
            final String target = link;
            if (getBridge() != null) getBridge().getWebView().postDelayed(() -> getBridge().getWebView().loadUrl(target), 200);
            return;
        }
        String u = i == null ? null : i.getStringExtra("uxgpt_url");
        if (u == null || !u.startsWith("/") || u.startsWith("//") || getBridge() == null) return;
        i.removeExtra("uxgpt_url");
        getBridge().getWebView().postDelayed(() -> getBridge().getWebView().loadUrl("https://uxgpt.vercel.app" + u), 300);
    }
}
