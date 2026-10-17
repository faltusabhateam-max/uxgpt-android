package app.vercel.uxgpt;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Every ~15 min (WorkManager minimum) polls https://uxgpt.vercel.app/api/notify/poll and shows local notifications. No Firebase. */
public class PollWorker extends Worker {
    static final String PREFS = "uxgpt_bg";
    static final String WORK = "uxgpt_poll";
    static final String CHANNEL = "uxgpt_default";

    public PollWorker(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }

    static Constraints net() { return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(); }

    static void schedule(Context c, boolean replace) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp.getString("key", null) == null) return;
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(PollWorker.class, 15, TimeUnit.MINUTES).setConstraints(net()).build();
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(WORK, replace ? ExistingPeriodicWorkPolicy.UPDATE : ExistingPeriodicWorkPolicy.KEEP, req);
    }

    static void runOnce(Context c) { WorkManager.getInstance(c).enqueue(new OneTimeWorkRequest.Builder(PollWorker.class).setConstraints(net()).build()); }

    static void cancel(Context c) { WorkManager.getInstance(c).cancelUniqueWork(WORK); }

    @NonNull
    @Override
    public Result doWork() {
        Context c = getApplicationContext();
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = sp.getString("key", null), base = sp.getString("base", "https://uxgpt.vercel.app");
        if (key == null) return Result.success();
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(base + "/api/notify/poll").openConnection();
            con.setConnectTimeout(15000); con.setReadTimeout(25000);
            con.setRequestProperty("X-Device-Key", key);
            con.setRequestProperty("User-Agent", "UXGPTApp/1.0 (Android background)");
            int code = con.getResponseCode();
            if (code == 401) { sp.edit().remove("key").apply(); cancel(c); return Result.success(); }
            if (code != 200) return Result.retry();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), "UTF-8"))) { String l; while ((l = br.readLine()) != null) sb.append(l); }
            JSONArray msgs = new JSONObject(sb.toString()).optJSONArray("messages");
            if (msgs != null) for (int i = 0; i < msgs.length(); i++) {
                JSONObject m = msgs.getJSONObject(i);
                show(c, m.optString("id", String.valueOf(i)).hashCode(), m.optString("title", "UXGPT"), m.optString("body", ""), m.optString("url", "/chat"));
            }
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        } finally { if (con != null) con.disconnect(); }
    }

    static void show(Context c, int id, String title, String body, String url) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "UXGPT alerts", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Trade confirmations, price alerts and quota resets");
                nm.createNotificationChannel(ch);
            }
        }
        Intent open = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        open.putExtra("uxgpt_url", url);
        PendingIntent pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_uxgpt).setContentTitle(title).setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(pi);
        try { NotificationManagerCompat.from(c).notify(id, b.build()); } catch (SecurityException ignored) { /* permission not granted */ }
    }
}
