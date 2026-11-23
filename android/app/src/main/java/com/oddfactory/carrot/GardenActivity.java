package com.oddfactory.carrot;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 텃밭 화면: 설치된 앱 = 당근, 30일 넘게 안 쓴 앱 = 잡초. 팻말에 진짜 앱 아이콘, 아래에 이름.
 * 옆으로 넘기거나 아래 번호를 눌러 페이지 이동, 위에서 앱 검색.
 * 꾹 누르면 One UI처럼 메뉴(선택 / 홈 화면에 추가 / 설치 삭제 / 앱 정보). '선택'은 여러 개 골라 한꺼번에 뽑기.
 * 삭제할 때는 화면 위 캬롯이 그 칸까지 가서 붙잡고, 지워지면 햇님이가 쑥 튀어나온다.
 * 런처가 아니다: One UI 홈·앱스 화면은 그대로이고, 이 화면은 보통 앱 화면이다.
 */
public final class GardenActivity extends Activity {
    static volatile boolean open;
    static final long DAY = 86_400_000L;
    // 햇님이 튀어나오기: 잎만 → 머리 → 반쯤 → 뿅 점프 → 착지 → 알통 자랑
    static final int[] HATNIM_MS = {160, 170, 170, 230, 180, 900};

    static final class App {
        String pkg, label, key, initials, crop; UserHandle user; Bitmap icon; long todayMs, lastUsed; int idleDays; boolean weed, system;
        boolean inBarrow; Bitmap item; // 수레에 실린 동안 칸의 식물은 비어 보임, item = 수레에 쌓일 그림
    }

    float dp; Typeface bold, light;
    GardenView garden; FrameLayout root;
    EditText search; TextView status, pageLabel; LinearLayout selBar, pageRow; TextView selText;
    View menu;
    final Handler h = new Handler(Looper.getMainLooper());
    LauncherApps la;
    final List<App> apps = new ArrayList<>();   // 전체
    final List<App> shown = new ArrayList<>();  // 검색 결과(화면에 보이는 것)
    boolean selecting; final Set<App> picked = new LinkedHashSet<>();
    final List<App> pullQueue = new ArrayList<>(); App pulling; long pullAskedAt;
    boolean multi; final List<App> removedMulti = new ArrayList<>(); // 여러 개 삭제 중(수레)

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        CarrotService.summonIfOff(getSharedPreferences(CarrotService.PREFS, MODE_PRIVATE));
        dp = getResources().getDisplayMetrics().density;
        try { bold = getResources().getFont(R.font.maple_bold); light = getResources().getFont(R.font.maple_light); } catch (Exception ignored) {}
        la = getSystemService(LauncherApps.class);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF8DBF5A);
        garden = new GardenView();
        root.addView(garden, new FrameLayout.LayoutParams(-1, -1));

        // 위: 검색칸 + 상태 한 줄
        LinearLayout top = new LinearLayout(this); top.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (10 * dp); top.setPadding(p, (int) (48 * dp), p, (int) (p * 0.6f)); // 상태 표시줄 아래로 넉넉히 내림
        search = new EditText(this);
        search.setHint("🔍  앱 검색"); search.setSingleLine(true); search.setTypeface(bold); search.setTextSize(16);
        search.setTextColor(0xFF3A2418); search.setHintTextColor(0x993A2418);
        search.setPadding((int) (14 * dp), (int) (8 * dp), (int) (14 * dp), (int) (8 * dp));
        GradientDrawable sb = new GradientDrawable(); sb.setColor(0xF2FFF8EA); sb.setCornerRadius(22 * dp); sb.setStroke((int) (2.5f * dp), 0xFF3A2418);
        search.setBackground(sb);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void afterTextChanged(Editable e) { applyFilter(); }
        });
        top.addView(search, new LinearLayout.LayoutParams(-1, (int) (46 * dp)));
        status = new TextView(this); status.setTypeface(bold); status.setTextSize(13); status.setTextColor(0xFFFFFFFF);
        status.setShadowLayer(3 * dp, 0, 1 * dp, 0xCC3A2418); status.setPadding((int) (6 * dp), (int) (4 * dp), 0, 0);
        status.setVisibility(View.GONE); // 개수 문구는 보여 주지 않음
        top.addView(status);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // 선택 모드 띠(아래): N개 선택 · 설치 삭제 · 취소
        selBar = new LinearLayout(this); selBar.setOrientation(LinearLayout.HORIZONTAL); selBar.setGravity(Gravity.CENTER_VERTICAL);
        selBar.setPadding(p, p, p, p);
        GradientDrawable bb = new GradientDrawable(); bb.setColor(0xF2FFF8EA); bb.setStroke((int) (2 * dp), 0xFF3A2418); bb.setCornerRadius(18 * dp);
        selBar.setBackground(bb);
        selText = new TextView(this); selText.setTypeface(bold); selText.setTextSize(16); selText.setTextColor(0xFF3A2418);
        selBar.addView(selText, new LinearLayout.LayoutParams(0, -2, 1f));
        selBar.addView(pill("설치 삭제", 0xFFE08A6B, v -> startPullQueue()));
        selBar.addView(pill("취소", 0xFF9DA88A, v -> setSelecting(false)));
        selBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); sl.setMargins(p, 0, p, (int) (54 * dp));
        root.addView(selBar, sl);

        // 맨 아래: ◀  3 / 20  ▶  (가운데를 누르면 번호판에서 바로 이동)
        pageRow = new LinearLayout(this); pageRow.setOrientation(LinearLayout.HORIZONTAL); pageRow.setGravity(Gravity.CENTER);
        pageRow.addView(roundBtn("◀", v -> garden.goPage(garden.page - 1, null)));
        pageLabel = new TextView(this); pageLabel.setTypeface(bold); pageLabel.setTextSize(16); pageLabel.setTextColor(0xFF3A2418); pageLabel.setGravity(Gravity.CENTER);
        GradientDrawable lg = new GradientDrawable(); lg.setColor(0xF2FFF8EA); lg.setCornerRadius(20 * dp); lg.setStroke((int) (2 * dp), 0xFF3A2418);
        pageLabel.setBackground(lg); pageLabel.setPadding((int) (18 * dp), 0, (int) (18 * dp), 0);
        pageLabel.setOnClickListener(v -> showPagePicker());
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-2, (int) (40 * dp)); ll.setMargins((int) (10 * dp), 0, (int) (10 * dp), 0);
        pageRow.addView(pageLabel, ll);
        pageRow.addView(roundBtn("▶", v -> garden.goPage(garden.page + 1, null)));
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-2, (int) (44 * dp), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        pl.bottomMargin = (int) (8 * dp);
        root.addView(pageRow, pl);

        setContentView(root);
        la.registerCallback(cb, h);
        load();
    }

    Button pill(String s, int color, View.OnClickListener c) {
        Button x = new Button(this); x.setText(s); x.setAllCaps(false); x.setTypeface(bold); x.setTextColor(0xFFFFFFFF); x.setTextSize(14);
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(14 * dp); g.setStroke((int) (2 * dp), 0xFF3A2418); x.setBackground(g);
        x.setPadding((int) (12 * dp), 0, (int) (12 * dp), 0); x.setMinWidth(0); x.setMinimumWidth(0); x.setMinHeight(0); x.setMinimumHeight(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, (int) (40 * dp)); lp.setMarginStart((int) (6 * dp)); x.setLayoutParams(lp);
        x.setOnClickListener(c); return x;
    }

    @Override protected void onResume() {
        super.onResume(); open = true;
        if (pulling != null && SystemClock.uptimeMillis() - pullAskedAt > 400) h.postDelayed(this::checkPullCancelled, 700);
        garden.resume();
    }
    @Override protected void onPause() { open = false; garden.pause(); super.onPause(); }
    @Override protected void onDestroy() { la.unregisterCallback(cb); super.onDestroy(); }
    @Override public void onBackPressed() {
        if (menu != null) { hideMenu(); return; }
        if (selecting) { setSelecting(false); return; }
        if (search.getText().length() > 0) { search.setText(""); return; }
        super.onBackPressed();
    }

    // ---- 앱 목록·사용 기록 ----
    boolean usageAllowed() {
        AppOpsManager ops = getSystemService(AppOpsManager.class);
        int m = Build.VERSION.SDK_INT >= 29
                ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName())
                : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
        return m == AppOpsManager.MODE_ALLOWED;
    }

    void load() {
        status.setText("텃밭을 살펴보는 중…");
        new Thread(() -> {
            List<App> list = new ArrayList<>();
            PackageManager pm = getPackageManager();
            long now = System.currentTimeMillis();
            Map<String, Long> today = new HashMap<>(), last = new HashMap<>();
            boolean usage = usageAllowed();
            if (usage) {
                UsageStatsManager um = getSystemService(UsageStatsManager.class);
                Calendar c = Calendar.getInstance(); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0);
                Map<String, UsageStats> agg = um.queryAndAggregateUsageStats(c.getTimeInMillis(), now);
                for (Map.Entry<String, UsageStats> e : agg.entrySet()) today.put(e.getKey(), e.getValue().getTotalTimeInForeground());
                for (int iv : new int[]{UsageStatsManager.INTERVAL_DAILY, UsageStatsManager.INTERVAL_WEEKLY, UsageStatsManager.INTERVAL_MONTHLY, UsageStatsManager.INTERVAL_YEARLY}) {
                    List<UsageStats> l = um.queryUsageStats(iv, now - 400 * DAY, now);
                    if (l == null) continue;
                    for (UsageStats u : l) {
                        long t = u.getLastTimeUsed(); if (Build.VERSION.SDK_INT >= 29) t = Math.max(t, u.getLastTimeVisible());
                        Long o = last.get(u.getPackageName()); if (o == null || t > o) last.put(u.getPackageName(), t);
                    }
                }
            }
            int iconPx = (int) (44 * dp);
            for (LauncherActivityInfo a : la.getActivityList(null, Process.myUserHandle())) {
                String pkg = a.getComponentName().getPackageName();
                if (pkg.equals(getPackageName())) continue;
                App x = new App(); x.pkg = pkg; x.user = a.getUser(); x.label = String.valueOf(a.getLabel());
                x.key = x.label.toLowerCase(Locale.ROOT).replace(" ", "");
                x.initials = initials(x.key);
                ApplicationInfo ai = a.getApplicationInfo();
                x.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                x.todayMs = today.containsKey(pkg) ? today.get(pkg) : 0;
                x.lastUsed = last.containsKey(pkg) ? last.get(pkg) : 0;
                long installed = 0; try { PackageInfo pi = pm.getPackageInfo(pkg, 0); installed = pi.firstInstallTime; } catch (Exception ignored) {}
                x.weed = usage && !x.system && now - installed > 30 * DAY && now - x.lastUsed > 30 * DAY;
                x.idleDays = (int) ((now - Math.max(x.lastUsed, installed)) / DAY);
                x.crop = cropOf(pkg);
                try { x.icon = toBitmap(a.getBadgedIcon(0), iconPx); } catch (Exception ignored) {}
                list.add(x);
            }
            list.sort((p, q) -> {
                if (p.weed != q.weed) return p.weed ? 1 : -1;
                if (p.todayMs != q.todayMs) return Long.compare(q.todayMs, p.todayMs);
                return Long.compare(q.lastUsed, p.lastUsed);
            });
            h.post(() -> { apps.clear(); apps.addAll(list); applyFilter(); });
        }, "garden-load").start();
    }

    void applyFilter() {
        String q = search.getText().toString().toLowerCase(Locale.ROOT).replace(" ", "");
        shown.clear();
        boolean jamo = isAllInitials(q); // 'ㅋㅋㅇㅌ' 같은 초성 검색(One UI 앱스 화면처럼)
        for (App a : apps) if (q.isEmpty() || a.key.contains(q) || a.pkg.contains(q) || (jamo && a.initials.contains(q))) shown.add(a);
        int weeds = 0; for (App a : apps) if (a.weed) weeds++;
        status.setText(!usageAllowed() ? "사용 기록을 허용하면 잡초(오래 안 쓴 앱)가 보여요"
                : q.isEmpty() ? "당근 " + (apps.size() - weeds) + " · 잡초 " + weeds : "찾은 앱 " + shown.size());
        garden.page = 0; garden.pageX = 0;
        garden.layoutCells();
    }

    // 작물 섞기: 당근이 절반 남짓(55%), 나머지 여섯 가지가 고르게. 같은 앱은 늘 같은 작물.
    static final String[] OTHERS = {"pumpkin", "corn", "tomato", "cabbage", "potato", "radish"};
    static String cropOf(String pkg) {
        int h = (pkg.hashCode() & 0x7fffffff) % 100;
        return h < 55 ? "carrot" : OTHERS[(h - 55) * OTHERS.length / 45];
    }

    static final char[] CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ".toCharArray();
    /** 한글 음절은 초성으로, 나머지 글자는 그대로. */
    static String initials(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) b.append(ch >= 0xAC00 && ch <= 0xD7A3 ? CHO[(ch - 0xAC00) / 588] : ch);
        return b.toString();
    }
    static boolean isAllInitials(String q) {
        if (q.isEmpty()) return false;
        for (char ch : q.toCharArray()) if (ch < 0x3131 || ch > 0x314E) return false;
        return true;
    }

    static Bitmap toBitmap(Drawable d, int px) {
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b); d.setBounds(0, 0, px, px); d.draw(c); return b;
    }

    // ---- 페이지 표시 ----
    TextView roundBtn(String t, View.OnClickListener c) {
        TextView v = new TextView(this); v.setText(t); v.setTextSize(15); v.setTextColor(0xFF3A2418); v.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(0xF2FFF8EA); g.setStroke((int) (2 * dp), 0xFF3A2418);
        v.setBackground(g); v.setOnClickListener(c);
        v.setLayoutParams(new LinearLayout.LayoutParams((int) (40 * dp), (int) (40 * dp)));
        return v;
    }
    void buildPageButtons() { markPage(); }
    void markPage() {
        if (pageLabel != null) pageLabel.setText((garden.page + 1) + " / " + garden.pages);
        pageRow.setVisibility(garden.pages > 1 ? View.VISIBLE : View.GONE);
    }
    /** 가운데 숫자를 누르면: 번호판(5칸씩)에서 바로 그 쪽으로. */
    void showPagePicker() {
        hideMenu();
        android.widget.GridLayout grid = new android.widget.GridLayout(this); grid.setColumnCount(5);
        int p = (int) (12 * dp); grid.setPadding(p, p, p, p);
        GradientDrawable g = new GradientDrawable(); g.setColor(0xFAFFF8EA); g.setCornerRadius(22 * dp); g.setStroke((int) (2.5f * dp), 0xFF3A2418);
        grid.setBackground(g); grid.setElevation(8 * dp);
        for (int i = 0; i < garden.pages; i++) {
            final int pg = i; boolean on = i == garden.page;
            TextView t = new TextView(this); t.setText(String.valueOf(i + 1)); t.setTypeface(bold); t.setTextSize(15); t.setGravity(Gravity.CENTER);
            GradientDrawable o = new GradientDrawable(); o.setShape(GradientDrawable.OVAL); o.setColor(on ? 0xFFE7A13C : 0x00000000); t.setBackground(o);
            t.setTextColor(on ? 0xFFFFFFFF : 0xFF3A2418);
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams(); lp.width = (int) (44 * dp); lp.height = (int) (44 * dp);
            t.setOnClickListener(v -> { hideMenu(); garden.goPage(pg, null); });
            grid.addView(t, lp);
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this); sv.addView(grid);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = (int) (58 * dp);
        menu = sv; root.addView(sv, lp);
    }

    // ---- 앱 지움 알림 ----
    final LauncherApps.Callback cb = new LauncherApps.Callback() {
        @Override public void onPackageRemoved(String pkg, UserHandle user) {
            App a = find(pkg); if (a == null) return;
            if (a == pulling && multi) { pulling = null; removedMulti.add(a); nextInQueue(); } // 수레에 실린 채로 확정
            else if (a == pulling) { pulling = null; CarrotService.pullDone(); garden.popped(a); }
            else { apps.remove(a); shown.remove(a); garden.layoutCells(); }
        }
        @Override public void onPackageAdded(String pkg, UserHandle user) { load(); }
        @Override public void onPackageChanged(String pkg, UserHandle user) {}
        @Override public void onPackagesAvailable(String[] p, UserHandle u, boolean r) {}
        @Override public void onPackagesUnavailable(String[] p, UserHandle u, boolean r) {}
    };
    App find(String pkg) { for (App a : apps) if (a.pkg.equals(pkg)) return a; return null; }

    void checkPullCancelled() {
        if (pulling == null) return;
        try { getPackageManager().getPackageInfo(pulling.pkg, 0); } catch (Exception gone) { return; }
        App a = pulling; pulling = null;
        if (multi) { a.inBarrow = false; CarrotService.barrowRemove(a.item); garden.invalidate(); } // 취소한 건 수레에서 꺼내 제자리로
        else CarrotService.pullCancel();
        nextInQueue();
    }

    // ---- 누름 ----
    void tapped(App a) {
        if (menu != null) { hideMenu(); return; }
        if (selecting) { toggle(a); return; }
        try { la.startMainActivity(la.getActivityList(a.pkg, a.user).get(0).getComponentName(), a.user, null, null); }
        catch (Exception e) { Toast.makeText(this, "열 수 없는 앱이에요", Toast.LENGTH_SHORT).show(); }
    }

    void toggle(App a) {
        if (!picked.remove(a)) {
            picked.add(a);
            // 캬롯이 수레를 밀고 그 칸으로 가서 식물을 집어 수레에 던져 넣는다
            int i = shown.indexOf(a);
            if (i >= 0) {
                Bitmap src = garden.plantOf(a);
                a.item = src == null ? null : src.copy(src.getConfig(), false);
                float[] p = garden.rootOnScreen(i);
                if (a.item != null) CarrotService.barrowAdd(p[0], p[1], a.item, () -> { if (picked.contains(a)) { a.inBarrow = true; garden.invalidate(); } });
            }
        } else { a.inBarrow = false; if (a.item != null) CarrotService.barrowRemove(a.item); }
        selText.setText(picked.size() + "개 선택");
        garden.invalidate();
    }

    /** 고르기 취소: 수레를 내려놓고 식물을 제자리로. */
    void setSelecting(boolean on) {
        if (!on) { for (App a : picked) a.inBarrow = false; CarrotService.barrowCancel(); }
        endSelectingUi(on);
    }
    void endSelectingUi(boolean on) {
        selecting = on; picked.clear();
        selBar.setVisibility(on ? View.VISIBLE : View.GONE);
        selText.setText("0개 선택");
        garden.invalidate();
    }

    /** One UI처럼 꾹 누르면 뜨는 메뉴. */
    void longPressed(App a, RectF cellOnScreen) {
        if (selecting) { toggle(a); return; }
        hideMenu();
        garden.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (12 * dp); box.setPadding(p, p, p, (int) (6 * dp));
        GradientDrawable g = new GradientDrawable(); g.setColor(0xFAFFF8EA); g.setCornerRadius(22 * dp); g.setStroke((int) (2.5f * dp), 0xFF3A2418);
        box.setBackground(g); box.setElevation(8 * dp);
        TextView title = new TextView(this); title.setText(a.label); title.setTypeface(bold); title.setTextSize(16); title.setTextColor(0xFF3A2418);
        title.setGravity(Gravity.CENTER); title.setPadding(0, 0, 0, (int) (8 * dp)); box.addView(title);
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(menuItem("✓", "선택", () -> { hideMenu(); setSelecting(true); toggle(a); }));
        row.addView(menuItem("⊕", "홈 화면에 추가", () -> { hideMenu(); pinToHome(a); }));
        row.addView(menuItem("⊖", "설치 삭제", () -> { hideMenu(); uninstall(a); }));
        row.addView(menuItem("ⓘ", "앱 정보", () -> { hideMenu(); startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg))); }));
        box.addView(row);
        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int bw = box.getMeasuredWidth(), bh = box.getMeasuredHeight(), W = root.getWidth();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
        lp.leftMargin = (int) Math.max(8 * dp, Math.min(W - bw - 8 * dp, cellOnScreen.centerX() - bw / 2f));
        float y = cellOnScreen.top - bh + cellOnScreen.height() * 0.1f;
        if (y < 90 * dp) y = cellOnScreen.bottom - cellOnScreen.height() * 0.1f;
        lp.topMargin = (int) y;
        menu = box; root.addView(box, lp);
    }
    View menuItem(String icon, String label, Runnable r) {
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setGravity(Gravity.CENTER);
        v.setPadding((int) (8 * dp), (int) (4 * dp), (int) (8 * dp), (int) (4 * dp));
        TextView i = new TextView(this); i.setText(icon); i.setTextSize(20); i.setTextColor(0xFF3A2418); i.setGravity(Gravity.CENTER);
        TextView t = new TextView(this); t.setText(label); t.setTypeface(bold); t.setTextSize(12); t.setTextColor(0xFF3A2418); t.setGravity(Gravity.CENTER);
        v.addView(i); v.addView(t);
        v.setOnClickListener(x -> r.run());
        return v;
    }
    void hideMenu() { if (menu != null) { root.removeView(menu); menu = null; } }

    void pinToHome(App a) {
        ShortcutManager sm = getSystemService(ShortcutManager.class);
        Intent launch = getPackageManager().getLaunchIntentForPackage(a.pkg);
        if (sm == null || !sm.isRequestPinShortcutSupported() || launch == null) { Toast.makeText(this, "홈 화면에 추가할 수 없어요", Toast.LENGTH_SHORT).show(); return; }
        launch.setAction(Intent.ACTION_MAIN);
        ShortcutInfo.Builder sb = new ShortcutInfo.Builder(this, "app:" + a.pkg).setShortLabel(a.label).setIntent(launch);
        if (a.icon != null) sb.setIcon(Icon.createWithBitmap(a.icon));
        sm.requestPinShortcut(sb.build(), null);
    }

    void uninstall(App a) {
        if (a.system) { Toast.makeText(this, "기본 앱이라 뽑을 수 없어요", Toast.LENGTH_SHORT).show(); return; }
        pullQueue.clear(); pullQueue.add(a); nextInQueue();
    }

    void startPullQueue() {
        pullQueue.clear();
        for (App a : picked) if (!a.system) pullQueue.add(a);
        int skipped = picked.size() - pullQueue.size();
        for (App a : picked) if (a.system) { a.inBarrow = false; if (a.item != null) CarrotService.barrowRemove(a.item); }
        multi = true; removedMulti.clear();
        endSelectingUi(false); // 수레는 그대로 두고(삭제 확인 뒤 엎는다) 선택 띠만 닫음
        if (skipped > 0) Toast.makeText(this, "기본 앱 " + skipped + "개는 뽑을 수 없어요", Toast.LENGTH_SHORT).show();
        nextInQueue();
    }

    /** 그 앱이 있는 페이지로 넘어가 → 캬롯이 그 칸까지 가서 붙잡으면 → 삭제 확인 창. */
    void nextInQueue() {
        if (pullQueue.isEmpty()) {
            if (multi) { // 확인 창을 다 거침 → 지운 게 있으면 화면 끝에서 수레를 엎고, 없으면 내려놓기
                multi = false;
                List<App> gone = new ArrayList<>(removedMulti); removedMulti.clear();
                if (gone.isEmpty()) CarrotService.barrowCancel();
                else CarrotService.barrowDump(() -> { apps.removeAll(gone); shown.removeAll(gone); garden.layoutCells(); });
            }
            return;
        }
        App a = pullQueue.remove(0);
        if (multi) { // 이미 수레에 실려 있으니 걸어가지 않고 바로 확인 창
            pulling = a; pullAskedAt = SystemClock.uptimeMillis();
            try { startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg))); }
            catch (Exception e) { pulling = null; a.inBarrow = false; nextInQueue(); }
            return;
        }
        int i = shown.indexOf(a);
        Runnable ask = () -> {
            pulling = a; pullAskedAt = SystemClock.uptimeMillis();
            try { startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg))); }
            catch (Exception e) { pulling = null; CarrotService.pullCancel(); nextInQueue(); }
        };
        Runnable walk = () -> {
            if (i < 0) { ask.run(); return; }
            float[] p = garden.rootOnScreen(i);
            CarrotService.pullAt(p[0], p[1], ask);
        };
        if (i >= 0 && i / garden.perPage != garden.page) garden.goPage(i / garden.perPage, walk); else walk.run();
    }

    // =====================================================================
    /** 텃밭 그림: 풀밭 바탕, 줄마다 이랑, 칸마다 당근/잡초 + 팻말(진짜 아이콘) + 이름. */
    final class GardenView extends View {
        final Paint px = new Paint(), text = new Paint(Paint.ANTI_ALIAS_FLAG), box = new Paint(Paint.ANTI_ALIAS_FLAG);
        Bitmap sky, skyWide, grass, bed, sign, weedBmp; Paint grassPaint;
        final Map<String, Bitmap> crops = new HashMap<>();
        final Paint gray = new Paint();
        final Bitmap[] hatnim = new Bitmap[6];
        final float[] panel = {0.24f, 0.25f, 0.76f, 0.61f};
        final Map<App, Long> yanking = new HashMap<>();
        final List<RectF> cells = new ArrayList<>();
        final List<Float> rowsY = new ArrayList<>();
        int cols = 4, rows = 1; float cellW, cellH, gridTop;
        boolean running;
        final int[] loc = new int[2];

        GardenView() {
            super(GardenActivity.this);
            px.setFilterBitmap(false);
            text.setTypeface(bold); text.setColor(0xFF3A2418); text.setTextAlign(Paint.Align.CENTER);
            sky = asset("garden_wallpaper.jpg");
            skyWide = asset("garden/garden_wide.jpg"); // 펼친 화면(가로가 넓은 화면)용
            weedBmp = asset("weed.png");
            grass = asset("garden/grass.jpg");
            bed = asset("garden/bed.png");
            sign = asset("garden/sign_b.png");
            for (String cr : new String[]{"carrot", "pumpkin", "corn", "tomato", "cabbage", "potato", "radish"}) crops.put(cr, asset("garden/" + cr + "_2.png"));
            gray.setColorFilter(new android.graphics.ColorMatrixColorFilter(new android.graphics.ColorMatrix() {{ setSaturation(0f); }}));
            for (int i = 0; i < hatnim.length; i++) hatnim[i] = asset("garden/hatnim_" + i + ".png");
            try (InputStream in = getAssets().open("garden/signs.json")) {
                org.json.JSONArray pnl = new org.json.JSONObject(new String(in.readAllBytes(), "UTF-8")).getJSONObject("b").getJSONArray("panel");
                for (int i = 0; i < 4; i++) panel[i] = (float) pnl.getDouble(i);
            } catch (Exception ignored) {}
        }

        Bitmap asset(String p) {
            try (InputStream in = getAssets().open(p)) { BitmapFactory.Options o = new BitmapFactory.Options(); o.inScaled = false; return BitmapFactory.decodeStream(in, null, o); }
            catch (Exception e) { return null; }
        }

        // ---- 페이지 ----
        float pageX; int page, pages = 1, perPage = 1;
        float downX, downY, startPageX; boolean dragging, longFired; android.view.VelocityTracker vt;
        android.animation.ValueAnimator snap;
        final Runnable longPress = () -> {
            longFired = true;
            int i = hit(downX, downY);
            if (i >= 0) { RectF r = new RectF(cells.get(i)); r.offset(-pageX, 0); longPressed(shown.get(i), r); }
        };

        int hit(float x, float y) {
            float wx = x + pageX;
            for (int i = 0; i < cells.size() && i < shown.size(); i++) if (cells.get(i).contains(wx, y)) return i;
            return -1;
        }

        /** i번 칸 식물 뿌리의 화면 좌표(캬롯이 붙잡으러 갈 곳). */
        float[] rootOnScreen(int i) {
            getLocationOnScreen(loc);
            RectF r = cells.get(i);
            return new float[]{loc[0] + r.left - page * getWidth() + r.width() * 0.5f, loc[1] + r.top + r.height() * 0.76f};
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            int W = getWidth();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (snap != null) snap.cancel();
                    hideKeyboard();
                    downX = e.getX(); downY = e.getY(); startPageX = pageX; dragging = false; longFired = false;
                    vt = android.view.VelocityTracker.obtain(); vt.addMovement(e);
                    postDelayed(longPress, 450);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (vt != null) vt.addMovement(e);
                    float dx = e.getX() - downX;
                    if (!dragging && !longFired && (Math.abs(dx) > 8 * dp || Math.abs(e.getY() - downY) > 12 * dp)) { dragging = Math.abs(dx) > 8 * dp; removeCallbacks(longPress); }
                    if (dragging) {
                        if (menu != null) hideMenu();
                        float x = startPageX - dx, max = (pages - 1) * W;
                        if (x < 0) x *= 0.35f; else if (x > max) x = max + (x - max) * 0.35f;
                        pageX = x; invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(longPress);
                    if (dragging) {
                        float v = 0; if (vt != null) { vt.addMovement(e); vt.computeCurrentVelocity(1000); v = vt.getXVelocity(); }
                        int to = Math.round(pageX / W);
                        if (Math.abs(v) > 400 * dp) to = v < 0 ? (int) Math.floor(startPageX / W) + 1 : (int) Math.ceil(startPageX / W) - 1;
                        goPage(to, null);
                    } else if (!longFired && e.getActionMasked() == MotionEvent.ACTION_UP) {
                        int i = hit(e.getX(), e.getY());
                        if (i >= 0) tapped(shown.get(i)); else hideMenu();
                    }
                    if (vt != null) { vt.recycle(); vt = null; }
                    dragging = false; return true;
            }
            return true;
        }

        void hideKeyboard() {
            InputMethodManager im = getSystemService(InputMethodManager.class);
            if (im != null) im.hideSoftInputFromWindow(search.getWindowToken(), 0);
            search.clearFocus();
        }

        void goPage(int to, Runnable after) {
            int W = getWidth();
            to = Math.max(0, Math.min(pages - 1, to));
            page = to; markPage();
            if (snap != null) snap.cancel();
            snap = android.animation.ValueAnimator.ofFloat(pageX, to * W);
            snap.setDuration(Math.min(320, (long) (Math.abs(pageX - to * W) / Math.max(1, W) * 260) + 90));
            snap.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
            snap.addUpdateListener(an -> { pageX = (float) an.getAnimatedValue(); invalidate(); });
            snap.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator an) { if (after != null) after.run(); }
            });
            snap.start();
        }

        float skyH() { return (getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels) * 0.36f; }

        void layoutCells() {
            cells.clear(); rowsY.clear();
            float W = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
            float H = getHeight() > 0 ? getHeight() : getResources().getDisplayMetrics().heightPixels;
            cols = 4;                                   // 한 페이지 4×4
            float topUi = 112 * dp, bottomUi = 62 * dp;
            float avail = H - topUi - bottomUi;
            rows = 4;                                   // 한 페이지 4줄 고정
            cellH = avail / rows; cellW = Math.min(W / cols, cellH * 1.1f); // 펼친 화면에서도 칸이 너무 넓어지지 않게
            float gridLeft = (W - cellW * cols) / 2f;
            perPage = rows * cols;
            gridTop = topUi + (avail - rows * cellH) / 2f;
            for (int r = 0; r < rows; r++) rowsY.add(gridTop + r * cellH);
            pages = Math.max(1, (shown.size() + perPage - 1) / perPage);
            for (int i = 0; i < shown.size(); i++) {
                int p = i / perPage, j = i % perPage, r = j / cols, c = j % cols;
                float x = p * W + gridLeft + c * cellW, y = gridTop + r * cellH;
                cells.add(new RectF(x, y, x + cellW, y + cellH));
            }
            if (page > pages - 1) { page = pages - 1; pageX = page * W; }
            buildPageButtons();
            invalidate();
        }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { layoutCells(); }

        // ---- 그리기 ----
        @Override protected void onDraw(Canvas c) {
            int W = getWidth(), H = getHeight();
            // 배경: 텃밭 풍경 한 장을 화면 가득(가운데 맞춰 자름). 땅·하늘을 나누지 않는다.
            Bitmap bg = skyWide != null && W > H * 0.8f ? skyWide : sky; // 화면 비율에 맞는 그림
            if (bg != null) {
                float k = Math.max(W / (float) bg.getWidth(), H / (float) bg.getHeight());
                float bw = bg.getWidth() * k, bh = bg.getHeight() * k;
                c.drawBitmap(bg, null, new RectF((W - bw) / 2f, (H - bh) / 2f, (W + bw) / 2f, (H + bh) / 2f), px);
            } else c.drawColor(0xFFE9C793);
            c.save(); c.translate(-pageX, 0);
            for (int i = 0; i < cells.size() && i < shown.size(); i++) {
                RectF r = cells.get(i);
                if (r.right < pageX || r.left > pageX + W) continue;
                drawCell(c, r, shown.get(i));
            }
            c.restore();
            if (shown.isEmpty() && search.getText().length() > 0) {
                text.setTextSize(16 * getResources().getDisplayMetrics().scaledDensity); text.setColor(0xFF3A2418); text.setStyle(Paint.Style.FILL);
                c.drawText("그런 앱은 텃밭에 없어요", W / 2f, H / 2f, text);
            }
        }

        void drawBed(Canvas c, float ry, int W) {
            float bt = ry + cellH * 0.47f, bh = cellH * 0.30f;
            if (bed == null) { box.setColor(0xFF9A6638); c.drawRoundRect(new RectF(6 * dp, bt, W - 6 * dp, bt + bh), 14 * dp, 14 * dp, box); return; }
            float tileW = bh * bed.getWidth() / bed.getHeight();
            float start = -(pageX % tileW) - tileW;
            for (float x = start; x < W + tileW; x += tileW) c.drawBitmap(bed, null, new RectF(x, bt, x + tileW + 1, bt + bh), px);
        }

        void drawCell(Canvas c, RectF r, App a) {
            Long ys = yanking.get(a);
            long yt = ys == null ? -1 : Math.max(0, SystemClock.uptimeMillis() - ys);
            float base = r.top + r.height() * 0.76f, pcx = r.centerX(); // 식물은 칸 가운데, 크게
            int alpha = 255;
            if (ys != null && yt > hatnimTotal()) alpha = (int) Math.max(0, 255 * (1 - (yt - hatnimTotal()) / 350f)); // 끝나면 칸이 흐려지며 사라짐
            Paint ap = new Paint(px); ap.setAlpha(alpha);
            // 식물: 뽑히는 중이면 햇님이가 튀어나오는 프레임
            Bitmap plant = a.weed ? weedBmp : crops.get(a.crop);
            float ph = r.height() * (a.weed ? 0.34f : 0.7f); // 잡초는 작은 새싹 덤불
            if (ys != null && hatnim[0] != null) {
                Bitmap hb = hatnim[hatnimFrame(yt)];
                float hh = r.height() * 0.92f, hw = hh * hb.getWidth() / hb.getHeight();
                c.drawBitmap(hb, null, new RectF(pcx - hw / 2, base - hh, pcx + hw / 2, base), ap);
            } else if (plant != null && !a.inBarrow) {
                float pw = ph * plant.getWidth() / plant.getHeight();
                float sway = a.weed ? (float) Math.sin(SystemClock.uptimeMillis() / 380.0 + a.pkg.hashCode() % 7) * 4f : 0;
                c.save(); c.rotate(sway, pcx, base);
                c.drawBitmap(plant, null, new RectF(pcx - pw / 2, base - ph, pcx + pw / 2, base), ap);
                c.restore();
            }
            // 팻말: 작게, 식물 오른쪽 앞에 꽂힘(뽑히면 옆으로 쓰러지며 흐려짐)
            float sh = r.height() * 0.52f, sw = sign != null ? sh * sign.getWidth() / sign.getHeight() : sh * 0.8f;
            sw = Math.min(sw, r.width() * 0.62f); sh = sign != null ? sw * sign.getHeight() / sign.getWidth() : sh;
            float sx = r.right - sw - r.width() * 0.02f, sy = base + r.height() * 0.1f - sh; // 크게, 식물 앞을 가려도 됨
            c.save();
            if (ys != null) { float k = Math.min(1f, yt / 500f); c.rotate(70 * k * k, sx + sw * 0.5f, sy + sh); ap.setAlpha((int) (alpha * (1 - 0.6f * k))); }
            if (sign != null) c.drawBitmap(sign, null, new RectF(sx, sy, sx + sw, sy + sh), ap);
            RectF pr = new RectF(sx + sw * panel[0], sy + sh * panel[1], sx + sw * panel[2], sy + sh * panel[3]);
            float is = Math.min(pr.width(), pr.height()) * 0.9f;
            RectF ir = new RectF(pr.centerX() - is / 2, pr.centerY() - is / 2, pr.centerX() + is / 2, pr.centerY() + is / 2);
            if (a.icon != null) {
                if (a.weed) { gray.setAlpha((int) (alpha * 0.75f)); c.drawBitmap(a.icon, null, ir, gray); } // 오래 안 써서 바랜 아이콘
                else c.drawBitmap(a.icon, null, ir, ap);
            }
            if (a.weed && ys == null) cobweb(c, pr);
            c.restore();
            if (a.weed && ys == null) idleBadge(c, sx + sw * 0.86f, sy + sh * 0.12f, a.idleDays);
            if (ys != null) return;
            // 앱 이름(풀밭 위, 흰 테두리 글씨)
            text.setTextSize(11.5f * getResources().getDisplayMetrics().scaledDensity);
            String name = ellipsize(a.label, r.width() * 0.96f);
            float ty0 = r.top + r.height() * 0.95f;
            text.setColor(0xF2FFFFFF); text.setStyle(Paint.Style.STROKE); text.setStrokeWidth(3.5f * dp); c.drawText(name, r.centerX(), ty0, text);
            text.setColor(0xFF3A2418); text.setStyle(Paint.Style.FILL); c.drawText(name, r.centerX(), ty0, text);
            if (selecting) { // 선택 표시(동그라미)
                boolean on = picked.contains(a);
                float cx0 = r.left + r.width() * 0.14f, cy0 = r.top + r.height() * 0.12f, rad = 11 * dp;
                box.setStyle(Paint.Style.FILL); box.setColor(on ? 0xFFE08A6B : 0xCCFFFFFF); c.drawCircle(cx0, cy0, rad, box);
                box.setStyle(Paint.Style.STROKE); box.setStrokeWidth(2 * dp); box.setColor(0xFF3A2418); c.drawCircle(cx0, cy0, rad, box);
                box.setStyle(Paint.Style.FILL);
                if (on) { text.setColor(Color.WHITE); text.setTextSize(13 * getResources().getDisplayMetrics().scaledDensity); c.drawText("✓", cx0, cy0 + 4.5f * dp, text); }
            }
        }

        /** 팻말 아이콘 칸 모서리에 거미줄(오래 방치됨). */
        void cobweb(Canvas c, RectF pr) {
            Paint w = new Paint(Paint.ANTI_ALIAS_FLAG); w.setStyle(Paint.Style.STROKE); w.setStrokeWidth(1.2f * dp); w.setColor(0xCCFFFFFF);
            float ox = pr.left, oy = pr.top, L = pr.width() * 0.55f;
            for (int i = 0; i <= 3; i++) { double a = Math.PI / 2 * i / 3; c.drawLine(ox, oy, ox + (float) Math.cos(a) * L, oy + (float) Math.sin(a) * L, w); }
            for (int k = 1; k <= 3; k++) {
                float rr = L * k / 3.4f; android.graphics.Path p = new android.graphics.Path();
                for (int i = 0; i <= 3; i++) { double a = Math.PI / 2 * i / 3; float x = ox + (float) Math.cos(a) * rr, y = oy + (float) Math.sin(a) * rr; if (i == 0) p.moveTo(x, y); else p.quadTo(ox + (float) Math.cos(a - 0.26) * rr * 0.8f, oy + (float) Math.sin(a - 0.26) * rr * 0.8f, x, y); }
                c.drawPath(p, w);
            }
        }

        /** 안 쓴 날수 배지(예: 45일). */
        void idleBadge(Canvas c, float cx, float cy, int days) {
            String t = days >= 365 ? "1년+" : days + "일";
            text.setTextSize(10.5f * getResources().getDisplayMetrics().scaledDensity);
            float tw = text.measureText(t) + 10 * dp, th = 17 * dp;
            RectF b = new RectF(cx - tw / 2, cy - th / 2, cx + tw / 2, cy + th / 2);
            box.setStyle(Paint.Style.FILL); box.setColor(0xFF7C8A92); c.drawRoundRect(b, th / 2, th / 2, box);
            box.setStyle(Paint.Style.STROKE); box.setStrokeWidth(1.5f * dp); box.setColor(0xFF3A2418); c.drawRoundRect(b, th / 2, th / 2, box); box.setStyle(Paint.Style.FILL);
            text.setColor(Color.WHITE); text.setStyle(Paint.Style.FILL); c.drawText(t, cx, cy + 3.8f * dp, text);
        }

        Bitmap plantOf(App a) { return a.weed ? weedBmp : crops.get(a.crop); }

        long hatnimTotal() { long t = 0; for (int m : HATNIM_MS) t += m; return t; }
        int hatnimFrame(long t) {
            for (int i = 0; i < HATNIM_MS.length; i++) { if (t < HATNIM_MS[i]) return i; t -= HATNIM_MS[i]; }
            return HATNIM_MS.length - 1;
        }

        String ellipsize(String s, float w) {
            if (text.measureText(s) <= w) return s;
            int n = text.breakText(s, true, w - text.measureText("…"), null); return s.substring(0, Math.max(0, n)) + "…";
        }

        /** 지워졌다: 햇님이가 쑥 튀어나와 알통 자랑 → 칸이 흐려지며 사라지고 뒤 칸이 당겨짐. */
        void popped(App a) {
            // 캬롯이 뽑고 물러나는 동안(약 0.25초) 잎이 흔들리다가, 비켜난 자리에서 햇님이가 튀어나온다
            yanking.put(a, SystemClock.uptimeMillis() + 450);
            h.postDelayed(() -> { yanking.remove(a); apps.remove(a); shown.remove(a); layoutCells(); nextInQueue(); }, 450 + hatnimTotal() + 380);
        }

        // 잡초 흔들림·햇님이 연출용 매 프레임 다시 그리기
        final Runnable tick = new Runnable() { @Override public void run() { if (!running) return; invalidate(); postOnAnimation(this); } };
        void resume() { if (running) return; running = true; postOnAnimation(tick); }
        void pause() { running = false; removeCallbacks(tick); }
    }
}
