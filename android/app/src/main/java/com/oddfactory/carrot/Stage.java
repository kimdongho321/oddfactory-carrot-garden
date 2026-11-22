package com.oddfactory.carrot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 홈 화면 위 투명 무대(터치는 모두 아래 홈 화면으로 통과).
 * 평소: 아이콘 앞으로 걷기 → 박수 또는 물주기 → 대기 → 반복.
 * 앱 하나 삭제: 그 아이콘 앞에서 잡초를 붙잡고 버티다가, 실제로 지워지면 쑥 뽑는다.
 * 여러 개 선택 삭제: 체크한 앱마다 잡초를 뽑아 수레에 싣고, 지워지면 수레를 밀어 화면 밖으로 내보낸다.
 */
final class Stage extends View {
    // ---- 모션 정의(프레임 이름, 표시 시간 ms) ----
    static final class Clip {
        final String[] f; final int[] ms; final float[] sy; final int total;
        /** ("프레임", ms, ...). "프레임@0.3"은 발 기준 세로 배율(땅에서 쑥 튀어나오는 눌림·늘어남). */
        Clip(Object... a) {
            f = new String[a.length / 2]; ms = new int[a.length / 2]; sy = new float[a.length / 2]; int t = 0;
            for (int i = 0; i < f.length; i++) {
                String n = (String) a[i * 2]; int k = n.indexOf('@');
                f[i] = k < 0 ? n : n.substring(0, k); sy[i] = k < 0 ? 1f : Float.parseFloat(n.substring(k + 1));
                ms[i] = (Integer) a[i * 2 + 1]; t += ms[i];
            }
            total = t;
        }
        int idx(long t, boolean loop) {
            if (loop) t %= total; else if (t >= total) return f.length - 1;
            for (int i = 0; i < f.length; i++) { if (t < ms[i]) return i; t -= ms[i]; }
            return f.length - 1;
        }
        String at(long t, boolean loop) { return f[idx(t, loop)]; }
    }
    static final Clip WALK = new Clip("walk_lift_left", 150, "walk_pass_left_to_right", 110, "walk_lift_right", 150, "walk_pass_right_to_left", 110);
    static final Clip CLAP = new Clip("master", 200, "clap_in_v2", 240, "clap_hit_v2", 200, "clap_out_v2", 160, "clap_hit_v2", 200,
            "clap_out_v2", 160, "clap_hit_v2", 200, "clap_out_v2", 250, "master", 300);
    static final Clip WATER = new Clip("i01a_water", 260, "i01_water", 300, "i01b_water", 300, "i01_water", 300, "i01b_water", 300,
            "i01_water", 300, "i01b_water", 300, "i01a_water", 260, "master", 300);
    static final Clip GRAB = new Clip("wn_lower1", 160, "wn_lower2", 150, "wn_grab_v2", 220);
    static final Clip TUG = new Clip("wn_grab_v2", 220, "wn_lower2", 140);
    static final Clip POP = new Clip("wn_pop", 700, "wn_lower2", 140, "wn_lower1", 160, "master", 200);

    // 시작: 새싹 → 머리 뿅 → 혀 낼름 → 손 짚고 → 일어남 / 종료: 반대로 쏙 들어감
    static final Clip ENTER = new Clip("e00_sprout@0.3", 80, "e00_sprout@1.15", 90, "e00_sprout@0.92", 80, "e00_sprout", 700,
            "e01_cat@0.25", 90, "e01_cat@1.22", 110, "e01_cat@0.86", 100, "e01_cat@1.07", 90, "e01_cat@0.97", 80, "e01_cat", 300,
            "e01_cat_L", 82, "e01_cat", 62, "e01_cat_R", 82, "e01_cat", 62, "e01_cat_L", 82, "e01_cat", 62, "e01_cat_R", 82, "e01_cat", 200,
            "e01_cat_in", 220, "e02_blink", 140, "e03_hands", 300, "e04_knee", 280, "e05_rise", 240, "master", 320);
    static final Clip EXIT = new Clip("master", 300, "e05_rise", 200, "e04_knee", 200, "e03_hands", 200, "e01_cat_in", 200, "e02_blink", 120,
            "e01_cat", 160, "e01_cat@1.12", 70, "e01_cat@0.6", 70, "e01_cat@0.25", 70, "e00_sprout", 300, "e00_sprout@1.15", 70,
            "e00_sprout@0.4", 70, "e00_sprout@0.05", 60);

    // 볼 당김(꾹 눌러 끌기): 앞 3장은 들어 올려지는 중, 뒤 2장을 누르고 있는 동안 반복
    static final Clip PULL_IN = new Clip("pull_right_mid", 120, "pull_right_mid2", 140, "pull_right_peak", 160);
    static final Clip PULL_LOOP = new Clip("pull_right_struggle_a", 150, "pull_right_struggle_b", 150);
    static final Clip PULL_OUT = new Clip("pull_right_peak", 140, "pull_right_mid", 120, "master", 200);
    // 한 번 톡: 오늘의 수확(당근을 쑥 뽑아 들어 보임)
    static final Clip HARVEST = new Clip("master", 200, "h01_notice", 220, "m05_harvest", 240, "m05b_harvest_v2", 300,
            "h03_popout", 220, "k05_harvest", 1400, "master", 300);
    // 두 번 톡: 정원 초대(손 흔들기) 뒤 메뉴
    static final Clip INVITE = new Clip("master", 150, "m06_invite", 160, "m06b_invite", 160, "k06_invite", 1100,
            "m06b_invite", 160, "m06_invite", 160, "master", 200);
    static final long WALK_MAX = 1500; // 산책 한 번에 걷는 최대 시간(ms)

    enum S { IDLE, WALK, ACT, GRAB, TUG, POP, BARROW_OUT, ENTER, EXIT, DRAG, OFF }

    interface Listener { void bodyAt(RectF r); }
    Listener listener;
    final RectF body = new RectF();
    float grabDX, grabDY; long dragT0; boolean dragLoop;
    enum Goal { WANDER, DELETE_ONE, LOAD }

    final Sprites sp;
    final float dp;
    final Paint px = new Paint(); // 도트 그대로(보간 없이)
    final Random rnd = new Random();
    final int[] loc = new int[2];

    // 화면 정보(이 뷰 좌표)
    final Map<String, RectF> icons = new HashMap<>();
    Set<String> stale = new HashSet<>();      // 30일 넘게 안 쓴 앱(라벨)
    final Set<String> pulled = new HashSet<>(); // 이미 뽑은 잡초
    float cellH = 0;
    boolean shownOnHome;

    // 캐릭터 상태
    float x = Float.NaN, y;      // 발 위치
    float tx, ty;                 // 걷는 목표
    boolean faceRight;
    S s = S.OFF; long t0; Clip act; long idleFor;
    String target;                // 지금 가는/작업 중인 아이콘 라벨
    Goal goal = Goal.WANDER;
    final ArrayDeque<String> queue = new ArrayDeque<>(); // 뽑으러 갈 앱(라벨)
    final List<String> loaded = new ArrayList<>();        // 수레에 실은 잡초
    long multiPending = -60000;   // 선택 삭제를 누른 시각
    String waitingRemoval;        // 단일 삭제: 이 앱이 지워지길 기다림
    long waitSince;
    float scale = 1f;             // 설정의 크기 배율
    boolean running;
    boolean entering;             // 다음에 보일 때 시작 모션부터
    boolean enterOnHome;          // 그 시작 모션을 홈 화면에서(시작 버튼 → 홈으로 나감)
    Runnable onExited;            // 종료 모션이 끝나면 부름

    Stage(Context c, Sprites s) {
        super(c);
        sp = s; dp = c.getResources().getDisplayMetrics().density;
        px.setFilterBitmap(false); px.setAntiAlias(false);
    }

    // 페이지 넘김: 기억해 둔 다음 페이지 잡초를 화면과 함께 옆에서 미끄러져 들어오게
    float slideFrom; long slideT0; static final long SLIDE_MS = 380;
    float slideOffset() {
        long t = SystemClock.uptimeMillis() - slideT0;
        if (slideFrom == 0 || t >= SLIDE_MS) return 0;
        float k = t / (float) SLIDE_MS; k = 1 - (1 - k) * (1 - k) * (1 - k); // 처음 빠르고 끝에 느리게
        return slideFrom * (1 - k);
    }
    void slideIn(Map<String, Rect> screenIcons, float from) {
        slideFrom = from; slideT0 = SystemClock.uptimeMillis();
        setScene(true, screenIcons);
    }

    // ---- 서비스에서 들어오는 정보 ----
    /** visible: 지금 캐릭터를 보일지. screenIcons: 홈 화면이면 아이콘(라벨→화면 좌표), 다른 앱 위면 비어 있음(바닥 모드). */
    void setScene(boolean visible, Map<String, Rect> screenIcons) {
        getLocationOnScreen(loc);
        icons.clear();
        float sum = 0; int n = 0;
        for (Map.Entry<String, Rect> e : screenIcons.entrySet()) {
            Rect r = e.getValue();
            icons.put(e.getKey(), new RectF(r.left - loc[0], r.top - loc[1], r.right - loc[0], r.bottom - loc[1]));
            sum += r.height(); n++;
        }
        if (n > 0) cellH = sum / n;
        // 시작을 눌러 홈 화면으로 나가는 중: 홈 화면(아이콘)에 닿기 전에는 보이지 않다가 거기서 솟아오른다
        if (entering && enterOnHome && n == 0) visible = false;
        boolean show = visible && getWidth() > 0;
        if (show != shownOnHome) {
            shownOnHome = show;
            setVisibility(show ? VISIBLE : INVISIBLE);
            if (show) resume(); else pause();
        }
        if (show && (Float.isNaN(x) || entering)) {
            if (n > 0) { RectF r = icons.get(pick()); x = r.centerX() - charH() * 0.3f; y = r.bottom; }
            else { x = getWidth() * 0.5f; y = groundY(); }
            if (entering) { entering = false; enterOnHome = false; play(S.ENTER, ENTER); } else idle(1500);
        }
        // 다른 앱 위(아이콘 없음)로 바뀌었는데 바닥이 아닌 곳에 서 있으면 바로 바닥으로 걸어간다
        if (show && n == 0 && s == S.IDLE && Math.abs(y - groundY()) > 4) idleFor = 0;
        invalidate();
    }

    /** 아이콘이 없는 화면(다른 앱 위)에서 서 있을 바닥: 화면 아래쪽(내비게이션 바 위). */
    float groundY() { return getHeight() * 0.86f; }

    /** 시작: 지금 보이면 그 자리에서, 아니면 다음에 보일 때 땅에서 솟아오른다. */
    /** 시작 버튼: 지금 화면에서는 숨기고, 홈 화면에 닿으면 거기서 땅에서 솟아오른다. */
    void enterOnHome() {
        enter();
        entering = true; enterOnHome = true; s = S.OFF; x = Float.NaN;
    }

    void enter() {
        enterOnHome = false;
        entering = true; onExited = null; goal = Goal.WANDER; queue.clear(); loaded.clear(); waitingRemoval = null;
        if (shownOnHome && !Float.isNaN(x)) { entering = false; play(S.ENTER, ENTER); }
    }

    /** 종료: 땅으로 쏙 들어간 뒤 done. 안 보이는 중이면 바로 done. */
    void exit(Runnable done) {
        entering = false;
        if (!shownOnHome || Float.isNaN(x) || s == S.OFF) { x = Float.NaN; s = S.OFF; done.run(); return; }
        onExited = done; goal = Goal.WANDER; queue.clear(); waitingRemoval = null;
        play(S.EXIT, EXIT);
    }

    /** 사용자가 '설치 삭제'를 눌렀다. labels 한 개면 단일 삭제, 여러 개면 선택 삭제. */
    void deleteRequested(List<String> labels, boolean multi) {
        if (labels.isEmpty()) return;
        if (multi) {
            goal = Goal.LOAD;
            for (String l : labels) if (!queue.contains(l) && !loaded.contains(l)) queue.add(l);
            multiPending = SystemClock.uptimeMillis();
            if (s == S.IDLE || s == S.ACT || (s == S.WALK && !queue.contains(target))) next();
        } else {
            goal = Goal.DELETE_ONE; queue.clear(); queue.add(labels.get(0));
            waitingRemoval = labels.get(0); waitSince = SystemClock.uptimeMillis();
            next();
        }
    }

    /** 선택 모드에서 새로 체크된 앱: 바로 뽑아서 수레에 싣는다(삭제 확정 전). */
    void selected(Set<String> labels) {
        boolean any = false;
        for (String l : labels) if (!queue.contains(l) && !loaded.contains(l)) { queue.add(l); any = true; }
        // 체크 해제된 앱은 대기열에서 뺀다(이미 실은 것은 그대로 두고, 선택 모드가 끝나면 내려놓는다)
        queue.retainAll(labels);
        if (any) { goal = Goal.LOAD; if (s == S.IDLE || s == S.ACT) next(); }
    }

    /** 선택 모드가 끝났는데 삭제가 없었다: 실은 잡초를 내려놓고 평소대로. */
    void selectionEnded() {
        if (goal != Goal.LOAD) return;
        if (SystemClock.uptimeMillis() - multiPending < 60000) return; // 삭제 확인 중: 지워지길 기다린다
        queue.clear();
        for (String l : loaded) pulled.remove(l);
        loaded.clear(); goal = Goal.WANDER;
        if (s != S.BARROW_OUT) idle(800);
    }

    void removed(String label) {
        if (label == null) return;
        if (label.equals(waitingRemoval)) {
            waitingRemoval = null;
            if (s == S.TUG || s == S.GRAB) { pulled.add(label); play(S.POP, POP); }
            else { pulled.add(label); goal = Goal.WANDER; }
            return;
        }
        if (loaded.contains(label) || queue.contains(label)) {
            queue.remove(label);
            if (!loaded.contains(label)) loaded.add(label);
            pulled.add(label);
            // 실은 앱이 모두 지워졌으면 수레를 밀고 나간다(잠깐 기다려 같은 묶음 삭제를 모은다)
            postDelayed(this::maybeBarrowOut, 700);
        }
    }

    void maybeBarrowOut() {
        if (goal != Goal.LOAD || !queue.isEmpty() || loaded.isEmpty() || s == S.BARROW_OUT) return;
        faceRight = x < getWidth() / 2f; // 가까운 쪽 화면 밖으로
        tx = faceRight ? getWidth() + charW() : -charW();
        play(S.BARROW_OUT, null);
    }

    void setStale(Set<String> labels) { stale = labels; invalidate(); }
    void setScale(float k) { scale = k; invalidate(); }

    // 아이콘 칸(이름 글자 포함) 안의 아이콘 그림 자리(One UI 홈·앱스 화면 기준 비율)
    static float iconSide(RectF r) { return r.width() * ICON_W; }
    static RectF iconImage(RectF r) {
        float s = iconSide(r), t = r.top + r.height() * ICON_TOP;
        return new RectF(r.centerX() - s / 2, t, r.centerX() + s / 2, t + s);
    }
    static float ICON_W = 0.51f, ICON_TOP = 0.33f;
    /** 홈 화면과 앱스 화면은 칸 안 아이콘 그림 자리가 다르다(앱스 화면은 아래에 이름 글자). */
    static void iconLayout(boolean apps) { ICON_W = apps ? 0.45f : 0.51f; ICON_TOP = apps ? 0.12f : 0.33f; }
    // 잡초 자리: 아이콘 그림 뒤에 뿌리(그림 가운데), 잎이 위·옆으로 삐져나온다 / 캬롯이 뽑을 때는 아이콘 아래쪽에 선다
    static float weedX(RectF r) { return r.centerX(); }
    static float weedY(RectF r) { RectF i = iconImage(r); return i.bottom; }

    // ---- 크기 ----
    /** 캐릭터 키: 화면(홈·다른 앱)과 관계없이 항상 같은 크기. 설정의 크기 배율만 반영. */
    float charH() { return 120 * dp * scale; }
    float charW() { return charH() * sp.w / sp.h; }

    // ---- 상태 진행 ----
    void play(S st, Clip c) { s = st; act = c; t0 = SystemClock.uptimeMillis(); invalidate(); }
    void idle(long ms) { s = S.IDLE; idleFor = ms; t0 = SystemClock.uptimeMillis(); target = null; }

    String pick() {
        List<String> ks = new ArrayList<>(icons.keySet());
        if (ks.isEmpty()) return null;
        if (!Float.isNaN(x)) { // 1.5초 안에 닿는 아이콘을 우선(제자리 아이콘은 빼고)
            float reach = walkSpeed() * WALK_MAX / 1000f;
            List<String> near = new ArrayList<>();
            for (String k : ks) {
                RectF r = icons.get(k);
                float d = (float) Math.hypot(r.centerX() - charH() * 0.33f - x, r.bottom - y);
                if (d <= reach && d > charH() * 0.15f) near.add(k);
            }
            if (!near.isEmpty()) return near.get(rnd.nextInt(near.size()));
        }
        return ks.get(rnd.nextInt(ks.size()));
    }
    float walkSpeed() { return charH() * (goal == Goal.WANDER ? 0.55f : 0.9f); }

    /** 다음 할 일: 대기열(잡초) 우선, 없으면 아무 아이콘 앞으로 산책. */
    void next() {
        String k = null;
        while (!queue.isEmpty() && k == null) { String q = queue.peek(); if (icons.containsKey(q)) k = q; else break; }
        if (k == null && goal == Goal.LOAD && !loaded.isEmpty()) { idle(1200); return; } // 화면에 없는 앱 기다림
        if (k == null && goal == Goal.DELETE_ONE) { // 지우려는 앱 아이콘이 이 화면에 없음 → 제자리에서 잡초 붙잡기
            target = waitingRemoval; play(S.GRAB, GRAB); return;
        }
        if (k == null) { goal = goal == Goal.LOAD ? Goal.WANDER : goal; k = pick(); }
        if (k == null) { // 아이콘이 없는 화면: 바닥을 따라 아무 곳으로
            target = null;
            float m = charW() * 0.5f, span = Math.max(1, getWidth() - 2 * m);
            tx = m + rnd.nextFloat() * span; ty = groundY();
            faceRight = tx > x; play(S.WALK, WALK); return;
        }
        target = k;
        RectF r = icons.get(k);
        boolean weedJob = queue.contains(k);
        // 잡초 일: 그림 속 잡초(발 오른쪽)가 아이콘 아래 중앙에 오도록 / 산책: 아이콘 옆에 선다
        // 잡초 일: 그림 속 잡초(발 오른쪽 0.285×키)가 아이콘 잡초 자리에 오도록 선다
        tx = weedJob ? weedX(r) - charH() * 0.285f : r.centerX() - charH() * 0.33f;
        ty = weedJob ? weedY(r) + charH() * 0.02f : r.bottom - cellH * 0.02f;
        tx = Math.max(charW() * 0.35f, Math.min(getWidth() - charW() * 0.35f, tx));
        faceRight = tx > x;
        play(S.WALK, WALK);
    }

    void arrive() {
        if (target != null && queue.contains(target)) { play(S.GRAB, GRAB); return; }
        if (target == null && rnd.nextInt(3) == 0) { idle(1500 + rnd.nextInt(2000)); return; }
        play(S.ACT, rnd.nextBoolean() ? CLAP : WATER);
    }

    void step(long now, float dt) {
        long t = now - t0;
        switch (s) {
            case IDLE:
                if (t >= idleFor) next();
                break;
            case ENTER:
                if (t >= ENTER.total) idle(900);
                break;
            case DRAG:
                break;
            case EXIT:
                if (t >= EXIT.total) { s = S.OFF; x = Float.NaN; Runnable d = onExited; onExited = null; if (d != null) d.run(); }
                break;
            case WALK: {
                // 가던 아이콘이 화면에서 사라졌으면(페이지 넘김·다른 앱으로 전환) 새 목적지
                if (target != null && goal == Goal.WANDER && !icons.containsKey(target)) { next(); break; }
                if (goal == Goal.WANDER && t >= WALK_MAX) { target = null; arrive(); break; } // 1.5초만 걷고 그 자리에서
                float sp = walkSpeed();
                float dx = tx - x, dy = ty - y, d = (float) Math.hypot(dx, dy);
                if (d <= sp * dt) { x = tx; y = ty; arrive(); }
                else { x += dx / d * sp * dt; y += dy / d * sp * dt; }
                break;
            }
            case ACT:
                if (t >= act.total) idle(2200 + rnd.nextInt(2200));
                break;
            case GRAB:
                if (t >= GRAB.total) play(S.TUG, TUG);
                break;
            case TUG:
                if (goal == Goal.LOAD) { // 선택 삭제: 버티다 바로 뽑아 수레에
                    if (t >= 900) { pulled.add(target); queue.remove(target); if (!loaded.contains(target)) loaded.add(target); play(S.POP, POP); }
                } else if (waitingRemoval == null || now - waitSince > 30000) { // 취소됐거나 너무 오래 → 그만
                    waitingRemoval = null; goal = Goal.WANDER; idle(1200);
                }
                break;
            case POP:
                if (t >= POP.total) {
                    if (goal == Goal.DELETE_ONE) goal = Goal.WANDER;
                    idle(goal == Goal.LOAD ? 200 : 1800);
                }
                break;
            case BARROW_OUT: {
                float sp = charH() * 1.1f, dx = tx - x;
                if (Math.abs(dx) <= sp * dt) { // 화면 밖으로 내보냄 → 반대쪽에서 다시 걸어 들어옴
                    loaded.clear(); goal = Goal.WANDER; multiPending = -60000;
                    x = faceRight ? -charW() : getWidth() + charW();
                    String k = pick(); if (k == null) { idle(1000); break; }
                    target = k; RectF r = icons.get(k);
                    tx = r.centerX() - charH() * 0.33f; ty = r.bottom; faceRight = tx > x; play(S.WALK, WALK);
                } else x += Math.signum(dx) * sp * dt;
                break;
            }
            default:
        }
    }

    String frameNow(long now) {
        long t = now - t0;
        switch (s) {
            case WALK: return WALK.at(t, true);
            case ACT: return act.at(t, false);
            case GRAB: return GRAB.at(t, false);
            case TUG: return TUG.at(t, true);
            case POP: return POP.at(t, false);
            case BARROW_OUT: return "b01_barrow";
            case ENTER: return ENTER.at(t, false);
            case DRAG: return t < PULL_IN.total ? PULL_IN.at(t, false) : PULL_LOOP.at(t - PULL_IN.total, true);
            case EXIT: return EXIT.at(t, false);
            case IDLE: return (t % 3200) > 3040 ? "k01_blink" : "master";
            default: return "master";
        }
    }

    // ---- 그리기·시간 ----
    long last;
    final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.1f, (now - last) / 1000f); last = now;
            if (!Float.isNaN(x)) step(now, dt);
            reportBody();
            invalidate();
            postOnAnimation(this);
        }
    };
    void reportBody() {
        if (listener == null) return;
        if (!shownOnHome || Float.isNaN(x) || s == S.OFF || s == S.ENTER || s == S.EXIT) { body.setEmpty(); listener.bodyAt(body); return; }
        float h = charH();
        body.set(x - h * 0.3f + loc[0], y - h * 0.92f + loc[1], x + h * 0.3f + loc[0], y + loc[1]);
        listener.bodyAt(body);
    }

    // ---- 터치(서비스의 터치 창에서) ----
    boolean busy() { return s == S.ENTER || s == S.EXIT || s == S.OFF || s == S.GRAB || s == S.TUG || s == S.POP || s == S.BARROW_OUT; }
    void tap() { if (!busy() && s != S.DRAG) play(S.ACT, HARVEST); }
    void invite() { if (!busy() && s != S.DRAG) play(S.ACT, INVITE); }
    boolean startDrag(float rawX, float rawY) {
        if (busy()) return false;
        grabDX = rawX - loc[0] - x; grabDY = rawY - loc[1] - y;
        target = null; play(S.DRAG, null); return true;
    }
    void dragTo(float rawX, float rawY) {
        if (s != S.DRAG) return;
        x = Math.max(0, Math.min(getWidth(), rawX - loc[0] - grabDX));
        y = Math.max(charH() * 0.9f, Math.min(getHeight(), rawY - loc[1] - grabDY));
    }
    void endDrag() { if (s == S.DRAG) { play(S.ACT, PULL_OUT); } }

    long pausedAt;
    void resume() { if (running) return; running = true; long now = SystemClock.uptimeMillis(); if (pausedAt > 0) t0 += now - pausedAt; last = now; postOnAnimation(tick); }
    void pause() { running = false; pausedAt = SystemClock.uptimeMillis(); removeCallbacks(tick); if (listener != null) { body.setEmpty(); listener.bodyAt(body); } }
    void stop() { pause(); }

    @Override protected void onDraw(Canvas c) {
        if (!shownOnHome || Float.isNaN(x) || s == S.OFF) return;
        // 오래 안 쓴 앱·지우려는 앱 아이콘 아래 잡초(캐릭터가 붙잡고 있는 것은 캐릭터 그림에 이미 있음)
        if (sp.weed != null) {
            boolean holding = s == S.GRAB || s == S.TUG || s == S.POP;
            for (Map.Entry<String, RectF> e : icons.entrySet()) {
                String k = e.getKey();
                boolean weedy = (stale.contains(k) || queue.contains(k) || k.equals(waitingRemoval)) && !pulled.contains(k);
                if (!weedy || (holding && k.equals(target))) continue;
                // 아이콘 그림 뒤에서 돋아나 살랑살랑(아이콘 그림 자리는 잘라 내고 그려서 뒤에 있는 것처럼)
                RectF r = new RectF(e.getValue()); r.offset(slideOffset(), 0);
                RectF img = iconImage(r);
                float ww = img.width() * 1.25f, wh = ww * sp.weed.getHeight() / sp.weed.getWidth();
                float cx = img.centerX(), by = img.top + img.height() * 0.62f;
                float sway = (float) Math.sin(SystemClock.uptimeMillis() / 380.0 + k.hashCode() % 7) * 4f;
                c.save();
                android.graphics.Path hole = new android.graphics.Path();
                float rad = img.width() * 0.3f;
                hole.addRoundRect(img, rad, rad, android.graphics.Path.Direction.CW);
                c.clipOutPath(hole);
                c.rotate(sway, cx, by);
                c.drawBitmap(sp.weed, null, new RectF(cx - ww / 2, by - wh * 0.9f, cx + ww / 2, by + wh * 0.1f), px);
                c.restore();
            }
        }
        Bitmap b = sp.frame(frameNow(SystemClock.uptimeMillis()));
        if (b == null) return;
        float h = charH(), k = h / sp.h, w = sp.w * k;
        float left = x - sp.footX * k, top = y - sp.footY * k;
        // 그림은 오른쪽을 보고 있다 → 왼쪽으로 걸을 때 좌우 반전
        boolean flip = (s == S.WALK || s == S.BARROW_OUT) && !faceRight;
        long t = SystemClock.uptimeMillis() - t0;
        float sy = s == S.ENTER ? ENTER.sy[ENTER.idx(t, false)] : s == S.EXIT ? EXIT.sy[EXIT.idx(t, false)] : 1f;
        c.save();
        if (flip) c.scale(-1, 1, x, y);
        if (sy != 1f) c.scale(1, sy, x, y);
        c.drawBitmap(b, null, new RectF(left, top, left + w, top + h), px);
        c.restore();
    }
}
