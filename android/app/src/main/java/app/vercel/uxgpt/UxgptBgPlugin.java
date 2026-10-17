package app.vercel.uxgpt;

import android.content.Context;
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

    @PluginMethod
    public void runNow(PluginCall call) { PollWorker.runOnce(getContext()); call.resolve(); }
}
