package com.oddfactory.carrot;

import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/** 첫 화면: 권한 켜기(접근성·사용 기록), 캬롯 보이기/숨기기, 크기, 업데이트 확인(현재 버전 표시). */
public final class MainActivity extends Activity {
    Typeface bold, light; float dp;
    TextView status; Button showBtn, hideBtn, everyBtn;
    SharedPreferences prefs;
    com.oddfactory.update.UpdateCheck appUpdates;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        appUpdates = new com.oddfactory.update.UpdateCheck(this, v -> {});
        prefs = getSharedPreferences(CarrotService.PREFS, MODE_PRIVATE);
        try { bold = getResources().getFont(R.font.maple_bold); light = getResources().getFont(R.font.maple_light); } catch (Exception ignored) {}
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (20 * dp); l.setPadding(p, p * 2, p, p); l.setBackgroundColor(0xFFFFF4E3);
        TextView title = text("캬롯 앱 텃밭", 26, true); title.setGravity(Gravity.CENTER); l.addView(title);
        TextView sub = text("홈 화면 앱들 사이를 캬롯이 돌보고,\n지우는 앱은 잡초처럼 쑥 뽑아요.", 14, false); sub.setGravity(Gravity.CENTER); l.addView(sub);

        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        showBtn = button("시작", 0xFF6DB36A, v -> start());
        hideBtn = button("종료", 0xFFE08A6B, v -> stop());
        for (Button bt : new Button[]{showBtn, hideBtn}) {
            bt.setTextSize(22);
            LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, (int) (72 * dp), 1f);
            half.setMarginEnd(bt == showBtn ? (int) (10 * dp) : 0);
            row.addView(bt, half);
        }
        LinearLayout.LayoutParams big = new LinearLayout.LayoutParams(-1, -2); big.topMargin = (int) (20 * dp);
        l.addView(row, big);

        status = text("", 14, false); status.setPadding(0, (int) (14 * dp), 0, (int) (6 * dp)); l.addView(status);
        l.addView(button("① 접근성에서 '캬롯 앱 텃밭' 켜기", 0xFFE7A13C, v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        TextView restricted = text("①에서 회색으로 막혀 있으면: 아래 버튼 → 오른쪽 위 ⋮ → '제한된 설정 허용' 후 다시 ①", 13, false);
        restricted.setPadding(0, (int) (8 * dp), 0, 0); l.addView(restricted);
        l.addView(button("앱 정보 열기(제한된 설정 허용)", 0xFFB9A27A, v -> startActivity(
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())))));
        l.addView(button("② 사용 기록 접근 허용(오래 안 쓴 앱 = 잡초)", 0xFFE7A13C, v -> openUsageAccess()));

        everyBtn = button("", 0xFF9DB08A, v -> {
            prefs.edit().putBoolean("everywhere", !CarrotService.everywhere(prefs)).apply();
            CarrotService.applySettings(); refresh();
        });
        l.addView(everyBtn);
        l.addView(button("텃밭 배경화면으로 바꾸기(앱스 화면도 텃밭)", 0xFF8BB86A, v -> askWallpaper()));

        final int MIN = 60, MAX = 160;
        int cur = Math.max(MIN, Math.min(MAX, prefs.getInt("size", 100)));
        TextView sz = text("캬롯 크기: " + cur + "%", 15, true); sz.setPadding(0, (int) (16 * dp), 0, 0); l.addView(sz);
        SeekBar seek = new SeekBar(this); seek.setMax(MAX - MIN); seek.setProgress(cur - MIN);
        seek.setPadding((int) (8 * dp), (int) (10 * dp), (int) (8 * dp), (int) (10 * dp));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) { sz.setText("캬롯 크기: " + (v + MIN) + "%"); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { prefs.edit().putInt("size", s.getProgress() + MIN).apply(); CarrotService.applySettings(); }
        });
        l.addView(seek);

        TextView help = text("홈 화면에서는 앱 아이콘 사이를 돌아다녀요. 앱을 길게 눌러 '설치 삭제'를 고르면 그 앱 앞에서 잡초를 붙잡고, 실제로 지워지면 쑥 뽑아요. "
                + "'선택'으로 여러 앱을 고르면 하나씩 뽑아 수레에 싣고, 지우면 수레째 밀고 나가요.", 13, false);
        help.setPadding(0, (int) (12 * dp), 0, 0); l.addView(help);
        l.addView(button("업데이트 확인 (현재 " + BuildConfig.VERSION_NAME + ")", 0xFF8C9AC9, v -> appUpdates.checkNow()));
        TextView ver = text("현재 버전 " + BuildConfig.VERSION_NAME + "\n화면 내용은 저장하거나 밖으로 보내지 않아요.\n글꼴: 메이플스토리 서체(넥슨) 사용", 12, false);
        ver.setTextColor(0xFF7A6250); ver.setPadding(0, (int) (16 * dp), 0, 0); l.addView(ver);
        ScrollView sv = new ScrollView(this); sv.addView(l); setContentView(sv);
    }

    TextView text(String s, int sp, boolean b) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTypeface(b ? bold : light); t.setTextColor(0xFF3A2418); return t; }
    Button button(String s, int color, View.OnClickListener c) {
        Button x = new Button(this); x.setText(s); x.setAllCaps(false); x.setTypeface(bold); x.setTextColor(0xFFFFFFFF); x.setTextSize(15);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(color); bg.setCornerRadius(18 * dp); bg.setStroke((int) (2.5f * dp), 0xFF3A2418); x.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = (int) (8 * dp); x.setLayoutParams(lp);
        x.setOnClickListener(c); return x;
    }

    boolean usageAllowed() {
        AppOpsManager ops = getSystemService(AppOpsManager.class);
        int m = Build.VERSION.SDK_INT >= 29
                ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName())
                : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
        return m == AppOpsManager.MODE_ALLOWED;
    }

    void openUsageAccess() {
        Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:" + getPackageName()));
        try { startActivity(i); } catch (Exception e) { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
    }

    /** 홈 화면 배경을 텃밭 그림으로. 앱스(앱 서랍) 화면은 이 배경을 비춰 보여 준다. 확인 창에서 고를 때만 바꾼다. */
    void askWallpaper() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("텃밭 배경화면")
                .setMessage("홈 화면 배경을 캬롯 텃밭 그림으로 바꿀까요?
앱스 화면에서도 텃밭이 비쳐 보여요.
(지금 배경 사진은 갤러리에서 다시 고를 수 있어요)")
                .setPositiveButton("바꾸기", (d, w) -> new Thread(() -> {
                    boolean ok;
                    try (java.io.InputStream in = getAssets().open("garden_wallpaper.jpg")) {
                        android.app.WallpaperManager.getInstance(this).setStream(in, null, true, android.app.WallpaperManager.FLAG_SYSTEM);
                        ok = true;
                    } catch (Exception e) { ok = false; }
                    boolean done = ok;
                    runOnUiThread(() -> Toast.makeText(this, done ? "텃밭 배경화면으로 바꿨어요" : "배경화면을 바꾸지 못했어요", Toast.LENGTH_SHORT).show());
                }).start())
                .setNegativeButton("그대로 두기", null)
                .show();
    }

    void start() {
        if (CarrotService.current == null) {
            prefs.edit().putBoolean("shown", true).apply(); // 접근성을 켜면 바로 솟아오른다
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "'캬롯 앱 텃밭'을 켜 주세요", Toast.LENGTH_LONG).show();
            return;
        }
        CarrotService.summon();
        // 홈 화면으로 나가서 거기서 땅에서 솟아오르는 모습을 보게 한다
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    void stop() {
        CarrotService.dismiss(this::refresh);
        if (CarrotService.current == null) prefs.edit().putBoolean("shown", false).apply();
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        appUpdates.resume();
        CarrotService.applySettings();
        refresh();
        getWindow().getDecorView().postDelayed(this::refresh, 400);
    }

    void refresh() {
        boolean a11y = CarrotService.current != null, usage = usageAllowed(), on = CarrotService.shown(prefs);
        status.setText("접근성(홈 화면 위 캐릭터): " + (a11y ? "켜짐 ✔" : "꺼짐 — 아래 ①")
                + "\n사용 기록(잡초 표시): " + (usage ? "허용 ✔" : "꺼짐 — 아래 ②")
                + "\n캬롯: " + (a11y && on ? "나와 있어요" : on ? "접근성을 켜면 나와요" : "쉬는 중"));
        showBtn.setAlpha(on ? 0.45f : 1f); hideBtn.setAlpha(on ? 1f : 0.45f);
        everyBtn.setText("다른 앱 위에서도 보이기: " + (CarrotService.everywhere(prefs) ? "켬" : "끔(홈 화면만)"));
    }

    @Override protected void onPause() { appUpdates.pause(); super.onPause(); }
    @Override protected void onDestroy() { appUpdates.destroy(); super.onDestroy(); }
    @Override protected void onActivityResult(int request, int result, Intent data) { super.onActivityResult(request, result, data); appUpdates.onActivityResult(request); }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (focused) appUpdates.onWindowFocus(); }
}
