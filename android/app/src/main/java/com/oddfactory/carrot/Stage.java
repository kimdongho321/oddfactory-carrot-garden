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
        final String[] f; final int[] ms; final float[] sy, dy; final int total;
        /** ("프레임", ms, ...). "프레임@0.3"은 발 기준 세로 배율(땅에서 쑥 튀어나오는 눌림·늘어남). */
        Clip(Object... a) {
            f = new String[a.length / 2]; ms = new int[a.length / 2]; sy = new float[a.length / 2]; dy = new float[a.length / 2]; int t = 0;
            for (int i = 0; i < f.length; i++) {
                String n = (String) a[i * 2];
                int u = n.indexOf('^'); // "프레임^0.15" = 키의 15%만큼 위로 떠 있음
                if (u >= 0) { dy[i] = Float.parseFloat(n.substring(u + 1)); n = n.substring(0, u); }
                int k = n.indexOf('@');
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
    // 박수: 1.75배 빠르게, 세 번 짝짝짝
    static final Clip CLAP = new Clip("clap_in_v2", 137, "clap_hit_v2", 114, "clap_out_v2", 91, "clap_hit_v2", 114, "clap_out_v2", 91,
            "clap_hit_v2", 114, "clap_out_v2", 143, "master", 171);
    // 물주기: 1.5배 빠르게
    static final Clip WATER = new Clip("i01a_water", 173, "i01_water", 200, "i01b_water", 200, "i01_water", 200, "i01b_water", 200,
            "i01_water", 200, "i01b_water", 200, "i01a_water", 173, "master", 200);
    static final Clip GRAB = new Clip("wn_lower1", 160, "wn_lower2", 150, "wn_grab_v2", 220);
    static final Clip TUG = new Clip("wn_grab_v2", 200, "wn_tug", 260); // 잡고 당기기 반복
    // 텃밭에서 뽑기 성공: 쑥 뽑음 → 뒤로 넘어가며 물러남 → 엉덩방아 → 주저앉아 웃음 → 일어남
    // 쑥 뽑힌 반동으로 살짝 솟았다가(떠 있음) 엉덩방아로 바닥에 털썩
    static final Clip FALL = new Clip("wn_pop", 120, "wn_pop^0.05", 80, "fall_0^0.13", 90, "fall_0^0.16", 90, "fall_0^0.09", 80,
            "fall_1@0.92", 70, "fall_1", 450, "fall_2", 900, "e05_rise", 220, "master", 200);
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
    // 볼 당김: 1.75배 빠르게
    static final Clip PULL_IN = new Clip("pull_right_mid", 69, "pull_right_mid2", 80, "pull_right_peak", 91);
    static final Clip PULL_LOOP = new Clip("pull_right_struggle_a", 86, "pull_right_struggle_b", 86);
    static final Clip PULL_OUT = new Clip("pull_right_peak", 80, "pull_right_mid", 69, "master", 114);
    // 한 번 톡: 오늘의 수확(당근을 쑥 뽑아 들어 보임)
    static final Clip HARVEST = new Clip("master", 200, "h01_notice", 220, "m05_harvest", 240, "m05b_harvest_v2", 300,
            "h03_popout", 220, "k05_harvest", 1400, "master", 300);
    // 두 번 톡: 정원 초대(손 흔들기) 뒤 메뉴
    static final Clip INVITE = new Clip("master", 150, "m06_invite", 160, "m06b_invite", 160, "k06_invite", 1100,
            "m06b_invite", 160, "m06_invite", 160, "master", 200);
    static final long WALK_MAX = 1500; // 산책 한 번에 걷는 최대 시간(ms)

    enum S { IDLE, WALK, ACT, GRAB, TUG, POP, BARROW_OUT, ENTER, EXIT, DRAG, TICKLE, OFF }
    // 간지럼: 몸을 문지르는 동안 꾹 참고 움찔(squirm), 손을 떼면 눈 깜빡이며 원래대로
    static final Clip SQUIRM = new Clip("tk_squirm_a", 150, "tk_squirm_b", 150);
    static final Clip SQUIRM_OUT = new Clip("tk_squirm_a", 160, "k01_blink", 140, "master", 200);
    long rubMs, lastRub;

    // ================= 수레(여러 개 뽑기) =================
    // 고를 때마다: 빈 수레를 밀고 그 칸까지 → 손을 놓고 평소 잡초 뽑기(잡기·버티기·쑥) → 뽑은 것을 수레에 던져 넣음
    // 수레 그림은 따로(barrow.png). 짐은 짐칸 안에 들어가고, 앞판을 다시 덮어 그려 '담긴' 모양으로.
    // 삭제가 끝나면: 가까운 화면 끝으로 밀고 가서 바퀴를 축으로 수레를 기울여 짐을 화면 밖으로 쏟는다.
    // 수레 밀며 걷기: 왼발 → 지나가는 발 → 오른발 → 지나가는 발(몸이 살짝 위로)
    static final Clip PUSH = new Clip("push_a", 130, "push_pass_a", 110, "push_b", 130, "push_pass_b", 110);
    // 잡고 → 당기고 → 잡고 → 당기고 → 쑥 → 던지고 → 손이 손잡이로 돌아오며(중간) → 다시 잡음
    static final Clip PICK = new Clip("wn_lower1", 140, "wn_lower2", 130, "wn_grab_v2", 200, "wn_tug", 240, "wn_grab_v2", 150, "wn_tug", 260,
            "wn_pop", 220, "wn_rise", 200, "bw2_windup", 180, "bw2_toss", 160, "bw2_regrip", 130, "push_a", 250);
    static final long PICK_GRAB = 140 + 130 + 200 + 240 + 150 + 260; // 쑥 뽑히는 순간(칸의 식물이 손으로)
    static final long PICK_TOSS = PICK_GRAB + 220 + 200 + 180 + 60; // 던지는 순간               // 던지는 순간
    // 수레째 엎기(캐릭터가 손잡이를 들어 올려 엎는 그림 6장, 수레·짐 포함)
    static final Clip DUMP = new Clip("push_a", 1); // 실제 엎기 프레임·시간은 dump.json(sp.dMs)
    int dumpIdx(long t) { for (int i = 0; i < sp.dMs.length; i++) { if (t < sp.dMs[i]) return i; t -= sp.dMs[i]; } return sp.dMs.length - 1; }
    long dumpTotal() { long s = 0; for (int m : sp.dMs) s += m; return s; }
    /** 엎기 프레임 그림 좌표 → 화면 좌표(수레 바퀴가 제자리, 첫 프레임의 발이 캐릭터 위치). */
    float[] dumpPt(float px0, float py0) {
        float k = charH() / sp.dCharH, dir = faceRight ? 1 : -1;
        return new float[]{x + dir * (px0 - sp.dFootX[0]) * k, y + (py0 - sp.dGround) * k};
    }
    enum B { NONE, HOLD, WALK, PICK, DUMP }
    static final class Job { float sx, sy; Bitmap item; Runnable picked; boolean dump; Runnable done; }
    static final class Fly { Bitmap b; float x, y, vx, vy, rot, vr; boolean toBin; float t, dur, x0, y0, x1, y1; int slot; }
    B bs = B.NONE; long bt0; final ArrayDeque<Job> jobs = new ArrayDeque<>(); Job job;
    final List<Bitmap> stack = new ArrayList<>(); final List<Fly> flies = new ArrayList<>();
    float tilt; float barrowAlpha = 1f;
    boolean barrowOn() { return bs != B.NONE; }

    /** 고른 앱 하나: 그 칸(뿌리의 화면 좌표 sx, sy)으로 수레를 밀고 가서 뽑아 싣는다. picked: 쑥 뽑힌 순간(칸의 식물을 지움). */
    void barrowAdd(float sx, float sy, Bitmap item, Runnable picked) {
        if (!shownOnHome || Float.isNaN(x) || s == S.OFF || s == S.ENTER || s == S.EXIT || sp.barrow == null) { stack.add(item); picked.run(); return; }
        Job j = new Job(); j.sx = sx - loc[0]; j.sy = sy - loc[1]; j.item = item; j.picked = picked;
        jobs.add(j);
        if (bs == B.NONE) { bs = B.HOLD; bt0 = SystemClock.uptimeMillis(); ext = false; queue.clear(); s = S.IDLE; tilt = 0; barrowAlpha = 1f; faceRight = true; }
        if (bs == B.HOLD) nextJob();
    }
    void barrowRemove(Bitmap item) {
        for (Job j : jobs) if (j.item == item && !j.dump) { jobs.remove(j); return; }
        int i = stack.lastIndexOf(item); if (i >= 0) stack.remove(i);
    }
    void barrowCancel() { jobs.clear(); job = null; stack.clear(); flies.clear(); bs = B.NONE; idle(600); }
    void barrowDump(Runnable done) {
        if (bs == B.NONE) { stack.clear(); done.run(); return; }
        Job j = new Job(); j.dump = true; j.done = done; jobs.add(j);
        if (bs == B.HOLD) nextJob();
    }

    // ---- 수레 위치: 손잡이 끝이 캬롯 손에, 바퀴가 발과 같은 바닥에 ----
    float bK() { return charH() / sp.h; }                                  // 캐릭터 프레임 → 화면 배율
    float barrowScale() { return sp.handH * bK() / sp.bGripH; }            // 수레 그림 → 화면 배율
    /** 수레 그림의 (bx, by) 점이 화면 어디인지(기울기 포함). 바퀴 바닥이 회전축. */
    float[] bpt(float bx, float by) {
        float s = barrowScale(), dir = faceRight ? 1 : -1;
        float gx = x + dir * sp.handX * bK(), gy = y;                        // 손 x, 바닥 y
        float lx = (bx - sp.bGripX) * s, ly = (by - sp.bGroundY) * s;        // 손잡이 기준, 바닥 기준
        float ax = (sp.bAxleX - sp.bGripX) * s;                               // 회전축(바퀴 바닥)
        double a = Math.toRadians(tilt);
        float rx = lx - ax, ry = ly;
        float qx = (float) (rx * Math.cos(a) - ry * Math.sin(a)) + ax, qy = (float) (rx * Math.sin(a) + ry * Math.cos(a)); // 앞으로 기울기
        return new float[]{gx + dir * qx, gy + qy};
    }

    void nextJob() {
        job = jobs.poll();
        if (job == null) { bs = B.HOLD; bt0 = SystemClock.uptimeMillis(); return; }
        float s = barrowScale(), front = (sp.barrow.getWidth() - sp.bGripX) * s + sp.handX * bK();
        if (job.dump) { // 가까운 화면 끝: 바퀴가 화면 끝 바로 안쪽에
            faceRight = x > getWidth() / 2f;
            tx = faceRight ? getWidth() - front - charH() * 0.05f : front + charH() * 0.05f; ty = y;
        } else {        // 평소 잡초 뽑기와 같은 자리(그림 속 잡초가 발 오른쪽 0.285×키)
            faceRight = true;
            tx = Math.max(charW() * 0.4f, Math.min(getWidth() - front, job.sx - charH() * 0.285f)); ty = job.sy;
        }
        bs = B.WALK; bt0 = SystemClock.uptimeMillis();
    }

    void stepBarrow(long now, float dt) {
        long t = now - bt0;
        switch (bs) {
            case WALK: {
                float dx = tx - x, dy = ty - y, dd = (float) Math.hypot(dx, dy);
                float spd = charH() * (dd > charH() * 2.5f ? 1.8f : 1.0f) * 1.75f; // 수레 밀고 가는 속도 1.75배
                if (dd > 1) faceRight = dx > 0 || (job != null && !job.dump) && dx >= 0;
                if (dd <= spd * dt) {
                    x = tx; y = ty; bt0 = now;
                    if (job != null && job.dump) { faceRight = tx > getWidth() / 2f; bs = B.DUMP; } else { faceRight = true; bs = B.PICK; }
                } else { x += dx / dd * spd * dt; y += dy / dd * spd * dt; }
                break;
            }
            case PICK: {
                if (job != null && job.picked != null && t >= PICK_GRAB) { job.picked.run(); job.picked = null; }
                if (job != null && job.item != null && t >= PICK_TOSS) { tossIntoBin(job.item); job.item = null; }
                if (t >= PICK.total) { job = null; nextJob(); }
                break;
            }
            case DUMP: {
                // 그림 속 짐이 쏟아지는 장면(dump_3)에 맞춰 실제 짐도 화면 밖으로 날려 보낸다
                long ejectAt = sp.dMs.length > 3 ? sp.dMs[0] + sp.dMs[1] + sp.dMs[2] + 80 : 600;
                if (!stack.isEmpty() && t >= ejectAt) eject();
                long tot = dumpTotal();
                if (t >= tot) barrowAlpha = Math.max(0, 1 - (t - tot) / 300f);
                if (t >= tot + 300) {
                    Runnable d = job != null ? job.done : null; job = null; bs = B.NONE; stack.clear(); tilt = 0; barrowAlpha = 1f;
                    idle(500); if (d != null) d.run();
                }
                break;
            }
            default:
        }
        for (int i = flies.size() - 1; i >= 0; i--) {
            Fly f = flies.get(i);
            if (f.toBin) { // 손에서 짐칸으로 포물선
                f.t += dt; float k = Math.min(1f, f.t / f.dur);
                f.x = f.x0 + (f.x1 - f.x0) * k; f.y = f.y0 + (f.y1 - f.y0) * k - charH() * 0.35f * 4 * k * (1 - k); f.rot = 360 * k;
                if (k >= 1f) { stack.add(f.b); flies.remove(i); }
                continue;
            }
            f.vy += charH() * 3.2f * dt; f.x += f.vx * dt; f.y += f.vy * dt; f.rot += f.vr * dt;
            if (f.y > getHeight() + charH() || f.x < -charH() * 2 || f.x > getWidth() + charH() * 2) flies.remove(i);
        }
    }

    void tossIntoBin(Bitmap item) {
        Fly f = new Fly(); f.b = item; f.toBin = true; f.dur = 0.38f;
        float dir = faceRight ? 1 : -1;
        f.x0 = x + dir * charH() * 0.3f; f.y0 = y - charH() * 0.55f;
        float[] p = slotPoint(stack.size()); f.x1 = p[0]; f.y1 = p[1] - itemH(item) * 0.5f;
        f.x = f.x0; f.y = f.y0; flies.add(f);
    }

    /** 짐칸 안 n번째 자리(바닥 가운데, 화면 좌표). 한 줄에 3개씩, 위로 한 겹씩 쌓인다. */
    float[] slotPoint(int n) {
        int perRow = 3, row = n / perRow, col = n % perRow;
        float bx = sp.bBinL + (sp.bBinR - sp.bBinL) * (col + 0.5f + (row % 2) * 0.25f) / perRow;
        float by = sp.bRimY + (sp.bFrontY - sp.bRimY) * 0.6f - row * (sp.bFrontY - sp.bRimY) * 0.55f;
        return bpt(bx, by);
    }
    float itemW() { return (sp.bBinR - sp.bBinL) * barrowScale() * 0.42f; }
    float itemH(Bitmap b) { return itemW() * b.getHeight() / b.getWidth(); }

    /** 짐칸의 짐을 기울어진 쪽(화면 밖)으로 쏟는다. */
    void eject() {
        float dir = faceRight ? 1 : -1;
        int fi = bs == B.DUMP && sp.dMs.length > 3 ? 3 : -1;
        for (int i = 0; i < stack.size(); i++) {
            float[] p = fi >= 0 ? dumpPt(sp.dBinX[fi] + (i % 3 - 1) * 20, sp.dBinY[fi] - (i / 3) * 18) : slotPoint(i);
            Fly f = new Fly(); f.b = stack.get(i); f.x = p[0]; f.y = p[1] - itemH(f.b) * 0.5f;
            f.vx = dir * charH() * (1.2f + rnd.nextFloat() * 0.8f); f.vy = -charH() * (0.9f + rnd.nextFloat() * 0.7f);
            f.vr = dir * (300 + rnd.nextInt(400));
            flies.add(f);
        }
        stack.clear();
    }

    String barrowFrame(long now) {
        long t = now - bt0;
        switch (bs) {
            case WALK: return PUSH.at(t * 7 / 4, true); // 걸음도 이동 속도에 맞춰 1.75배
            case PICK: return PICK.at(t, false);
            case DUMP: return DUMP.at(t, false);
            default: return "push_a";
        }
    }

    void drawBarrowSprite(Canvas c, boolean frontOnly) {
        float s = barrowScale(), dir = faceRight ? 1 : -1;
        float[] o = bpt(sp.bGripX, sp.bGroundY);
        c.save();
        c.translate(o[0], o[1]); c.scale(dir, 1); c.rotate(tilt, (sp.bAxleX - sp.bGripX) * s, 0);
        c.translate(-sp.bGripX * s, -sp.bGroundY * s);
        Paint p = new Paint(px); p.setAlpha((int) (255 * barrowAlpha));
        Bitmap b = frontOnly ? sp.barrowFront : sp.barrow;
        RectF r = new RectF(0, 0, b.getWidth() * s, b.getHeight() * s);
        if (frontOnly) c.drawBitmap(b, null, r, p); else outlined(c, b, r, p);
        c.restore();
    }

    void drawItem(Canvas c, Bitmap it, float cx, float bottom, float rot) {
        float iw = itemW(), ih = iw * it.getHeight() / it.getWidth();
        c.save(); c.rotate(rot, cx, bottom - ih / 2);
        c.drawBitmap(it, null, new RectF(cx - iw / 2, bottom - ih, cx + iw / 2, bottom), px);
        c.restore();
    }

    void drawBarrow(Canvas c, long now) {
        if (bs == B.DUMP && sp.dw > 0) { // 스탠딩 기준 자세 + 바퀴 축으로 기울인 수레(합성 프레임)
            int i = dumpIdx(now - bt0);
            Bitmap d = sp.frame("dump_" + i);
            if (d != null) {
                float k = charH() / sp.dCharH, w = sp.dw * k, h = sp.dh * k;
                float left = x - sp.dFootX[0] * k, top = y - sp.dGround * k;
                Paint p = new Paint(px); p.setAlpha((int) (255 * barrowAlpha));
                footShadow(c, x, y);
                c.save(); if (!faceRight) c.scale(-1, 1, x, y);
                outlined(c, d, new RectF(left, top, left + w, top + h), p);
                c.restore();
            }
            for (int j = 0; j < stack.size(); j++) { // 아직 안 쏟은 짐: 짐칸과 함께 기울어짐
                float[] q = dumpPt(sp.dBinX[i] + (j % 3 - 1) * 20, sp.dBinY[i] - (j / 3) * 18);
                drawItem(c, stack.get(j), q[0], q[1], ((j * 53) % 21 - 10) + (faceRight ? sp.dTilt[i] : -sp.dTilt[i]));
            }
            return;
        }
        boolean picking = bs == B.PICK;
        // 1) 뽑는 동안은 캬롯이 수레 앞(손을 놓고 잡초 쪽), 그 밖에는 캬롯이 손잡이 뒤
        drawBarrowSprite(c, false);
        for (int i = 0; i < stack.size(); i++) {               // 짐칸 안 짐(바닥은 앞판에 가려짐)
            float[] p = slotPoint(i);
            drawItem(c, stack.get(i), p[0], p[1], ((i * 53) % 21 - 10) + tilt);
        }
        drawBarrowSprite(c, true);                              // 앞판을 다시 덮어 '안에 담긴' 모양
        Bitmap b = sp.frame(barrowFrame(now));
        if (b != null) {
            // 수레를 기울이면 손잡이가 앞으로 밀려 올라간다 → 캬롯도 그만큼 따라 앞으로
            float cx = x;
            if (tilt != 0) { float t0x = tilt; tilt = 0; float g0 = bpt(sp.bGripX, sp.bGroundY - sp.bGripH)[0]; tilt = t0x; cx += bpt(sp.bGripX, sp.bGroundY - sp.bGripH)[0] - g0; }
            float h = charH(), k = h / sp.h, w = sp.w * k, left = cx - sp.footX * k, top = y - sp.footY * k;
            footShadow(c, cx, y);
            c.save(); if (!faceRight) c.scale(-1, 1, cx, y);
            outlined(c, b, new RectF(left, top, left + w, top + h), px);
            c.restore();
        }
    }

    void drawFlies(Canvas c) {
        for (Fly f : flies) drawItem(c, f.b, f.x, f.y + itemH(f.b) / 2, f.rot);
    }

    interface Listener { void bodyAt(RectF r); }
    Listener listener;
    final RectF body = new RectF();
    float grabDX, grabDY; long dragT0; boolean dragLoop;
    enum Goal { WANDER, DELETE_ONE, LOAD }

    final Sprites sp;
    final float dp;
    final Paint px = new Paint(); // 도트 그대로(보간 없이)
    // 텃밭처럼 비슷한 색 배경에서도 잘 보이게: 흰 테두리(스티커처럼) + 발밑 그림자
    final Paint rim = new Paint();
    { rim.setColorFilter(new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN)); }
    final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    { shade.setColor(0x40000000); }
    void outlined(Canvas c, Bitmap b, RectF r, Paint p) {
        if (!GardenActivity.open) { c.drawBitmap(b, null, r, p); return; } // 텃밭 화면에서만
        float o = Math.max(2f, charH() * 0.014f);
        rim.setAlpha(p.getAlpha());
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i; float dx = (float) Math.cos(a) * o, dy = (float) Math.sin(a) * o;
            c.drawBitmap(b, null, new RectF(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy), rim);
        }
        c.drawBitmap(b, null, r, p);
    }
    void footShadow(Canvas c, float fx, float fy) {
        if (!GardenActivity.open) return;
        float h = charH(); c.drawOval(new RectF(fx - h * 0.2f, fy - h * 0.035f, fx + h * 0.2f, fy + h * 0.035f), shade);
    }
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
        // One UI는 넘김이 끝난 뒤에 신호를 몰아서 보낸다 → 미끄러지는 연출 없이 바로 그 자리에
        slideFrom = 0;
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
    /** 칸 안에서 실제로 그려진 아이콘 판(둥근 덩어리) 자리. 화면 사진으로 잰 비율. */
    static RectF iconImage(RectF r) {
        float hw = r.width() * 0.245f;
        return new RectF(r.centerX() - hw, r.top + r.height() * ICON_TOP, r.centerX() + hw, r.top + r.height() * ICON_BOTTOM);
    }
    static float ICON_TOP = 0.335f, ICON_BOTTOM = 0.73f;
    /** 홈 화면과 앱스 화면은 칸 안 아이콘 판 자리가 다르다(앱스 화면은 아래에 이름 글자). */
    static void iconLayout(boolean apps) { ICON_TOP = apps ? 0.27f : 0.335f; ICON_BOTTOM = apps ? 0.635f : 0.73f; }
    // 잡초 자리: 아이콘 그림 뒤에 뿌리(그림 가운데), 잎이 위·옆으로 삐져나온다 / 캬롯이 뽑을 때는 아이콘 아래쪽에 선다
    static float weedX(RectF r) { return r.centerX(); }
    static float weedY(RectF r) { RectF i = iconImage(r); return i.bottom; }

    // ---- 크기 ----
    /** 캐릭터 키: 화면(홈·다른 앱)과 관계없이 항상 같은 크기. 설정의 크기 배율만 반영. */
    float charH() { return 120 * dp * scale; }
    float charW() { return charH() * sp.w / sp.h; }

    // ---- 상태 진행 ----
    void play(S st, Clip c) { s = st; act = c; t0 = SystemClock.uptimeMillis(); invalidate(); }
    /** 대기: 1.75배 빨리 다음 동작으로(그래도 최소 0.5초는 쉬었다가). */
    void idle(long ms) { s = S.IDLE; idleFor = Math.max(500, Math.round(ms / 1.75)); t0 = SystemClock.uptimeMillis(); target = null; }

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

    // ---- 텃밭 화면에서 부르는 뽑기: 그 칸까지 걸어가(멀면 달려가) 잡초를 붙잡는다 ----
    boolean ext; Runnable extGrab; long extSince;
    /** sx, sy: 뽑을 식물 뿌리의 화면 좌표. 붙잡으면 grabbed(그때 삭제 확인 창). 캬롯이 안 보이면 바로 grabbed. */
    void pullAt(float sx, float sy, Runnable grabbed) {
        if (!shownOnHome || Float.isNaN(x) || s == S.ENTER || s == S.EXIT || s == S.OFF) { grabbed.run(); return; }
        ext = true; extGrab = grabbed; extSince = SystemClock.uptimeMillis();
        target = null; queue.clear(); waitingRemoval = null; goal = Goal.WANDER;
        tx = Math.max(charW() * 0.3f, Math.min(getWidth() - charW() * 0.3f, sx - loc[0] - charH() * 0.285f));
        ty = sy - loc[1];
        faceRight = tx > x; play(S.WALK, WALK);
    }
    /** 지워졌다: 쑥 뽑으며 반동으로 뒤로 물러나 그 칸을 비워 준다(텃밭의 햇님이가 가려지지 않게). */
    float recoilFrom, recoilTo; long recoilT0 = -1;
    void extPop() {
        if (!ext) return;
        ext = false; extGrab = null; play(S.POP, FALL);
        recoilFrom = x; recoilTo = Math.max(charW() * 0.3f, x - charH() * 0.62f);
        if (recoilFrom - recoilTo < charH() * 0.3f) recoilTo = Math.min(getWidth() - charW() * 0.3f, x + charH() * 0.62f); // 왼쪽이 막혔으면 오른쪽으로
        recoilT0 = SystemClock.uptimeMillis();
    }
    void extRelease() { if (ext) { ext = false; extGrab = null; idle(800); } }

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
        if (ext) { faceRight = true; play(S.GRAB, GRAB); return; }
        if (target != null && queue.contains(target)) { play(S.GRAB, GRAB); return; }
        // 걷고 나면 물뿌리기 → 박수 → 대기 순서대로 돌아가며
        int k = actTurn++ % 3;
        if (k == 0) play(S.ACT, WATER); else if (k == 1) play(S.ACT, CLAP); else idle(1500 + rnd.nextInt(2000));
    }
    int actTurn;

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
            case TICKLE:
                if (now - lastRub > 700) endTickle(); // 손은 대고 있는데 안 문지르면 잦아든다
                break;
            case EXIT:
                if (t >= EXIT.total) { s = S.OFF; x = Float.NaN; Runnable d = onExited; onExited = null; if (d != null) d.run(); }
                break;
            case WALK: {
                // 가던 아이콘이 화면에서 사라졌으면(페이지 넘김·다른 앱으로 전환) 새 목적지
                if (!ext && target != null && goal == Goal.WANDER && !icons.containsKey(target)) { next(); break; }
                if (!ext && goal == Goal.WANDER && t >= WALK_MAX) { target = null; arrive(); break; } // 1.5초만 걷고 그 자리에서
                float dx = tx - x, dy = ty - y, d = (float) Math.hypot(dx, dy);
                float sp = ext ? charH() * (d > charH() * 2.5f ? 2.4f : 1.2f) * 1.75f : walkSpeed(); // 삭제하러 가는 걸음 1.75배 // 뽑으러 갈 때 멀면 빨리
                if (d <= sp * dt) { x = tx; y = ty; arrive(); }
                else { x += dx / d * sp * dt; y += dy / d * sp * dt; }
                break;
            }
            case ACT:
                if (t >= act.total) idle(2200 + rnd.nextInt(2200));
                break;
            case GRAB:
                if (t >= GRAB.total) { play(S.TUG, TUG); if (ext && extGrab != null) { Runnable g = extGrab; extGrab = null; g.run(); } }
                break;
            case TUG:
                if (ext) { if (now - extSince > 90000) extRelease(); }
                else if (goal == Goal.LOAD) { // 선택 삭제: 버티다 바로 뽑아 수레에
                    if (t >= 900) { pulled.add(target); queue.remove(target); if (!loaded.contains(target)) loaded.add(target); play(S.POP, POP); }
                } else if (waitingRemoval == null || now - waitSince > 30000) { // 취소됐거나 너무 오래 → 그만
                    waitingRemoval = null; goal = Goal.WANDER; idle(1200);
                }
                break;
            case POP:
                if (recoilT0 > 0) { // 뒤로 넘어가며 물러남(처음 빠르고 끝에 느리게)
                    float k = Math.min(1f, Math.max(0f, (now - recoilT0 - 120) / 380f)); k = 1 - (1 - k) * (1 - k);
                    x = recoilFrom + (recoilTo - recoilFrom) * k;
                    if (k >= 1f) recoilT0 = -1;
                }
                if (t >= (act == FALL ? FALL.total : POP.total)) {
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
            case WALK: return WALK.at(ext ? t * 7 / 4 : t, true);
            case ACT: return act.at(t, false);
            case GRAB: return GRAB.at(t, false);
            case TUG: return TUG.at(t, true);
            case POP: return act == FALL ? FALL.at(t, false) : POP.at(t, false);
            case BARROW_OUT: return "b01_barrow";
            case ENTER: return ENTER.at(t, false);
            case TICKLE: return SQUIRM.at(t, true);
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
            if (!Float.isNaN(x)) { if (barrowOn() || !flies.isEmpty()) stepBarrow(now, dt); if (!barrowOn()) step(now, dt); }
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
    boolean busy() { return barrowOn() || s == S.ENTER || s == S.EXIT || s == S.OFF || s == S.GRAB || s == S.TUG || s == S.POP || s == S.BARROW_OUT; }
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
    boolean startTickle() {
        if (busy() || s == S.DRAG) return false;
        target = null; rubMs = 0; lastRub = SystemClock.uptimeMillis(); play(S.TICKLE, null); return true;
    }
    void rub() {
        if (s != S.TICKLE) return;
        long now = SystemClock.uptimeMillis(); rubMs += Math.min(120, now - lastRub); lastRub = now;
    }
    void endTickle() { if (s == S.TICKLE) play(S.ACT, SQUIRM_OUT); }
    static final Clip TAP_SQUIRM = new Clip("tk_squirm_a", 150, "tk_squirm_b", 150, "tk_squirm_a", 150, "tk_squirm_b", 150, "tk_squirm_a", 160, "k01_blink", 140, "master", 200);
    /** 한 번 톡: 잠깐 움찔(간지럼). */
    void tap() { if (!busy() && s != S.DRAG && s != S.TICKLE) play(S.ACT, TAP_SQUIRM); }

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
                boolean weedy = (queue.contains(k) || k.equals(waitingRemoval)) && !pulled.contains(k); // 오래 안 쓴 앱 잡초는 텃밭 화면에서
                if (!weedy || (holding && k.equals(target))) continue;
                // 아이콘 그림 뒤에서 돋아나 살랑살랑(아이콘 그림 자리는 잘라 내고 그려서 뒤에 있는 것처럼)
                RectF r = new RectF(e.getValue()); r.offset(slideOffset(), 0);
                RectF img = iconImage(r);
                float ww = img.width() * 1.0f, wh = ww * sp.weed.getHeight() / sp.weed.getWidth();
                float cx = img.centerX(), by = img.top + img.height() * 0.6f;
                float sway = (float) Math.sin(SystemClock.uptimeMillis() / 380.0 + k.hashCode() % 7) * 4f;
                c.save();
                android.graphics.Path hole = new android.graphics.Path();
                float rad = img.height() * 0.4f;
                hole.addRoundRect(img, rad, rad, android.graphics.Path.Direction.CW);
                c.clipOutPath(hole);
                c.rotate(sway, cx, by);
                c.drawBitmap(sp.weed, null, new RectF(cx - ww / 2, by - wh * 0.9f, cx + ww / 2, by + wh * 0.1f), px);
                c.restore();
            }
        }
        if (barrowOn() && sp.barrow != null) { drawBarrow(c, SystemClock.uptimeMillis()); drawFlies(c); return; }
        if (!flies.isEmpty()) drawFlies(c);
        Bitmap b = sp.frame(frameNow(SystemClock.uptimeMillis()));
        if (b == null) return;
        float h = charH(), k = h / sp.h, w = sp.w * k;
        float left = x - sp.footX * k, top = y - sp.footY * k;
        // 그림은 오른쪽을 보고 있다 → 왼쪽으로 걸을 때 좌우 반전
        boolean flip = (s == S.WALK || s == S.BARROW_OUT) && !faceRight;
        long t = SystemClock.uptimeMillis() - t0;
        float sy = s == S.ENTER ? ENTER.sy[ENTER.idx(t, false)] : s == S.EXIT ? EXIT.sy[EXIT.idx(t, false)]
                : (s == S.POP && act == FALL) ? FALL.sy[FALL.idx(t, false)] : 1f;
        if (s == S.POP && act == FALL) { float lift = FALL.dy[FALL.idx(t, false)] * h; top -= lift; }
        if (s != S.ENTER && s != S.EXIT) footShadow(c, x, y);
        c.save();
        if (flip) c.scale(-1, 1, x, y);
        if (sy != 1f) c.scale(1, sy, x, y);
        outlined(c, b, new RectF(left, top, left + w, top + h), px);
        c.restore();
    }
}
