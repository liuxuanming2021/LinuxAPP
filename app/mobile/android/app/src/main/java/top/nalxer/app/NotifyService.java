package top.nalxer.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 后台消息通知：前台服务里定时拉 /api/chat?since=，有新消息就发系统通知。
 * 只负责「应用不在前台时」的这一条链路，前台交互仍由网页里的逻辑处理。
 */
public class NotifyService extends Service {

    public static final String ACTION_START = "top.nalxer.app.notify.START";
    public static final String ACTION_STOP = "top.nalxer.app.notify.STOP";

    private static final String TAG = "NalxerNotify";
    private static final String CHANNEL_ID = "nalxer_chat";
    private static final int NOTIFY_ID = 4201;
    private static final long POLL_MS = 10000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    private String server = "";
    private String token = "";
    private long lastId = 0L;
    private volatile boolean running = false;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        pollOnce();
                    } catch (Throwable t) {
                        Log.w(TAG, "poll failed: " + t);
                    }
                }
            });
            handler.postDelayed(this, POLL_MS);
        }
    };

    public static void start(Context ctx, String server, String token, long since) {
        Intent it = new Intent(ctx, NotifyService.class);
        it.setAction(ACTION_START);
        it.putExtra("server", server);
        it.putExtra("token", token);
        it.putExtra("since", since);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(it);
        else ctx.startService(it);
    }

    public static void stop(Context ctx) {
        Intent it = new Intent(ctx, NotifyService.class);
        it.setAction(ACTION_STOP);
        ctx.startService(it);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            running = false;
            handler.removeCallbacks(tick);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null) {
            String s = intent.getStringExtra("server");
            String t = intent.getStringExtra("token");
            if (s != null) server = s;
            if (t != null) token = t;
            lastId = Math.max(lastId, intent.getLongExtra("since", lastId));
        }
        createChannel();
        startForeground(NOTIFY_ID, buildIdle());
        if (!running) {
            running = true;
            handler.post(tick);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void pollOnce() throws Exception {
        if (server.isEmpty() || token.isEmpty()) return;
        String url = server + "/api/chat?since=" + lastId;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        int code = c.getResponseCode();
        if (code != 200) return;
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        JSONObject d = new JSONObject(sb.toString());
        JSONArray msgs = d.optJSONArray("messages");
        if (msgs == null) return;
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            long id = m.optLong("id", 0L);
            if (id <= lastId) continue;
            lastId = id;
            String src = m.optString("source", "");
            if ("notice".equals(src)) continue;
            String name = m.optString("name", "");
            String text = m.optString("text", "");
            String ch = m.optString("channel", "");
            String where = "team".equals(ch) ? " · 队伍频道" : ("manage".equals(ch) ? " · 队伍" : " · 公屏");
            post(name + where, text);
        }
        long last = d.optLong("last", 0L);
        if (last > lastId) lastId = last;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "聊天消息", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("应用在后台时收到的新消息");
        nm.createNotificationChannel(ch);
    }

    private Notification buildIdle() {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("Nalxer 正在后台接收消息")
                .setContentText("有新消息会在这里提醒")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build();
    }

    private void post(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setWhen(System.currentTimeMillis());
        nm.notify((int) (System.currentTimeMillis() % 100000) + 1, b.build());
    }
}
