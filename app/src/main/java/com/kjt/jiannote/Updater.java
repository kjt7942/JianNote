package com.kjt.jiannote;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * GitHub Releases 자동 업데이트.
 * - 앱을 켤 때(6시간에 한 번) 최신 릴리스를 확인 → 새 버전이면 APK를 받아 바로 설치 진행
 * - 버전 번호 = GitHub Actions 실행 번호 (태그 build-N, versionCode N)
 * - 구글 플레이에서 설치한 경우에는 플레이 정책상 동작하지 않음
 * - 안드로이드는 다른 앱(브라우저 등)으로 설치된 앱의 첫 자체 업데이트에 '설치' 확인을 한 번 요구하고,
 *   그 뒤부터는 확인 없이 업데이트된다 (Android 12 이상).
 */
public class Updater {
    static final String API = "https://api.github.com/repos/kjt7942/JianNote/releases/latest";
    private static final long INTERVAL = 6 * 60 * 60 * 1000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean busy;

    public static boolean autoEnabled(Context c) {
        return new Prefs(c).b("autoUpdate", true);
    }

    public static void setAuto(Context c, boolean on) {
        new Prefs(c).put("autoUpdate", on);
    }

    /** 구글 플레이에서 설치됐으면 자체 업데이트를 하지 않는다 */
    static boolean fromPlayStore(Context c) {
        try {
            String inst;
            if (Build.VERSION.SDK_INT >= 30) {
                inst = c.getPackageManager().getInstallSourceInfo(c.getPackageName()).getInstallingPackageName();
            } else {
                inst = c.getPackageManager().getInstallerPackageName(c.getPackageName());
            }
            return "com.android.vending".equals(inst);
        } catch (Exception e) {
            return false;
        }
    }

    static long currentVersion(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 앱 시작 시 호출 (자동) */
    public static void autoCheck(Activity a) {
        if (!autoEnabled(a) || fromPlayStore(a)) return;
        Prefs p = new Prefs(a);
        long last = Long.parseLong(p.s("updLast", "0"));
        if (System.currentTimeMillis() - last < INTERVAL) return;
        p.put("updLast", String.valueOf(System.currentTimeMillis()));
        check(a, false);
    }

    /** 수동 확인 (설정 → 업데이트 확인) */
    public static void check(Activity a, boolean manual) {
        if (fromPlayStore(a)) {
            if (manual) Toast.makeText(a, "구글 플레이에서 설치한 앱은 플레이 스토어에서 업데이트됩니다", Toast.LENGTH_LONG).show();
            return;
        }
        if (busy) {
            if (manual) Toast.makeText(a, "업데이트를 받는 중입니다…", Toast.LENGTH_SHORT).show();
            return;
        }
        if (manual) Toast.makeText(a, "새 버전을 확인하는 중…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                JSONObject rel = new JSONObject(get(API));
                String tag = rel.optString("tag_name", "");
                long latest = Long.parseLong(tag.replaceAll("[^0-9]", "").isEmpty() ? "0" : tag.replaceAll("[^0-9]", ""));
                String apkUrl = null, apkName = null;
                JSONArray assets = rel.optJSONArray("assets");
                if (assets != null) for (int i = 0; i < assets.length(); i++) {
                    JSONObject as = assets.getJSONObject(i);
                    if (as.optString("name").endsWith(".apk")) {
                        apkUrl = as.optString("browser_download_url");
                        apkName = as.optString("name");
                    }
                }
                long cur = currentVersion(a);
                if (latest <= cur || apkUrl == null) {
                    if (manual) MAIN.post(() -> Toast.makeText(a, "최신 버전을 사용 중입니다 (" + cur + ")", Toast.LENGTH_SHORT).show());
                    return;
                }
                final String url = apkUrl, name = apkName;
                final String title = rel.optString("name", "새 버전");
                MAIN.post(() -> {
                    if (!a.isFinishing()) download(a, url, name, title);
                });
            } catch (Exception e) {
                if (manual) MAIN.post(() -> Toast.makeText(a, "업데이트 확인 실패: 인터넷 연결을 확인하세요", Toast.LENGTH_LONG).show());
            }
        }, "jiannote-update").start();
    }

    private static String get(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int r;
            while ((r = in.read(b)) > 0) bo.write(b, 0, r);
            return bo.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static void download(Activity a, String url, String name, String title) {
        // 출처를 알 수 없는 앱 설치 허용이 필요
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(a).setTitle(title + " 업데이트")
                    .setMessage("새 버전이 있습니다. 자동 업데이트를 하려면 JianNote에 '앱 설치 허용'을 한 번 켜 주세요.")
                    .setNegativeButton("나중에", null)
                    .setPositiveButton("설정 열기", (d, w) -> a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + a.getPackageName()))))
                    .show();
            return;
        }
        busy = true;
        Toast.makeText(a, title + " 업데이트를 내려받는 중…", Toast.LENGTH_SHORT).show();
        Context app = a.getApplicationContext();
        new Thread(() -> {
            File dir = new File(app.getCacheDir(), "updates");
            dir.mkdirs();
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) f.delete();
            File apk = new File(dir, name);
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(15000);
                c.setReadTimeout(60000);
                // GitHub 은 다른 호스트로 리디렉트하므로 직접 따라간다
                int code = c.getResponseCode();
                int hops = 0;
                while ((code == 301 || code == 302 || code == 307 || code == 308) && hops++ < 5) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    c = (HttpURLConnection) new URL(loc).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(60000);
                    code = c.getResponseCode();
                }
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] b = new byte[1 << 16];
                    int r;
                    while ((r = in.read(b)) > 0) out.write(b, 0, r);
                }
                c.disconnect();
                install(app, apk);
            } catch (Exception e) {
                MAIN.post(() -> Toast.makeText(app, "업데이트 다운로드 실패: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                busy = false;
            }
        }, "jiannote-download").start();
    }

    private static void install(Context c, File apk) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                byte[] b = new byte[1 << 16];
                int r;
                while ((r = in.read(b)) > 0) out.write(b, 0, r);
                s.fsync(out);
            }
            Intent i = new Intent(c, Receiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pend = PendingIntent.getBroadcast(c, id, i, flags);
            s.commit(pend.getIntentSender());
        }
    }

    /** 설치 결과 받기: 사용자 확인이 필요하면 확인 화면을 띄운다 */
    public static class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(confirm);
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    Toast.makeText(c, "업데이트 설치 실패" + (msg != null ? ": " + msg : ""), Toast.LENGTH_LONG).show();
                }
            }
        }
    }
}
