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
        final String[] f; final int[] ms; final int total;
        Clip(Object... a) {
            f = new String[a.length / 2]; ms = new int[a.length / 2]; int t = 0;
            for (int i = 0; i < f.length; i++) { f[i] = (String) a[i * 2]; ms[i] = (Integer) a[i * 2 + 1]; t += ms[i]; }
            total = t;
        }
        String at(long t, boolean loop) {
            if (loop) t %= total; else if (t >= total) return f[f.length - 1];
            for (int i = 0; i < f.length; i++) { if (t < ms[i]) return f[i]; t -= ms[i]; }
            return f[f.length - 1];
        }
    }
    static final Clip WALK = new Clip("walk_lift_left", 150, "walk_pass_left_to_right", 110, "walk_lift_right", 150, "walk_pass_right_to_left", 110);
    static final Clip CLAP = new Clip("master", 200, "clap_in_v2", 240, "clap_hit_v2", 200, "clap_out_v2", 160, "clap_hit_v2", 200,
            "clap_out_v2", 160, "clap_hit_v2", 200, "clap_out_v2", 250, "master", 300);
    static final Clip WATER = new Clip("i01a_water", 260, "i01_water", 300, "i01b_water", 300, "i01_water", 300, "i01b_water", 300,
            "i01_water", 300, "i01b_water", 300, "i01a_water", 260, "master", 300);
    static final Clip GRAB = new Clip("wn_lower1", 160, "wn_lower2", 150, "wn_grab_v2", 220);
    static final Clip TUG = new Clip("wn_grab_v2", 220, "wn_lower2", 140);
    static final Clip POP = new Clip("wn_pop", 700, "wn_lower2", 140, "wn_lower1", 160, "master", 200);

    enum S { IDLE, WALK, ACT, GRAB, TUG, POP, BARROW_OUT, OFF }
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

    Stage(Context c, Sprites s) {
        super(c);
        sp = s; dp = c.getResources().getDisplayMetrics().density;
        px.setFilterBitmap(false); px.setAntiAlias(false);
    }

    // ---- 서비스에서 들어오는 정보 ----
    void setHome(boolean home, Map<String, Rect> screenIcons) {
        getLocationOnScreen(loc);
        icons.clear();
        float sum = 0; int n = 0;
        for (Map.Entry<String, Rect> e : screenIcons.entrySet()) {
            Rect r = e.getValue();
            icons.put(e.getKey(), new RectF(r.left - loc[0], r.top - loc[1], r.right - loc[0], r.bottom - loc[1]));
            sum += r.height(); n++;
        }
        if (n > 0) cellH = sum / n;
        boolean show = home && (n > 0 || !Float.isNaN(x));
        if (show != shownOnHome) {
            shownOnHome = show;
            setVisibility(show ? VISIBLE : INVISIBLE);
            if (show) resume(); else pause();
        }
        if (show && Float.isNaN(x) && n > 0) {
            String k = pick();
            RectF r = icons.get(k);
            x = r.centerX() - charH() * 0.3f; y = r.bottom; idle(1500);
        }
        invalidate();
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

    // ---- 크기 ----
    float charH() { float base = cellH > 0 ? cellH * 1.1f : 150 * dp; return Math.max(90 * dp, Math.min(260 * dp, base)) * scale; }
    float charW() { return charH() * sp.w / sp.h; }

    // ---- 상태 진행 ----
    void play(S st, Clip c) { s = st; act = c; t0 = SystemClock.uptimeMillis(); invalidate(); }
    void idle(long ms) { s = S.IDLE; idleFor = ms; t0 = SystemClock.uptimeMillis(); target = null; }

    String pick() {
        List<String> ks = new ArrayList<>(icons.keySet());
        return ks.isEmpty() ? null : ks.get(rnd.nextInt(ks.size()));
    }

    /** 다음 할 일: 대기열(잡초) 우선, 없으면 아무 아이콘 앞으로 산책. */
    void next() {
        String k = null;
        while (!queue.isEmpty() && k == null) { String q = queue.peek(); if (icons.containsKey(q)) k = q; else break; }
        if (k == null && goal == Goal.LOAD && !loaded.isEmpty()) { idle(1200); return; } // 화면에 없는 앱 기다림
        if (k == null && goal == Goal.DELETE_ONE) { // 지우려는 앱 아이콘이 이 화면에 없음 → 제자리에서 잡초 붙잡기
            target = waitingRemoval; play(S.GRAB, GRAB); return;
        }
        if (k == null) { goal = goal == Goal.LOAD ? Goal.WANDER : goal; k = pick(); }
        if (k == null) { idle(2000); return; }
        target = k;
        RectF r = icons.get(k);
        boolean weedJob = queue.contains(k);
        // 잡초 일: 그림 속 잡초(발 오른쪽)가 아이콘 아래 중앙에 오도록 / 산책: 아이콘 옆에 선다
        tx = r.centerX() - charH() * (weedJob ? 0.27f : 0.33f);
        ty = r.bottom - (weedJob ? 0 : cellH * 0.02f);
        tx = Math.max(charW() * 0.35f, Math.min(getWidth() - charW() * 0.35f, tx));
        faceRight = tx > x;
        play(S.WALK, WALK);
    }

    void arrive() {
        if (target != null && queue.contains(target)) { play(S.GRAB, GRAB); return; }
        play(S.ACT, rnd.nextBoolean() ? CLAP : WATER);
    }

    void step(long now, float dt) {
        long t = now - t0;
        switch (s) {
            case IDLE:
                if (t >= idleFor) next();
                break;
            case WALK: {
                float sp = charH() * (goal == Goal.WANDER ? 0.55f : 0.9f);
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
            invalidate();
            postOnAnimation(this);
        }
    };
    long pausedAt;
    void resume() { if (running) return; running = true; long now = SystemClock.uptimeMillis(); if (pausedAt > 0) t0 += now - pausedAt; last = now; postOnAnimation(tick); }
    void pause() { running = false; pausedAt = SystemClock.uptimeMillis(); removeCallbacks(tick); }
    void stop() { pause(); }

    @Override protected void onDraw(Canvas c) {
        if (!shownOnHome || Float.isNaN(x)) return;
        // 오래 안 쓴 앱·지우려는 앱 아이콘 아래 잡초(캐릭터가 붙잡고 있는 것은 캐릭터 그림에 이미 있음)
        if (sp.weed != null) {
            boolean holding = s == S.GRAB || s == S.TUG || s == S.POP;
            for (Map.Entry<String, RectF> e : icons.entrySet()) {
                String k = e.getKey();
                boolean weedy = (stale.contains(k) || queue.contains(k) || k.equals(waitingRemoval)) && !pulled.contains(k);
                if (!weedy || (holding && k.equals(target))) continue;
                RectF r = e.getValue();
                float ww = r.width() * 0.34f, wh = ww * sp.weed.getHeight() / sp.weed.getWidth();
                c.drawBitmap(sp.weed, null, new RectF(r.right - ww * 1.05f, r.bottom - wh * 0.95f, r.right - ww * 0.05f, r.bottom + wh * 0.05f), px);
            }
        }
        Bitmap b = sp.frame(frameNow(SystemClock.uptimeMillis()));
        if (b == null) return;
        float h = charH(), k = h / sp.h, w = sp.w * k;
        float left = x - sp.footX * k, top = y - sp.footY * k;
        boolean flip = (s == S.WALK || s == S.BARROW_OUT) && faceRight;
        if (flip) { c.save(); c.scale(-1, 1, x, y); }
        c.drawBitmap(b, null, new RectF(left, top, left + w, top + h), px);
        if (flip) c.restore();
    }
}
