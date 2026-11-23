package com.oddfactory.carrot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.JSONObject;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** assets/frames/*.png(tools/export_overlay.py 출력). 모든 프레임이 같은 캔버스이고 발 위치(아래 중앙)가 같다. */
final class Sprites {
    final int w, h;          // 프레임 캔버스(px)
    final float footX, footY; // 캔버스 안 발 위치
    final Bitmap weed;
    // 수레(따로 그린 빈 수레, barrow.json): 손잡이 끝·바닥·바퀴축·짐칸 위치(수레 그림 px), 밀 때 손 위치(캐릭터 프레임 px)
    Bitmap barrow, barrowFront;
    int dw, dh; float dFootX, dFootY; // 수레째 엎기 프레임(캐릭터+수레 한 그림)
    float bGripX, bGripH, bGroundY, bAxleX, bBinL, bBinR, bRimY, bFrontY, handX, handH;
    private final Context ctx;
    private final Map<String, Bitmap> cache = new HashMap<>();

    Sprites(Context c) throws Exception {
        ctx = c.getApplicationContext();
        JSONObject m = new JSONObject(read("frames/meta.json"));
        w = m.getInt("w"); h = m.getInt("h");
        footX = (float) m.getDouble("footX"); footY = (float) m.getDouble("footY");
        weed = decode("weed.png");
        try {
            JSONObject b = new JSONObject(read("barrow.json"));
            barrow = decode("barrow.png"); barrowFront = decode("barrow_front.png");
            bGripX = (float) b.getDouble("gripX"); bGripH = (float) b.getDouble("gripH"); bGroundY = (float) b.getDouble("groundY");
            bAxleX = (float) b.getDouble("axleX"); bBinL = (float) b.getDouble("binL"); bBinR = (float) b.getDouble("binR");
            bRimY = (float) b.getDouble("rimY"); bFrontY = (float) b.getDouble("frontY");
            handX = (float) b.getDouble("handX"); handH = (float) b.getDouble("handH");
        } catch (Exception e) { barrow = null; }
        try {
            JSONObject d = new JSONObject(read("frames/dump.json"));
            dw = d.getInt("w"); dh = d.getInt("h"); dFootX = (float) d.getDouble("footX"); dFootY = (float) d.getDouble("footY");
        } catch (Exception ignored) {}
    }

    Bitmap frame(String name) {
        Bitmap b = cache.get(name);
        if (b == null) { b = decode("frames/" + name + ".png"); if (b != null) cache.put(name, b); }
        return b;
    }

    private String read(String path) throws Exception {
        try (InputStream in = ctx.getAssets().open(path)) { return new String(in.readAllBytes(), "UTF-8"); }
    }

    private Bitmap decode(String path) {
        try (InputStream in = ctx.getAssets().open(path)) {
            BitmapFactory.Options o = new BitmapFactory.Options(); o.inScaled = false;
            return BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) { return null; }
    }
}
