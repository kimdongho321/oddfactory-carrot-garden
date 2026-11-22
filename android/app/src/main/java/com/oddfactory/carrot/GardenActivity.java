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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 텃밭 화면: 설치된 앱 = 작물. 팻말에 앱의 진짜 아이콘과 이름.
 * 오늘 많이 쓴 앱은 크게 자라고, 30일 넘게 안 쓴 앱은 잡초. 작물을 누르면 앱 실행, 잡초를 누르면 캬롯이 뽑으러 가고 삭제 확인 창.
 * 런처가 아니다: One UI 홈·앱스 화면은 그대로이고, 이 화면은 앱 아이콘(또는 캬롯 메뉴)으로 여는 보통 화면이다.
 */
public final class GardenActivity extends Activity {
    static volatile boolean open; // 열려 있는 동안은 홈 화면 위 캬롯을 숨긴다(텃밭 안의 캬롯과 겹치지 않게)

    static final String[] CROPS = {"pumpkin", "carrot", "corn", "tomato", "cabbage", "potato", "radish"};
    static final long DAY = 86_400_000L;

    static final class App {
        String pkg, label; UserHandle user; Bitmap icon; int crop, stage; long todayMs, lastUsed; boolean weed, system;
    }

    float dp; Typeface bold, light;
    GardenView garden; ScrollView scroll; TextView title; Button multiBtn, pullBtn;
    final Handler h = new Handler(Looper.getMainLooper());
    LauncherApps la;
    final List<App> apps = new ArrayList<>();
    boolean multi; final Set<App> picked = new HashSet<>();
    final List<App> pullQueue = new ArrayList<>(); App pulling; long pullAskedAt;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        try { bold = getResources().getFont(R.font.maple_bold); light = getResources().getFont(R.font.maple_light); } catch (Exception ignored) {}
        la = getSystemService(LauncherApps.class);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFFE9C793);
        scroll = new ScrollView(this);
        garden = new GardenView();
        scroll.addView(garden);
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> garden.invalidate());
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        // 위쪽 띠: 제목 + 여러 개 뽑기
        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setGravity(Gravity.CENTER_VERTICAL);
        int p = (int) (10 * dp); bar.setPadding(p, (int) (p * 1.4f), p, p);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xEEFFF6E6); bg.setStroke((int) (2 * dp), 0xFF3A2418);
        bar.setBackground(bg);
        title = new TextView(this); title.setTypeface(bold); title.setTextSize(18); title.setTextColor(0xFF3A2418);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        multiBtn = smallButton("여러 개 뽑기", 0xFF8BB86A, v -> setMulti(!multi));
        pullBtn = smallButton("뽑기", 0xFFE08A6B, v -> startPullQueue());
        pullBtn.setVisibility(View.GONE);
        bar.addView(pullBtn); bar.addView(multiBtn);
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        setContentView(root);
        la.registerCallback(cb, h);
        load();
    }

    Button smallButton(String s, int color, View.OnClickListener c) {
        Button x = new Button(this); x.setText(s); x.setAllCaps(false); x.setTypeface(bold); x.setTextColor(0xFFFFFFFF); x.setTextSize(13);
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(14 * dp); g.setStroke((int) (2 * dp), 0xFF3A2418); x.setBackground(g);
        x.setPadding((int) (10 * dp), 0, (int) (10 * dp), 0); x.setMinHeight(0); x.setMinimumHeight((int) (38 * dp));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, (int) (38 * dp)); lp.setMarginStart((int) (6 * dp)); x.setLayoutParams(lp);
        x.setOnClickListener(c); return x;
    }

    @Override protected void onResume() {
        super.onResume(); open = true;
        // 삭제 확인 창에서 돌아왔는데 그 앱이 그대로면 = 취소
        if (pulling != null && SystemClock.uptimeMillis() - pullAskedAt > 400) h.postDelayed(this::checkPullCancelled, 700);
        garden.resume();
    }
    @Override protected void onPause() { open = false; garden.pause(); super.onPause(); }
    @Override protected void onDestroy() { la.unregisterCallback(cb); super.onDestroy(); }

    // ---- 앱 목록·사용 기록 ----
    boolean usageAllowed() {
        AppOpsManager ops = getSystemService(AppOpsManager.class);
        int m = Build.VERSION.SDK_INT >= 29
                ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName())
                : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
        return m == AppOpsManager.MODE_ALLOWED;
    }

    void load() {
        title.setText("텃밭을 살펴보는 중…");
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
                ApplicationInfo ai = a.getApplicationInfo();
                x.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                x.crop = cropOf(ai);
                x.todayMs = today.containsKey(pkg) ? today.get(pkg) : 0;
                x.lastUsed = last.containsKey(pkg) ? last.get(pkg) : 0;
                long installed = 0; try { PackageInfo pi = pm.getPackageInfo(pkg, 0); installed = pi.firstInstallTime; } catch (Exception ignored) {}
                x.weed = usage && !x.system && now - installed > 30 * DAY && now - x.lastUsed > 30 * DAY;
                long min = x.todayMs / 60_000;
                x.stage = x.todayMs <= 0 ? 0 : min < 15 ? 1 : min < 60 ? 2 : 3;
                try { x.icon = toBitmap(a.getBadgedIcon(0), iconPx); } catch (Exception ignored) {}
                list.add(x);
            }
            // 오늘 많이 쓴 순 → 최근 쓴 순 → 잡초(오래된 순)
            list.sort((p, q) -> {
                if (p.weed != q.weed) return p.weed ? 1 : -1;
                if (p.todayMs != q.todayMs) return Long.compare(q.todayMs, p.todayMs);
                return Long.compare(q.lastUsed, p.lastUsed);
            });
            h.post(() -> {
                apps.clear(); apps.addAll(list);
                int weeds = 0, grown = 0; for (App a : apps) { if (a.weed) weeds++; if (a.todayMs > 0) grown++; }
                title.setText(usage ? "오늘 자란 작물 " + grown + " · 잡초 " + weeds : "사용 기록을 허용하면 작물이 자라요");
                garden.layoutCells();
            });
        }, "garden-load").start();
    }

    static int cropOf(ApplicationInfo ai) {
        int c = Build.VERSION.SDK_INT >= 26 ? ai.category : -1;
        if ((ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0) c = ApplicationInfo.CATEGORY_GAME;
        switch (c) {
            case ApplicationInfo.CATEGORY_GAME: return 0;         // 호박
            case ApplicationInfo.CATEGORY_SOCIAL: return 1;       // 당근
            case ApplicationInfo.CATEGORY_VIDEO: return 2;        // 옥수수
            case ApplicationInfo.CATEGORY_AUDIO: return 3;        // 토마토
            case ApplicationInfo.CATEGORY_PRODUCTIVITY: return 4; // 양배추
            case ApplicationInfo.CATEGORY_MAPS: return 5;         // 감자
            default: return 6;                                    // 무
        }
    }

    static Bitmap toBitmap(Drawable d, int px) {
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b); d.setBounds(0, 0, px, px); d.draw(c); return b;
    }

    // ---- 앱 지움 알림 ----
    final LauncherApps.Callback cb = new LauncherApps.Callback() {
        @Override public void onPackageRemoved(String pkg, UserHandle user) {
            App a = find(pkg); if (a == null) return;
            if (a == pulling) { pulling = null; garden.popped(a); }
            else { apps.remove(a); garden.layoutCells(); }
        }
        @Override public void onPackageAdded(String pkg, UserHandle user) { load(); }
        @Override public void onPackageChanged(String pkg, UserHandle user) {}
        @Override public void onPackagesAvailable(String[] p, UserHandle u, boolean r) {}
        @Override public void onPackagesUnavailable(String[] p, UserHandle u, boolean r) {}
    };
    App find(String pkg) { for (App a : apps) if (a.pkg.equals(pkg)) return a; return null; }

    void checkPullCancelled() {
        if (pulling == null) return;
        try { getPackageManager().getPackageInfo(pulling.pkg, 0); } catch (Exception gone) { return; } // 지워짐(알림이 곧 옴)
        App a = pulling; pulling = null; garden.cancelled(a);
        nextInQueue();
    }

    // ---- 누름 ----
    void tapped(App a) {
        if (multi) {
            if (!a.weed) { Toast.makeText(this, "잡초만 골라 뽑을 수 있어요", Toast.LENGTH_SHORT).show(); return; }
            if (!picked.remove(a)) picked.add(a);
            pullBtn.setText("뽑기 " + picked.size()); pullBtn.setVisibility(picked.isEmpty() ? View.GONE : View.VISIBLE);
            garden.invalidate(); return;
        }
        if (a.weed) { pullQueue.clear(); pullQueue.add(a); nextInQueue(); return; }
        try { la.startMainActivity(la.getActivityList(a.pkg, a.user).get(0).getComponentName(), a.user, null, null); }
        catch (Exception e) { Toast.makeText(this, "열 수 없는 앱이에요", Toast.LENGTH_SHORT).show(); }
    }

    void setMulti(boolean on) {
        multi = on; picked.clear();
        multiBtn.setText(on ? "그만 고르기" : "여러 개 뽑기");
        pullBtn.setVisibility(View.GONE);
        garden.invalidate();
    }

    void startPullQueue() {
        pullQueue.clear(); pullQueue.addAll(picked); setMulti(false);
        garden.barrowMode = pullQueue.size() > 1;
        nextInQueue();
    }

    /** 캬롯이 다음 잡초로 걸어가 붙잡으면 → 삭제 확인 창. */
    void nextInQueue() {
        if (pullQueue.isEmpty()) { garden.queueDone(); return; }
        App a = pullQueue.remove(0);
        garden.goPull(a, () -> {
            pulling = a; pullAskedAt = SystemClock.uptimeMillis();
            Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg));
            try { startActivity(i); } catch (Exception e) { pulling = null; garden.cancelled(a); nextInQueue(); }
        });
    }

    // =====================================================================
    /** 텃밭 그림: 배경(흙), 칸마다 작물·잡초 + 팻말(진짜 아이콘·이름), 그리고 돌아다니는 캬롯. */
    final class GardenView extends View {
        final Paint px = new Paint(), text = new Paint(Paint.ANTI_ALIAS_FLAG), box = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Map<String, Bitmap> art = new HashMap<>();
        Bitmap header, dirt, sign, weedBmp; Paint dirtPaint;
        final float[] panel = {0.24f, 0.25f, 0.76f, 0.61f}; // 팻말 그림 안 아이콘 자리(비율)
        final Map<App, Long> yanking = new HashMap<>();     // 뽑혀 날아가는 칸(시작 시각)
        Sprites sp;
        final List<RectF> cells = new ArrayList<>();
        int cols = 3; float cellW, cellH, top0;
        final GestureDetector gd;
        final Random rnd = new Random();
        boolean running; boolean barrowMode;

        // 캬롯
        float cx = Float.NaN, cy, tx, ty; boolean faceRight; String st = "idle"; long t0; Stage.Clip clip; long idleFor = 1200;
        App job; Runnable onGrab; int loadedCount;

        GardenView() {
            super(GardenActivity.this);
            px.setFilterBitmap(false);
            text.setTypeface(bold); text.setColor(0xFF3A2418); text.setTextAlign(Paint.Align.CENTER);
            try { sp = new Sprites(GardenActivity.this); } catch (Exception ignored) {}
            header = asset("garden_wallpaper.jpg");
            weedBmp = asset("weed.png");
            dirt = asset("garden/dirt.jpg");
            sign = asset("garden/sign_b.png");
            try (InputStream in = getAssets().open("garden/signs.json")) {
                org.json.JSONArray pnl = new org.json.JSONObject(new String(in.readAllBytes(), "UTF-8")).getJSONObject("b").getJSONArray("panel");
                for (int i = 0; i < 4; i++) panel[i] = (float) pnl.getDouble(i);
            } catch (Exception ignored) {}
            for (String c : CROPS) for (int s = 0; s < 4; s++) art.put(c + s, asset("garden/" + c + "_" + s + ".png"));
            gd = new GestureDetector(GardenActivity.this, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }
                @Override public boolean onSingleTapUp(MotionEvent e) {
                    for (int i = 0; i < cells.size() && i < apps.size(); i++) if (cells.get(i).contains(e.getX(), e.getY())) { tapped(apps.get(i)); return true; }
                    return false;
                }
            });
        }

        Bitmap asset(String p) {
            try (InputStream in = getAssets().open(p)) { BitmapFactory.Options o = new BitmapFactory.Options(); o.inScaled = false; return BitmapFactory.decodeStream(in, null, o); }
            catch (Exception e) { return null; }
        }

        @Override public boolean onTouchEvent(MotionEvent e) { return gd.onTouchEvent(e) || super.onTouchEvent(e); }

        void layoutCells() {
            cells.clear();
            float w = getResources().getDisplayMetrics().widthPixels;
            cols = w / dp > 600 ? 5 : 3;
            cellW = w / cols; cellH = cellW * 1.25f;
            top0 = headerH();
            for (int i = 0; i < apps.size(); i++) {
                int r = i / cols, c = i % cols;
                cells.add(new RectF(c * cellW, top0 + r * cellH, (c + 1) * cellW, top0 + (r + 1) * cellH));
            }
            requestLayout(); invalidate();
            if (Float.isNaN(cx) && !cells.isEmpty()) { RectF r = cells.get(0); cx = r.centerX(); cy = r.bottom - cellH * 0.1f; idle(1000); }
        }
        float headerH() { return header == null ? 120 * dp : getResources().getDisplayMetrics().widthPixels * 0.55f; }

        @Override protected void onMeasure(int w, int h) {
            int rows = (apps.size() + cols - 1) / Math.max(1, cols);
            int height = (int) (headerH() + rows * cellH + 80 * dp);
            setMeasuredDimension(MeasureSpec.getSize(w), Math.max(height, getResources().getDisplayMetrics().heightPixels));
        }

        // ---- 그리기 ----
        @Override protected void onDraw(Canvas c) {
            int W = getWidth();
            if (dirt != null) { // 흙 바닥(반복 무늬)
                if (dirtPaint == null) { dirtPaint = new Paint(); dirtPaint.setFilterBitmap(false);
                    dirtPaint.setShader(new android.graphics.BitmapShader(dirt, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)); }
                c.drawRect(0, 0, W, getHeight(), dirtPaint);
            }
            float vt = scroll.getScrollY(), vb = vt + scroll.getHeight();
            // 하늘·언덕 머리 그림(배경화면 위쪽) + 그 아래는 흙
            if (header != null) {
                float hh = headerH();
                Rect src = new Rect(0, 0, header.getWidth(), (int) (header.getWidth() * hh / W));
                c.drawBitmap(header, src, new RectF(0, 0, W, hh), px);
            }
            for (int i = 0; i < cells.size() && i < apps.size(); i++) {
                RectF r = cells.get(i);
                if (r.bottom < vt - cellH || r.top > vb + cellH) continue;
                drawCell(c, r, apps.get(i));
            }
            drawCarrot(c);
        }

        void drawCell(Canvas c, RectF r, App a) {
            // 뽑히는 중이면 잡초·팻말이 위로 튀어 오르며 흐려진다
            Long ys = yanking.get(a); float lift = 0; int alpha = 255;
            if (ys != null) {
                float k = Math.min(1f, (SystemClock.uptimeMillis() - ys) / 700f);
                lift = r.height() * 0.6f * (1 - (1 - k) * (1 - k)); alpha = (int) (255 * (1 - k));
                dust(c, r, k);
            }
            Paint ap = new Paint(px); ap.setAlpha(alpha);
            c.save(); c.translate(0, -lift);
            // 작물(또는 잡초): 같은 작물은 4단계가 같은 높이 기준이라 그대로 그리면 자란 정도가 보인다
            Bitmap plant = a.weed ? weedBmp : art.get(CROPS[a.crop] + a.stage);
            float ph = r.height() * (a.weed ? 0.46f : 0.6f), pb = r.top + r.height() * 0.68f, pcx = r.centerX() - r.width() * 0.1f;
            if (plant != null) {
                float pw = ph * plant.getWidth() / plant.getHeight();
                float sway = a.weed ? (float) Math.sin(SystemClock.uptimeMillis() / 380.0 + a.pkg.hashCode() % 7) * 4f : 0;
                c.save(); c.rotate(sway, pcx, pb);
                c.drawBitmap(plant, null, new RectF(pcx - pw / 2, pb - ph, pcx + pw / 2, pb), ap);
                c.restore();
            }
            // 팻말(작물 오른쪽 앞) + 아이콘 자리에 진짜 앱 아이콘
            float sh = r.height() * 0.5f, sw = sign != null ? sh * sign.getWidth() / sign.getHeight() : sh * 0.7f;
            float sx = r.right - sw - r.width() * 0.04f, sy = r.top + r.height() * 0.86f - sh;
            if (sign != null) c.drawBitmap(sign, null, new RectF(sx, sy, sx + sw, sy + sh), ap);
            RectF pr = new RectF(sx + sw * panel[0], sy + sh * panel[1], sx + sw * panel[2], sy + sh * panel[3]);
            float is = Math.min(pr.width(), pr.height()) * 0.86f;
            if (a.icon != null) c.drawBitmap(a.icon, null, new RectF(pr.centerX() - is / 2, pr.centerY() - is / 2, pr.centerX() + is / 2, pr.centerY() + is / 2), ap);
            c.restore();
            if (ys != null) return;
            // 앱 이름
            text.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
            String name = ellipsize(a.label, r.width() * 0.94f);
            float ty0 = r.top + r.height() * 0.97f;
            text.setColor(0xDDFFF6E6); text.setStyle(Paint.Style.STROKE); text.setStrokeWidth(3 * dp); c.drawText(name, r.centerX(), ty0, text);
            text.setColor(0xFF3A2418); text.setStyle(Paint.Style.FILL); c.drawText(name, r.centerX(), ty0, text);
            if (multi && picked.contains(a)) {
                box.setColor(0xFFE08A6B); c.drawCircle(r.left + r.width() * 0.15f, r.top + r.height() * 0.15f, 11 * dp, box);
                text.setColor(Color.WHITE); c.drawText("✓", r.left + r.width() * 0.15f, r.top + r.height() * 0.15f + 5 * dp, text);
            }
        }

        /** 뽑힐 때 흙 튀김(작은 흙덩이 몇 개가 포물선으로). */
        void dust(Canvas c, RectF r, float k) {
            box.setColor(0xFF8A5A33);
            float bx = r.centerX() - r.width() * 0.1f, by = r.top + r.height() * 0.68f;
            for (int i = 0; i < 7; i++) {
                double ang = Math.PI * (0.15 + 0.7 * i / 6.0);
                float d = r.width() * 0.45f * k, x = bx + (float) Math.cos(ang) * d * (i % 2 == 0 ? 1 : -1);
                float y = by - (float) Math.sin(ang) * d + r.height() * 0.5f * k * k;
                c.drawCircle(x, y, (3.5f - 2f * k) * dp, box);
            }
        }

        String ellipsize(String s, float w) {
            if (text.measureText(s) <= w) return s;
            int n = text.breakText(s, true, w - text.measureText("…"), null); return s.substring(0, Math.max(0, n)) + "…";
        }

        // ---- 캬롯 ----
        float charH() { return cellH * 0.95f; }
        void idle(long ms) { st = "idle"; idleFor = ms; t0 = SystemClock.uptimeMillis(); }
        void play(String s, Stage.Clip c) { st = s; clip = c; t0 = SystemClock.uptimeMillis(); }

        void goPull(App a, Runnable grabbed) {
            job = a; onGrab = grabbed;
            int i = apps.indexOf(a); if (i < 0) { grabbed.run(); return; }
            RectF r = cells.get(i);
            // 칸이 화면 밖이면 그쪽으로 스크롤
            scroll.smoothScrollTo(0, (int) Math.max(0, r.centerY() - scroll.getHeight() / 2f));
            tx = r.centerX() - charH() * 0.285f; ty = r.top + r.height() * 0.62f;
            faceRight = tx > cx; play("walk", Stage.WALK);
        }
        void popped(App a) {
            play("pop", Stage.POP); loadedCount++;
            yanking.put(a, SystemClock.uptimeMillis());
            h.postDelayed(() -> { yanking.remove(a); apps.remove(a); layoutCells(); nextInQueue(); }, Math.max(750, Stage.POP.total));
        }
        void cancelled(App a) { job = null; idle(800); }
        void queueDone() {
            if (barrowMode && loadedCount > 1) { barrowMode = false; tx = getWidth() + charH(); faceRight = true; play("barrow", null); }
            loadedCount = 0; job = null;
        }

        void step(float dt) {
            long t = SystemClock.uptimeMillis() - t0;
            float vt = scroll.getScrollY();
            switch (st) {
                case "idle":
                    if (job == null && t >= idleFor) { // 화면에 보이는 칸 근처로 1.5초 산책
                        List<RectF> vis = new ArrayList<>();
                        for (RectF r : cells) if (r.top > vt && r.bottom < vt + scroll.getHeight()) vis.add(r);
                        if (vis.isEmpty()) { idle(1000); break; }
                        RectF r = vis.get(rnd.nextInt(vis.size()));
                        tx = r.centerX() - charH() * 0.3f; ty = r.top + r.height() * 0.62f; faceRight = tx > cx; play("walk", Stage.WALK);
                    }
                    break;
                case "walk": {
                    float spd = charH() * (job != null ? 1.1f : 0.6f), dx = tx - cx, dy = ty - cy, d = (float) Math.hypot(dx, dy);
                    if (job == null && t > Stage.WALK_MAX) d = 0;
                    if (d <= spd * dt) {
                        if (d > 0) { cx = tx; cy = ty; }
                        if (job != null) play("grab", Stage.GRAB);
                        else play("act", rnd.nextBoolean() ? Stage.CLAP : Stage.WATER);
                    } else { cx += dx / d * spd * dt; cy += dy / d * spd * dt; }
                    break;
                }
                case "act": if (t >= clip.total) idle(2000 + rnd.nextInt(2000)); break;
                case "grab": if (t >= Stage.GRAB.total) { play("tug", Stage.TUG); Runnable r = onGrab; onGrab = null; if (r != null) r.run(); } break;
                case "pop": if (t >= Stage.POP.total) { job = null; idle(600); } break;
                case "barrow": if (cx > getWidth() + charH() * 0.5f) { cx = -charH(); idle(300); } else cx += charH() * 1.1f * dt; break;
                default:
            }
        }

        String frame() {
            long t = SystemClock.uptimeMillis() - t0;
            switch (st) {
                case "walk": return Stage.WALK.at(t, true);
                case "act": return clip.at(t, false);
                case "grab": return Stage.GRAB.at(t, false);
                case "tug": return Stage.TUG.at(t, true);
                case "pop": return Stage.POP.at(t, false);
                case "barrow": return "b01_barrow";
                default: return (t % 3200) > 3040 ? "k01_blink" : "master";
            }
        }

        void drawCarrot(Canvas c) {
            if (sp == null || Float.isNaN(cx)) return;
            Bitmap b = sp.frame(frame()); if (b == null) return;
            float hh = charH(), k = hh / sp.h, w = sp.w * k, left = cx - sp.footX * k, top = cy - sp.footY * k;
            boolean flip = (st.equals("walk") || st.equals("barrow")) && !faceRight;
            c.save(); if (flip) c.scale(-1, 1, cx, cy);
            c.drawBitmap(b, null, new RectF(left, top, left + w, top + hh), px);
            c.restore();
        }

        long last;
        final Runnable tick = new Runnable() { @Override public void run() {
            if (!running) return;
            long now = SystemClock.uptimeMillis(); float dt = Math.min(0.1f, (now - last) / 1000f); last = now;
            if (!Float.isNaN(cx)) step(dt);
            invalidate(); postOnAnimation(this);
        } };
        void resume() { if (running) return; running = true; last = SystemClock.uptimeMillis(); postOnAnimation(tick); }
        void pause() { running = false; removeCallbacks(tick); }
    }
}
