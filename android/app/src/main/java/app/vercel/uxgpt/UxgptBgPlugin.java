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
            // Always open in a real browser tab (never let the X / Google apps intercept the OAuth page).
            String pkg = androidx.browser.customtabs.CustomTabsClient.getPackageName(getContext(), null);
            if (pkg != null) ct.intent.setPackage(pkg);
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

    /** Save a generated chart image / video to the phone gallery (Pictures/UXGPT or Movies/UXGPT).
     *  The file arrives in base64 chunks (append) so large videos never cross the bridge in one piece. */
    @PluginMethod
    public void mediaChunk(PluginCall call) {
        String id = call.getString("id", ""); String data = call.getString("data", "");
        if (id == null || !id.matches("[A-Za-z0-9_-]{4,40}")) { call.reject("invalid id"); return; }
        java.io.File tmp = new java.io.File(getContext().getCacheDir(), "media_" + id);
        try {
            if (data != null && !data.isEmpty()) {
                byte[] bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT);
                try (java.io.FileOutputStream o = new java.io.FileOutputStream(tmp, Boolean.TRUE.equals(call.getBoolean("append", true)))) { o.write(bytes); }
            }
            if (!Boolean.TRUE.equals(call.getBoolean("done", false))) { call.resolve(); return; }
            String mime = call.getString("mime", "image/png"); String name = call.getString("name", "UXGPT-" + System.currentTimeMillis());
            if (mime == null || !(mime.equals("image/png") || mime.equals("video/mp4") || mime.equals("video/webm"))) { call.reject("unsupported type"); return; }
            name = name.replaceAll("[^A-Za-z0-9._-]", "_");
            boolean video = mime.startsWith("video/");
            android.net.Uri out;
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime);
                cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, (video ? android.os.Environment.DIRECTORY_MOVIES : android.os.Environment.DIRECTORY_PICTURES) + "/UXGPT");
                cv.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
                android.net.Uri col = video ? android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY) : android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);
                android.content.ContentResolver cr = getContext().getContentResolver();
                out = cr.insert(col, cv);
                if (out == null) throw new Exception("MediaStore insert failed");
                try (java.io.OutputStream os = cr.openOutputStream(out); java.io.FileInputStream in = new java.io.FileInputStream(tmp)) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) os.write(b, 0, n); }
                cv.clear(); cv.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0); cr.update(out, cv, null, null);
            } else {
                java.io.File dir = new java.io.File(getContext().getExternalFilesDir(video ? android.os.Environment.DIRECTORY_MOVIES : android.os.Environment.DIRECTORY_PICTURES), "UXGPT");
                dir.mkdirs(); java.io.File f = new java.io.File(dir, name);
                try (java.io.FileOutputStream os = new java.io.FileOutputStream(f); java.io.FileInputStream in = new java.io.FileInputStream(tmp)) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) os.write(b, 0, n); }
                android.media.MediaScannerConnection.scanFile(getContext(), new String[]{ f.getAbsolutePath() }, new String[]{ mime }, null);
                out = android.net.Uri.fromFile(f);
            }
            JSObject r = new JSObject(); r.put("uri", out.toString()); r.put("path", tmp.getAbsolutePath()); call.resolve(r);
        } catch (Exception e) { call.reject("Couldn't save: " + e.getMessage()); }
    }

    /** Open a saved chart image / video in the gallery or player (used when a "download complete" notification is tapped). */
    @PluginMethod
    public void openMedia(PluginCall call) {
        String uri = call.getString("uri"); String path = call.getString("path"); String mime = call.getString("mime", "image/png");
        if (mime == null || !(mime.startsWith("image/") || mime.startsWith("video/"))) mime = "image/*";
        try {
            android.net.Uri u = null;
            if (uri != null && uri.startsWith("content://")) u = android.net.Uri.parse(uri);
            else if (path != null && !path.isEmpty()) {
                java.io.File f = new java.io.File(path);
                if (!f.getCanonicalPath().startsWith(getContext().getCacheDir().getCanonicalPath()) || !f.exists()) { call.reject("file not found"); return; }
                u = androidx.core.content.FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", f);
            }
            if (u == null) { call.reject("nothing to open"); return; }
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            i.setDataAndType(u, mime);
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            try { getActivity().startActivity(i); } catch (android.content.ActivityNotFoundException nf) { getActivity().startActivity(android.content.Intent.createChooser(i, mime.startsWith("video/") ? "Play video" : "Open image").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); }
            call.resolve();
        } catch (Exception e) { call.reject("Couldn't open: " + e.getMessage()); }
    }

    @PluginMethod
    public void runNow(PluginCall call) { PollWorker.runOnce(getContext()); call.resolve(); }
}
