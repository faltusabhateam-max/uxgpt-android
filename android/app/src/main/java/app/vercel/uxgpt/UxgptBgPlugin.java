package app.vercel.uxgpt;

import android.content.Context;
import android.net.Uri;
import androidx.browser.customtabs.CustomTabColorSchemeParams;
import androidx.browser.customtabs.CustomTabsIntent;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Stores the per-device poll key and (re)schedules the background notification poller. */
@CapacitorPlugin(name = "UxgptBg")
public class UxgptBgPlugin extends Plugin {
    @PluginMethod
    public void configure(PluginCall call) {
        String key = call.getString("key");
        String base = call.getString("base", "https://uxgpt.vercel.app");
        if (key == null || key.isEmpty() || base == null || !base.startsWith("https://")) { call.reject("invalid config"); return; }
        Context c = getContext();
        c.getSharedPreferences(PollWorker.PREFS, Context.MODE_PRIVATE).edit().putString("key", key).putString("base", base).apply();
        PollWorker.schedule(c, true);
        JSObject r = new JSObject(); r.put("ok", true); call.resolve(r);
    }

    @PluginMethod
    public void clear(PluginCall call) {
        Context c = getContext();
        c.getSharedPreferences(PollWorker.PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        PollWorker.cancel(c);
        call.resolve();
    }

    /** OAuth providers (Google, X, Apple) refuse embedded WebViews: open them in Chrome Custom Tabs instead.
     *  The flow returns via https://uxgpt.vercel.app/oauth/return -> uxgpt://oauth -> MainActivity. */
    @Override
    public Boolean shouldOverrideLoad(Uri url) {
        String h = url.getHost() == null ? "" : url.getHost();
        boolean oauth = h.equals("accounts.google.com") || h.equals("x.com") || h.equals("twitter.com") || h.endsWith(".twitter.com") || h.endsWith(".x.com") || h.equals("appleid.apple.com");
        if (!oauth) return null;
        try {
            CustomTabsIntent ct = new CustomTabsIntent.Builder()
                .setDefaultColorSchemeParams(new CustomTabColorSchemeParams.Builder().setToolbarColor(0xFF050505).build())
                .setShowTitle(true).build();
            ct.launchUrl(getActivity(), url);
            return true;
        } catch (Exception e) { return null; }
    }

    @PluginMethod
    public void openTab(PluginCall call) {
        String u = call.getString("url");
        if (u == null || !u.startsWith("https://")) { call.reject("invalid url"); return; }
        new CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(getActivity(), Uri.parse(u));
        call.resolve();
    }

    @PluginMethod
    public void runNow(PluginCall call) { PollWorker.runOnce(getContext()); call.resolve(); }
}
