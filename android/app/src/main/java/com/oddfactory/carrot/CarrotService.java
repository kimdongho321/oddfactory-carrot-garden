package com.oddfactory.carrot;

import android.accessibilityservice.AccessibilityService;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.graphics.Typeface;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 홈 화면(One UI 홈 등 기본 런처) 위에 캬롯을 띄운다. 런처를 대신하지 않는다.
 * 접근성으로 읽는 것: 홈 화면 아이콘의 이름·위치, 길게 누른 앱, '설치 삭제' 누름, 선택 모드의 체크 상태.
 * 앱이 실제로 지워졌는지는 LauncherApps 알림으로 받는다. 화면 내용은 저장·전송하지 않는다(디버그 기록은 기기 안, 최근 일부만).
 */
public final class CarrotService extends AccessibilityService {
    static final String PREFS = "carrot";
    static CarrotService current;

    WindowManager wm;
    Stage stage;
    View grip, menu;              // 캐릭터 몸 위치에만 있는 터치 창, 두 번 톡 메뉴
    WindowManager.LayoutParams gripLp;
    SharedPreferences prefs;
    final Handler h = new Handler(Looper.getMainLooper());
    String launcher = "";
    LauncherApps la;
    final Map<String, String> labelOfPkg = new HashMap<>();
    String longLabel; long longAt;
    Set<String> lastSelected = new HashSet<>();
    boolean selecting;
    long lastLog;
    // 페이지 넘김: One UI는 넘길 때 scrollX(페이지 폭 단위)를 알려 준다 → 페이지마다 아이콘 자리를 기억해 두고 바로 꺼내 쓴다
    final Map<String, Map<String, Rect>> pageCache = new HashMap<>();
    String screenKey = "home"; int curPage = -1; long lastScrollAt; float pageW = 0; int lastSettledX = Integer.MIN_VALUE;
    Map<String, Rect> lastIcons = new LinkedHashMap<>();
    int scrollLogLeft = 400;
    String lastSig = "";
    boolean hotseatSeen;

    static boolean shown(SharedPreferences p) { return p.getBoolean("shown", false); }
    static boolean everywhere(SharedPreferences p) { return true; } // 다른 앱을 켜도 캬롯은 남아 있는다(사용자 지시 2026-11-24)
    static float scaleOf(SharedPreferences p) { return p.getInt("size", 100) / 100f; }

    @Override protected void onServiceConnected() {
        current = this;
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        wm = getSystemService(WindowManager.class);
        launcher = homePackage();
        la = getSystemService(LauncherApps.class);
        refreshApps();
        la.registerCallback(appsCb, h);
        try { stage = new Stage(this, new Sprites(this)); } catch (Exception e) { log("sprites fail " + e); return; }
        stage.setScale(scaleOf(prefs));
        if (shown(prefs)) stage.entering = true; // 켜진 채로 다시 연결되면(재부팅 등) 땅에서 솟아오르며 등장
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= 30) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setTitle("CarrotStage");
        stage.setVisibility(android.view.View.INVISIBLE);
        wm.addView(stage, lp);
        // 터치 창: 캐릭터 몸 크기만큼만 화면을 받고, 나머지는 아래 앱으로 그대로 간다
        grip = new View(this);
        grip.setOnTouchListener(this::onGripTouch);
        gripLp = new WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        gripLp.gravity = Gravity.TOP | Gravity.START;
        gripLp.setTitle("CarrotGrip");
        grip.setVisibility(View.GONE);
        wm.addView(grip, gripLp);
        stage.listener = this::placeGrip;
        refreshStale();
        h.post(poll);
        log("connected launcher=" + launcher);
    }

    String homePackage() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo r = getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        return r != null && r.activityInfo != null ? r.activityInfo.packageName : "com.sec.android.app.launcher";
    }

    // ---- 설치된 앱: 라벨 ↔ 패키지 ----
    void refreshApps() {
        labelOfPkg.clear();
        for (UserHandle u : la.getProfiles()) {
            for (LauncherActivityInfo a : la.getActivityList(null, u)) {
                String l = String.valueOf(a.getLabel());
                labelOfPkg.put(a.getComponentName().getPackageName(), l);
            }
        }
    }

    final LauncherApps.Callback appsCb = new LauncherApps.Callback() {
        @Override public void onPackageRemoved(String pkg, UserHandle user) {
            String label = labelOfPkg.get(pkg);
            log("removed " + pkg + " / " + label);
            if (stage != null) stage.removed(label);
            refreshApps();
        }
        @Override public void onPackageAdded(String pkg, UserHandle user) { refreshApps(); }
        @Override public void onPackageChanged(String pkg, UserHandle user) { refreshApps(); }
        @Override public void onPackagesAvailable(String[] p, UserHandle u, boolean r) { refreshApps(); }
        @Override public void onPackagesUnavailable(String[] p, UserHandle u, boolean r) {}
    };

    // ---- 오래 안 쓴 앱(잡초) ----
    boolean usageAllowed() {
        AppOpsManager ops = getSystemService(AppOpsManager.class);
        int m = Build.VERSION.SDK_INT >= 29
                ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName())
                : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
        return m == AppOpsManager.MODE_ALLOWED;
    }

    void refreshStale() {
        h.removeCallbacks(staleTimer);
        h.postDelayed(staleTimer, 30 * 60_000L);
        if (stage == null || !usageAllowed()) return;
        long now = System.currentTimeMillis(), day = 86_400_000L, limit = 30 * day;
        UsageStatsManager um = getSystemService(UsageStatsManager.class);
        Map<String, Long> lastUse = new HashMap<>();
        // 일·주·월 묶음까지 모두 훑어 앱별 가장 최근 사용 시각(앞 화면에 보였던 시각 포함)을 구한다
        for (int iv : new int[]{UsageStatsManager.INTERVAL_DAILY, UsageStatsManager.INTERVAL_WEEKLY,
                UsageStatsManager.INTERVAL_MONTHLY, UsageStatsManager.INTERVAL_YEARLY}) {
            List<UsageStats> list = um.queryUsageStats(iv, now - 400 * day, now);
            if (list == null) continue;
            for (UsageStats u : list) {
                long t = u.getLastTimeUsed();
                if (Build.VERSION.SDK_INT >= 29) t = Math.max(t, u.getLastTimeVisible());
                if (u.getTotalTimeInForeground() <= 0 && t <= 0) continue;
                Long o = lastUse.get(u.getPackageName());
                if (o == null || t > o) lastUse.put(u.getPackageName(), t);
            }
        }
        Set<String> stale = new HashSet<>();
        PackageManager pm = getPackageManager();
        for (Map.Entry<String, String> e : labelOfPkg.entrySet()) {
            String pkg = e.getKey();
            if (pkg.equals(getPackageName()) || pkg.equals(launcher)) continue;
            try {
                PackageInfo pi = pm.getPackageInfo(pkg, 0);
                if ((pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue; // 지울 수 없는 기본 앱
                if (now - pi.firstInstallTime < limit) continue;
                Long last = lastUse.get(pkg);
                if (last == null || now - last > limit) stale.add(e.getValue());
            } catch (Exception ignored) {}
        }
        stage.setStale(stale);
        log("stale " + stale.size() + " / apps " + labelOfPkg.size() + " / used " + lastUse.size() + " " + stale);
    }
    final Runnable staleTimer = this::refreshStale;

    // ---- 화면 읽기 ----
    final Runnable poll = new Runnable() {
        @Override public void run() {
            scan();
            h.postDelayed(this, stage != null && stage.shownOnHome ? 700 : 1500);
        }
    };
    final Runnable scanSoon = this::scan;

    float pageWidth() {
        if (pageW > 0) return pageW;
        return getResources().getDisplayMetrics().widthPixels * 0.9535f; // 첫 넘김 전 추정(넘겨 보면 실제 값을 배운다)
    }

    void onPageScroll(int x, int dx) {
        long now = SystemClock.uptimeMillis();
        float pw = pageWidth();
        boolean start = now - lastScrollAt > 450;
        lastScrollAt = now;
        if (start && dx != 0) {
            int from = Math.round((x - dx) / pw);
            if (curPage != from && !lastIcons.isEmpty()) pageCache.put(screenKey + ":" + from, new LinkedHashMap<>(lastIcons));
            int to = dx > 0 ? (int) Math.ceil(x / pw - 0.02f) : (int) Math.floor(x / pw + 0.02f);
            if (to == from) to = from + (dx > 0 ? 1 : -1);
            curPage = to;
            Map<String, Rect> next = pageCache.get(screenKey + ":" + to);
            // 다음 페이지를 본 적 있으면 바로 그 잡초를 옆에서 밀어 넣고, 처음이면 일단 비운다(멈추면 읽어서 채움)
            stage.slideIn(next != null ? next : new LinkedHashMap<>(), dx > 0 ? pw : -pw);
            lastIcons = next != null ? next : new LinkedHashMap<>();
            log("page " + screenKey + " " + from + "->" + to + (next != null ? " cached" : " new"));
        }
        if (Math.abs(dx) <= 2) { // 멈춤
            curPage = Math.round(x / pw);
            if (lastSettledX != Integer.MIN_VALUE && x != lastSettledX) {
                float d = Math.abs(x - lastSettledX); int n = Math.round(d / pw);
                if (n >= 1) pageW = d / n;
            }
            lastSettledX = x;
        }
    }
    final Runnable settle1 = this::scan, settle2 = this::scan, settle3 = this::scan;
    long lastScanAt;
    /** 화면이 바뀌는 중(페이지 넘김 등): 최대 60ms마다 바로 읽고, 멈춘 뒤에도 0.12·0.3·0.6초에 다시 읽어 잡초 위치를 맞춘다. */
    void quickScan() {
        long now = SystemClock.uptimeMillis();
        h.removeCallbacks(scanSoon);
        if (now - lastScanAt >= 60) scan(); else h.postDelayed(scanSoon, 60 - (now - lastScanAt));
        h.removeCallbacks(settle1); h.removeCallbacks(settle2); h.removeCallbacks(settle3);
        h.postDelayed(settle1, 120); h.postDelayed(settle2, 300); h.postDelayed(settle3, 600);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (stage == null) return;
        CharSequence p = e.getPackageName();
        String pkg = p == null ? "" : p.toString();
        int type = e.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED && pkg.equals(launcher)) {
            String l = eventLabel(e);
            if (l != null) { longLabel = l; longAt = SystemClock.uptimeMillis(); }
            log("long " + l);
        } else if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            String t = eventLabel(e);
            if (t != null && t.contains("삭제") && (pkg.equals(launcher) || pkg.contains("packageinstaller"))) onDeleteTapped(pkg, t);
        }
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED && pkg.equals(launcher) && e.getMaxScrollX() == 0 && e.getScrollX() > 0
                && Build.VERSION.SDK_INT >= 28) onPageScroll(e.getScrollX(), e.getScrollDeltaX());
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED && pkg.equals(launcher) && scrollLogLeft > 0) { // 페이지 넘김 신호 조사용(최근 400개만)
            scrollLogLeft--;
            log("scroll " + e.getClassName() + " x=" + e.getScrollX() + "/" + e.getMaxScrollX() + " y=" + e.getScrollY()
                    + (Build.VERSION.SDK_INT >= 28 ? " dx=" + e.getScrollDeltaX() : "") + " from=" + e.getFromIndex() + " to=" + e.getToIndex()
                    + " n=" + e.getItemCount() + " t=" + (SystemClock.uptimeMillis() % 100000));
        }
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && (pkg.equals(launcher) || pkg.contains("packageinstaller"))) {
            logTree("window " + pkg + " " + e.getClassName());
        }
        if (pkg.equals(launcher) || pkg.contains("packageinstaller") || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) quickScan();
    }

    void onDeleteTapped(String pkg, String text) {
        logTree("delete tap " + pkg + " '" + text + "'");
        if (!pkg.equals(launcher)) return; // 시스템 확인 창의 '삭제'는 실제 삭제 알림으로 처리
        if (selecting && !lastSelected.isEmpty()) {
            stage.deleteRequested(new ArrayList<>(lastSelected), true);
        } else if (longLabel != null && SystemClock.uptimeMillis() - longAt < 120_000) {
            List<String> one = new ArrayList<>(); one.add(longLabel);
            stage.deleteRequested(one, false);
        }
    }

    static String eventLabel(AccessibilityEvent e) {
        AccessibilityNodeInfo src = e.getSource();
        String l = src != null ? nodeLabel(src) : null;
        if (l != null && !l.isEmpty()) return l;
        StringBuilder b = new StringBuilder();
        for (CharSequence c : e.getText()) if (c != null) { if (b.length() > 0) b.append(' '); b.append(c); }
        if (b.length() == 0 && e.getContentDescription() != null) {
            String d = e.getContentDescription().toString(); int k = d.indexOf(',');
            b.append(k > 0 ? d.substring(0, k) : d);
        }
        return b.length() == 0 ? null : b.toString().trim();
    }

    /** 아이콘 칸 라벨: 안쪽 글자(이름 TextView)를 먼저, 없으면 설명. 알림 개수 같은 덧붙임은 쉼표 앞까지만. */
    static String nodeLabel(AccessibilityNodeInfo n) {
        String l = null;
        for (int i = 0; i < n.getChildCount() && l == null; i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null && c.getText() != null && c.getText().length() > 0) l = c.getText().toString();
        }
        if (l == null && n.getText() != null && n.getText().length() > 0) l = n.getText().toString();
        if (l == null && n.getContentDescription() != null) {
            l = n.getContentDescription().toString();
            int k = l.indexOf(',');
            if (k > 0) l = l.substring(0, k);
        }
        return l == null ? null : l.trim();
    }

    void scan() {
        if (stage == null) return;
        lastScanAt = SystemClock.uptimeMillis();
        boolean visible = false, home = false;
        Map<String, Rect> icons = new LinkedHashMap<>();
        Set<String> selected = new HashSet<>();
        boolean anyCheckable = false;
        if (shown(prefs)) {
            AccessibilityNodeInfo active = getRootInActiveWindow();
            String activePkg = active != null && active.getPackageName() != null ? active.getPackageName().toString() : "";
            home = activePkg.equals(launcher);
            boolean installer = activePkg.contains("packageinstaller") && stage.shownOnHome; // 삭제 확인 창 동안은 계속 보인다
            boolean ours = activePkg.equals(getPackageName());
            boolean other = everywhere(prefs) && !activePkg.isEmpty() && !activePkg.equals("com.android.systemui");
            visible = home || installer || ours || other;
            if (home || installer) {
                for (AccessibilityWindowInfo w : getWindows()) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r == null || r.getPackageName() == null || !r.getPackageName().toString().equals(launcher)) continue;
                    List<Rect> checked = new ArrayList<>();
                    hotseatSeen = false;
                    anyCheckable |= collect(r, icons, checked, 0);
                    String key = hotseatSeen ? "home" : "apps";
                    if (!key.equals(screenKey)) { screenKey = key; curPage = -1; lastSettledX = Integer.MIN_VALUE; }
                    Stage.iconLayout(key.equals("apps"));
                    for (Rect c : checked) for (Map.Entry<String, Rect> e : icons.entrySet()) if (Rect.intersects(e.getValue(), c)) selected.add(e.getKey());
                }
            }
        }
        boolean scrolling = SystemClock.uptimeMillis() - lastScrollAt < 450;
        if (home && scrolling) {
            // 넘기는 중에는 움직이는 화면을 읽은 값 대신 기억해 둔 페이지 값을 쓴다
            stage.setScene(visible, lastIcons);
        } else {
            stage.setScene(visible, icons);
            if (home && !icons.isEmpty()) {
                lastIcons = icons;
                if (curPage >= 0) pageCache.put(screenKey + ":" + curPage, new LinkedHashMap<>(icons));
            }
        }
        String sig = visible + " " + home + " " + icons.size();
        if (!sig.equals(lastSig)) {
            lastSig = sig;
            Rect f = icons.isEmpty() ? null : icons.values().iterator().next();
            log("scene visible=" + visible + " home=" + home + " " + screenKey + ":" + curPage + " first=" + (f == null ? "-" : f.toShortString()) + " icons=" + icons.keySet());
        }
        // 선택 모드(체크 칸이 보임)
        if (anyCheckable) {
            if (!selecting) { selecting = true; logTree("selection mode"); }
            if (!selected.equals(lastSelected)) { lastSelected = selected; stage.selected(selected); log("selected " + selected); }
        } else if (selecting && home) {
            selecting = false; lastSelected = new HashSet<>(); stage.selectionEnded();
        }
    }

    /** 아이콘 칸(클릭 가능 + 라벨, 아이콘 크기)만 모은다. 반환: 체크 칸이 하나라도 있었는지. */
    boolean collect(AccessibilityNodeInfo n, Map<String, Rect> icons, List<Rect> checked, int depth) {
        if (n == null || depth > 30) return false;
        boolean checkable = false;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        String id = n.getViewIdResourceName();
        if (n.isCheckable()) { checkable = true; if (n.isChecked()) checked.add(r); }
        if (id != null && id.endsWith("/hotseat_container")) hotseatSeen = true;
        boolean iconLike = id != null ? id.endsWith("/icon_container") || id.endsWith("/icon_view_container")
                : (n.isClickable() && n.getContentDescription() != null);
        float dp = getResources().getDisplayMetrics().density;
        if (iconLike && n.isVisibleToUser() && r.width() > 40 * dp && r.height() > 40 * dp && r.width() < 600 && r.height() < 600) {
            String l = nodeLabel(n);
            // 페이지 표시 점 같은 것은 빼고 앱·폴더 칸만
            if (l != null && !l.isEmpty() && !l.contains("페이지") && !icons.containsKey(l)) icons.put(l, r);
        }
        for (int i = 0; i < n.getChildCount(); i++) checkable |= collect(n.getChild(i), icons, checked, depth + 1);
        return checkable;
    }

    // ---- 디버그 기록(기기 안 files/tree.txt, 200KB 넘으면 새로) ----
    void log(String s) { write(stamp() + " " + s + "\n"); }
    void logTree(String why) {
        long now = SystemClock.uptimeMillis();
        if (now - lastLog < 400) return;
        lastLog = now;
        StringBuilder b = new StringBuilder(stamp()).append(" ### ").append(why).append('\n');
        for (AccessibilityWindowInfo w : getWindows()) {
            AccessibilityNodeInfo r = w.getRoot();
            b.append("[win type=").append(w.getType()).append(" layer=").append(w.getLayer()).append(" active=").append(w.isActive())
                    .append(" pkg=").append(r == null ? null : r.getPackageName()).append("]\n");
            if (r != null && r.getPackageName() != null) {
                String p = r.getPackageName().toString();
                if (p.equals(launcher) || p.contains("packageinstaller")) dump(r, b, 0);
            }
        }
        write(b.toString());
    }
    void dump(AccessibilityNodeInfo n, StringBuilder b, int d) {
        if (n == null || d > 25 || b.length() > 60000) return;
        Rect r = new Rect(); n.getBoundsInScreen(r);
        for (int i = 0; i < d; i++) b.append(' ');
        String cls = n.getClassName() == null ? "" : n.getClassName().toString();
        b.append(cls.substring(cls.lastIndexOf('.') + 1)).append(' ').append(n.getViewIdResourceName())
                .append(" t=").append(n.getText()).append(" d=").append(n.getContentDescription())
                .append(n.isClickable() ? " clk" : "").append(n.isCheckable() ? (n.isChecked() ? " [x]" : " [ ]") : "")
                .append(' ').append(r.toShortString()).append('\n');
        for (int i = 0; i < n.getChildCount(); i++) dump(n.getChild(i), b, d + 1);
    }
    static String stamp() { return new SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(new Date()); }
    void write(String s) {
        try {
            File f = new File(getFilesDir(), "tree.txt");
            boolean append = f.length() < 200_000;
            try (FileWriter w = new FileWriter(f, append)) { w.write(s); }
        } catch (Exception ignored) {}
    }

    // ---- 터치 ----
    int gx = -1, gy = -1, gw = -1, gh = -1;
    void placeGrip(RectF r) {
        if (grip == null) return;
        boolean on = !r.isEmpty();
        if (!on) { if (grip.getVisibility() != View.GONE) grip.setVisibility(View.GONE); return; }
        int x = Math.round(r.left), y = Math.round(r.top), w = Math.round(r.width()), hh = Math.round(r.height());
        if (grip.getVisibility() != View.VISIBLE) { grip.setVisibility(View.VISIBLE); gx = -1; }
        if (x == gx && y == gy && w == gw && hh == gh) return;
        gx = x; gy = y; gw = w; gh = hh;
        gripLp.x = x; gripLp.y = y; gripLp.width = w; gripLp.height = hh;
        try { wm.updateViewLayout(grip, gripLp); } catch (RuntimeException ignored) {}
    }

    float downX, downY; long lastTapAt; boolean tickling;
    boolean dragging; Runnable pendingTap, longPress;
    boolean onGripTouch(View v, MotionEvent e) {
        if (stage == null) return true;
        float rx = e.getRawX(), ry = e.getRawY();
        float dp = getResources().getDisplayMetrics().density;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = rx; downY = ry; dragging = false; tickling = false;
                longPress = () -> dragging = stage.startDrag(downX, downY); // 꾹 누르고 끌기 → 볼 당기기
                h.postDelayed(longPress, 350);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && !tickling && Math.hypot(rx - downX, ry - downY) > 10 * dp) { // 바로 문지르기 → 간지럼
                    h.removeCallbacks(longPress); tickling = stage.startTickle();
                }
                if (dragging) stage.dragTo(rx, ry);
                if (tickling) stage.rub();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                h.removeCallbacks(longPress);
                if (dragging) { dragging = false; stage.endDrag(); return true; }
                if (tickling) { tickling = false; stage.endTickle(); return true; }
                if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) return true;
                long now = SystemClock.uptimeMillis();
                if (now - lastTapAt < 300 && pendingTap != null) { // 두 번 톡 → 메뉴
                    h.removeCallbacks(pendingTap); pendingTap = null; lastTapAt = 0; showMenu(); return true;
                }
                lastTapAt = now;
                pendingTap = () -> { pendingTap = null; stage.tap(); }; // 한 번 톡 → 잠깐 움찔(간지럼)
                h.postDelayed(pendingTap, 300);
                return true;
        }
        return true;
    }

    void showMenu() {
        hideMenu();
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(10 * dp); box.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xFFFFFBF2); bg.setCornerRadius(16 * dp); bg.setStroke(Math.round(3 * dp), 0xFF3A2418);
        box.setBackground(bg);
        Typeface tf = null; try { tf = getResources().getFont(R.font.maple_bold); } catch (Exception ignored) {}
        String[] labels = {"🥕 텃밭 열기 (앱 서랍)", "설정 열기", "캬롯 종료", "닫기"};
        Runnable[] acts = {
                () -> { hideMenu(); openGardenWithInvite(); },
                () -> { hideMenu(); Intent i = new Intent(this, MainActivity.class); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); },
                () -> { hideMenu(); dismiss(null); },
                this::hideMenu};
        for (int i = 0; i < labels.length; i++) {
            Button b = new Button(this); b.setText(labels[i]); b.setAllCaps(false); if (tf != null) b.setTypeface(tf);
            if (i == 0) { // 텃밭 열기만 크게·강조
                GradientDrawable hb = new GradientDrawable(); hb.setColor(0xFFE7A13C); hb.setCornerRadius(14 * dp); hb.setStroke(Math.round(2.5f * dp), 0xFF3A2418);
                b.setBackground(hb); b.setTextColor(0xFFFFFFFF); b.setTextSize(17);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, Math.round(54 * dp)); blp.bottomMargin = Math.round(6 * dp);
                b.setLayoutParams(blp);
            }
            final Runnable r = acts[i]; b.setOnClickListener(v -> r.run());
            box.addView(b);
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        // 캬롯 몸 옆(넓은 쪽)에 띄워 정원 초대 모션이 가려지지 않게
        int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int mw = box.getMeasuredWidth(), mh = box.getMeasuredHeight();
        float gap = 12 * dp;
        boolean right = gx + gw / 2f < sw / 2f;
        float mx = right ? gx + gw + gap : gx - mw - gap;
        lp.x = Math.round(Math.max(8 * dp, Math.min(sw - mw - 8 * dp, mx)));
        lp.y = Math.round(Math.max(24 * dp, Math.min(sh - mh - 24 * dp, gy + gh / 2f - mh / 2f)));
        box.setOnTouchListener((v, ev) -> { if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE) hideMenu(); return false; });
        menu = box; wm.addView(box, lp);
    }
    /** 텃밭 열기: 캬롯이 "내 정원에 놀러올래?" 초대 모션을 하고 나서 텃밭 화면이 열린다. */
    void openGardenWithInvite() {
        Runnable open = () -> { Intent i = new Intent(this, GardenActivity.class); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); };
        if (stage != null && stage.shownOnHome && !stage.busy()) { stage.invite(); h.postDelayed(open, 1300); }
        else open.run();
    }

    void hideMenu() { if (menu != null) { try { wm.removeView(menu); } catch (RuntimeException ignored) {} menu = null; } }

    // ---- 텃밭 화면에서 부르는 뽑기 ----
    static void pullAt(float sx, float sy, Runnable grabbed) {
        CarrotService c = current;
        if (c == null || c.stage == null) { grabbed.run(); return; }
        c.stage.pullAt(sx, sy, grabbed);
    }
    static boolean barrowAdd(float sx, float sy, android.graphics.Bitmap item, Runnable picked) {
        CarrotService c = current;
        if (c == null || c.stage == null) return false;
        c.stage.barrowAdd(sx, sy, item, picked); return true;
    }
    static void barrowRemove(android.graphics.Bitmap item) { CarrotService c = current; if (c != null && c.stage != null) c.stage.barrowRemove(item); }
    static void barrowCancel() { CarrotService c = current; if (c != null && c.stage != null) c.stage.barrowCancel(); }
    static void barrowDump(Runnable done) {
        CarrotService c = current;
        if (c == null || c.stage == null) { done.run(); return; }
        c.stage.barrowDump(done);
    }
    static void pullDone() { CarrotService c = current; if (c != null && c.stage != null) c.stage.extPop(); }
    static void pullCancel() { CarrotService c = current; if (c != null && c.stage != null) c.stage.extRelease(); }

    // ---- 첫 화면에서 부르는 것 ----
    /** 시작: 땅에서 솟아오르며 등장. */
    /** 시작 안 한 채로 텃밭을 열면: 시작도 같이(홈으로 보내지 않고 그 자리에서 솟아오름). */
    static void summonIfOff(SharedPreferences p) {
        CarrotService c = current;
        if (shown(p) || c == null || c.stage == null) return;
        c.prefs.edit().putBoolean("shown", true).apply();
        c.stage.enter(); // 텃밭 화면 위에서 바로 솟아오름
        c.scan();
    }

    static void summon() {
        CarrotService c = current;
        if (c == null || c.stage == null) return;
        c.prefs.edit().putBoolean("shown", true).apply();
        c.stage.enterOnHome();
        c.scan();
    }

    /** 종료: 땅으로 쏙 들어간 뒤 숨는다. */
    static void dismiss(Runnable after) {
        CarrotService c = current;
        if (c == null || c.stage == null) { if (after != null) after.run(); return; }
        c.stage.exit(() -> {
            c.prefs.edit().putBoolean("shown", false).apply();
            c.scan();
            if (after != null) after.run();
        });
    }

    static void applySettings() {
        CarrotService c = current;
        if (c == null || c.stage == null) return;
        c.stage.setScale(scaleOf(c.prefs));
        c.refreshStale();
        c.scan();
    }

    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (la != null) la.unregisterCallback(appsCb);
        hideMenu();
        if (grip != null) try { wm.removeView(grip); } catch (RuntimeException ignored) {}
        if (stage != null) { stage.stop(); try { wm.removeView(stage); } catch (RuntimeException ignored) {} }
        stage = null;
        if (current == this) current = null;
        super.onDestroy();
    }
}
