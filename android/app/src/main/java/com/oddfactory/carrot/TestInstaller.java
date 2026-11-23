package com.oddfactory.carrot;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;

/** 테스트용 빈 앱(assets/testapks/t1~t6.apk, '테스트 잡초 N')을 하나씩 설치한다. 진짜 앱을 지우지 않고 텃밭 삭제를 시험하려는 것.
 *  설치마다 Android 확인 창이 뜨고 사용자가 '설치'를 눌러야 한다. */
final class TestInstaller {
    static final int COUNT = 6;
    private final Activity a;
    private final String action;
    private int next = 1, ok = 0;
    private boolean registered;

    TestInstaller(Activity a) { this.a = a; action = a.getPackageName() + ".TEST_INSTALL"; }

    void start() {
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            toast("'캬롯 앱 텃밭'의 앱 설치 허용을 켠 뒤 다시 눌러 주세요.");
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            return;
        }
        if (!registered) {
            if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(rx, new IntentFilter(action), Context.RECEIVER_NOT_EXPORTED);
            else a.registerReceiver(rx, new IntentFilter(action), a.getPackageName() + ".UPDATE_STATUS", null);
            registered = true;
        }
        next = 1; ok = 0;
        installNext();
    }

    private void installNext() {
        if (next > COUNT) { done(); return; }
        int n = next++;
        new Thread(() -> {
            try {
                PackageInstaller pi = a.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                int id = pi.createSession(p);
                try (PackageInstaller.Session s = pi.openSession(id);
                     InputStream in = a.getAssets().open("testapks/t" + n + ".apk");
                     OutputStream out = s.openWrite("t" + n + ".apk", 0, -1)) {
                    byte[] buf = new byte[16 * 1024]; int c;
                    while ((c = in.read(buf)) > 0) out.write(buf, 0, c);
                    s.fsync(out);
                    out.close();
                    int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    s.commit(PendingIntent.getBroadcast(a, 1000 + n, new Intent(action).setPackage(a.getPackageName()), fl).getIntentSender());
                }
            } catch (Exception e) {
                a.runOnUiThread(() -> { toast("테스트 잡초 " + n + " 설치 준비 실패"); installNext(); });
            }
        }, "test-install").start();
    }

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent r) {
            int st = r.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = Build.VERSION.SDK_INT >= 33 ? r.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class) : r.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) { confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); a.startActivity(confirm); }
                return;
            }
            if (st == PackageInstaller.STATUS_SUCCESS) ok++;
            installNext();
        }
    };

    private void done() {
        toast("테스트 잡초 " + ok + "개 설치됨 — 텃밭에서 뽑아 보세요");
        if (registered) { try { a.unregisterReceiver(rx); } catch (Exception ignored) {} registered = false; }
    }

    void destroy() { if (registered) { try { a.unregisterReceiver(rx); } catch (Exception ignored) {} registered = false; } }

    private void toast(String t) { Toast.makeText(a, t, Toast.LENGTH_LONG).show(); }
}
