package app.vercel.uxgpt;

import android.content.Intent;
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
        PollWorker.schedule(this, false);
        openFromNotification(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        openFromNotification(intent);
    }

    private void openFromNotification(Intent i) {
        String u = i == null ? null : i.getStringExtra("uxgpt_url");
        if (u == null || !u.startsWith("/") || u.startsWith("//") || getBridge() == null) return;
        i.removeExtra("uxgpt_url");
        getBridge().getWebView().postDelayed(() -> getBridge().getWebView().loadUrl("https://uxgpt.vercel.app" + u), 300);
    }
}
