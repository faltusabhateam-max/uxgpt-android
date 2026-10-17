package app.vercel.uxgpt;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/** In-app updates: download the signed APK, verify its SHA-256, hand it to Android's package installer, relaunch. */
@CapacitorPlugin(name = "UxgptUpdater")
public class UxgptUpdaterPlugin extends Plugin {
    private volatile boolean downloading = false;

    private long versionCode() throws Exception {
        android.content.pm.PackageInfo pi = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
        return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
    }

    private boolean canInstall() {
        return Build.VERSION.SDK_INT < 26 || getContext().getPackageManager().canRequestPackageInstalls();
    }

    @PluginMethod
    public void info(PluginCall call) {
        try {
            android.content.pm.PackageInfo pi = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
            JSObject r = new JSObject();
            r.put("versionCode", versionCode()); r.put("versionName", pi.versionName); r.put("canInstall", canInstall()); r.put("updater", 1);
            call.resolve(r);
        } catch (Exception e) { call.reject(e.getMessage()); }
    }

    @PluginMethod
    public void download(final PluginCall call) {
        final String url = call.getString("url");
        final String sha = call.getString("sha256", "");
        if (url == null || !url.startsWith("https://")) { call.reject("invalid url"); return; }
        if (downloading) { call.reject("A download is already running"); return; }
        downloading = true;
        new Thread(() -> {
            File dir = new File(getContext().getCacheDir(), "updates");
            File out = new File(dir, "UXGPT-update.apk");
            try {
                dir.mkdirs();
                for (File f : dir.listFiles()) f.delete();
                HttpURLConnection c = null; String u = url;
                for (int i = 0; i < 6; i++) { // follow redirects (GitHub -> CDN)
                    c = (HttpURLConnection) new URL(u).openConnection();
                    c.setInstanceFollowRedirects(false); c.setConnectTimeout(20000); c.setReadTimeout(30000);
                    c.setRequestProperty("User-Agent", "UXGPTApp-Updater");
                    int code = c.getResponseCode();
                    if (code >= 300 && code < 400) { String loc = c.getHeaderField("Location"); c.disconnect(); if (loc == null || !loc.startsWith("https://")) throw new Exception("Bad redirect"); u = loc; continue; }
                    if (code != 200) throw new Exception("Download failed (HTTP " + code + ")");
                    break;
                }
                long total = c.getContentLengthLong();
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                try (InputStream in = c.getInputStream(); FileOutputStream fo = new FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024]; long got = 0; int n; int lastPct = -1; long lastT = 0;
                    while ((n = in.read(buf)) > 0) {
                        fo.write(buf, 0, n); md.update(buf, 0, n); got += n;
                        int pct = total > 0 ? (int) (got * 100 / total) : -1; long now = System.currentTimeMillis();
                        if (pct != lastPct && now - lastT > 120) { lastPct = pct; lastT = now; JSObject p = new JSObject(); p.put("pct", pct); p.put("received", got); p.put("total", total); notifyListeners("progress", p); }
                    }
                }
                StringBuilder hex = new StringBuilder(); for (byte b : md.digest()) hex.append(String.format("%02x", b));
                if (!sha.isEmpty() && !hex.toString().equalsIgnoreCase(sha)) { out.delete(); throw new Exception("Integrity check failed (SHA-256 mismatch). The download was discarded."); }
                JSObject r = new JSObject(); r.put("path", out.getAbsolutePath()); r.put("sha256", hex.toString()); r.put("verified", !sha.isEmpty());
                call.resolve(r);
            } catch (Exception e) { call.reject(e.getMessage() == null ? "Download failed" : e.getMessage()); }
            finally { downloading = false; }
        }).start();
    }

    @PluginMethod
    public void install(PluginCall call) {
        String path = call.getString("path");
        File f = path == null ? null : new File(path);
        File dir = new File(getContext().getCacheDir(), "updates");
        try {
            if (f == null || !f.exists() || !f.getCanonicalPath().startsWith(dir.getCanonicalPath())) { call.reject("Update file not found"); return; }
            if (!canInstall()) {
                Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getContext().getPackageName()));
                s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(s);
                JSObject r = new JSObject(); r.put("needsPermission", true); call.resolve(r); return;
            }
            Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            JSObject r = new JSObject(); r.put("started", true); call.resolve(r);
        } catch (Exception e) { call.reject(e.getMessage()); }
    }

    /** Relaunch the app (fresh process) so a new native build / web build is used. */
    @PluginMethod
    public void restart(PluginCall call) {
        call.resolve();
        Intent i = getContext().getPackageManager().getLaunchIntentForPackage(getContext().getPackageName());
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        getContext().startActivity(i);
        getBridge().getWebView().postDelayed(() -> Runtime.getRuntime().exit(0), 250);
    }

    /** Apply a web-only update: drop the HTTP cache and reload the app shell. */
    @PluginMethod
    public void reloadWeb(PluginCall call) {
        getBridge().getWebView().post(() -> { getBridge().getWebView().clearCache(true); getBridge().getWebView().loadUrl("https://uxgpt.vercel.app/chat"); });
        call.resolve();
    }
}
