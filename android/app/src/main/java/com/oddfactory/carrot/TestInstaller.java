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
                PackageInstaller.Session s = pi.openSession(id);
                // 스트림은 commit 전에 닫는다(commit 뒤에 닫으면 예외 → 다음 설치가 바로 시작돼 확인 창이 겹쳤음)
                try (InputStream in = a.getAssets().open("testapks/t" + n + ".apk");
                     OutputStream out = s.openWrite("t" + n + ".apk", 0, -1)) {
                    byte[] buf = new byte[16 * 1024]; int c;
                    while ((c = in.read(buf)) > 0) out.write(buf, 0, c);
                    s.fsync(out);
                }
                int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                s.commit(PendingIntent.getBroadcast(a, 1000 + n, new Intent(action).setPackage(a.getPackageName()), fl).getIntentSender());
                try { s.close(); } catch (Exception ignored) {}
            } catch (Exception e) {
                a.runOnUiThread(() -> { toast("테스트 잡초 " + n + " 설치 준비 실패: " + e.getMessage()); installNext(); });
            }
        }, "test-install").start();
    }

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent r) {
            int st = r.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = Build.VERSION.SDK_INT >= 33 ? r.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class) : r.getParcelableExtra(Intent.EXTRA_INTENT);
                // 앞 설치 확인 창이 아직 닫히는 중이면 Android가 막는다 → 우리 화면이 다시 앞에 오면 띄운다
                if (confirm != null) { pending = confirm; onFocus(); }
                return;
            }
            if (st == PackageInstaller.STATUS_SUCCESS) ok++;
            else if (st != PackageInstaller.STATUS_FAILURE_ABORTED) toast("테스트 잡초 설치 실패: " + r.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            // 다음 것은 확인 창이 완전히 닫힌 뒤에
            a.getWindow().getDecorView().postDelayed(TestInstaller.this::installNext, 600);
        }
    };

    private Intent pending;
    /** 우리 화면이 앞에 있을 때만 확인 창을 띄운다(MainActivity.onWindowFocusChanged에서도 부름). */
    void onFocus() {
        if (pending == null || !a.hasWindowFocus()) return;
        Intent i = pending; pending = null;
        try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); a.startActivity(i); } catch (Exception e) { toast("설치 확인 창을 열 수 없어요"); installNext(); }
    }

    private void done() {
        toast("테스트 잡초 " + ok + "개 설치됨 — 텃밭에서 뽑아 보세요");
        if (registered) { try { a.unregisterReceiver(rx); } catch (Exception ignored) {} registered = false; }
    }

    void destroy() { if (registered) { try { a.unregisterReceiver(rx); } catch (Exception ignored) {} registered = false; } }

    private void toast(String t) { Toast.makeText(a, t, Toast.LENGTH_LONG).show(); }
}
