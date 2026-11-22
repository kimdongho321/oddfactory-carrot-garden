"""웹 미리보기용 추가 모션(앱 자산에서 바로 만든 프레임): 수레로 모으기·수레 엎기·햇님이.

앱에서는 잡초 쌓기·날리기를 코드로 그리지만, 웹 뷰어는 그림만 넘기므로 같은 계산으로 미리 그려 둔다.
build_web.py 가 불러서 web/frames 에 저장하고 모션 목록 끝에 붙인다.
"""
import json
import math
import os

from PIL import Image

import build_web as bw

A = os.path.join(bw.ROOT, "android", "app", "src", "main", "assets")
FR = os.path.join(A, "frames")
SIZE = 512
BIN_DX, BIN_DY, BIN_W = 0.42, -0.25, 0.2  # Stage.java 와 같은 값


def load(p):
    return Image.open(p).convert("RGBA")


def square(img, foot_x, foot_y, scale=1.0):
    """발 위치가 캔버스 아래 가운데쯤 오도록 흰 정사각형에 얹는다."""
    S = 460
    c = Image.new("RGBA", (S, S), (255, 255, 255, 255))
    im = img.resize((round(img.width * scale), round(img.height * scale)), Image.NEAREST)
    c.alpha_composite(im, (round(S * 0.36 - foot_x * scale), round(S * 0.92 - foot_y * scale)))
    return c.convert("RGB").resize((SIZE, SIZE), Image.NEAREST)


def stacked(frame, meta, items, flies=()):
    """수레 프레임에 짐칸 잡초 쌓기 + 날아가는 잡초."""
    f = frame.copy()
    bh = meta["h"]; fx, fy = meta["footX"], meta["footY"]
    bx, by, iw = fx + bh * BIN_DX, fy + bh * BIN_DY, bh * BIN_W
    big = Image.new("RGBA", (f.width + 400, f.height + 200), (0, 0, 0, 0))
    big.alpha_composite(f, (0, 200))
    for i, it in enumerate(items):
        ih = iw * it.height / it.width
        jx = ((i * 37) % 9 - 4) * iw * 0.04; rot = ((i * 53) % 15 - 7)
        base = by - i * ih * 0.28
        s = it.resize((round(iw), round(ih)), Image.NEAREST).rotate(-rot, expand=True, resample=Image.NEAREST)
        big.alpha_composite(s, (round(bx + jx - s.width / 2), round(200 + base - s.height)))
    for it, x, y, r in flies:
        ih = iw * it.height / it.width
        s = it.resize((round(iw), round(ih)), Image.NEAREST).rotate(-r, expand=True, resample=Image.NEAREST)
        big.alpha_composite(s, (round(x - s.width / 2), round(200 + y - s.height / 2)))
    return big, fx, fy + 200


def build(out_dir, stamp):
    meta = json.load(open(os.path.join(FR, "barrow.json")))
    weed = load(os.path.join(A, "weed.png"))
    crops = [load(os.path.join(A, "garden", n + "_2.png")) for n in ("carrot", "radish", "tomato")]
    items_all = [weed, crops[0], weed, crops[1]]
    fr = {n: load(os.path.join(FR, n + ".png")) for n in meta["frames"]}
    out, motions = [], []

    def put(name, img, fx, fy, ms):
        square(img, fx, fy).save(os.path.join(out_dir, name + ".png"))
        out.append({"src": "frames/" + name + ".png?v=" + stamp, "ms": ms, "name": name, "tf": ""})

    # 1) 수레로 모으기: 걸어가서 던져 넣기 ×4 → 짐칸에 4개 쌓임
    walk = [("bw_walk_l", 120), ("bw_pass_a", 100), ("bw_walk_r", 120), ("bw_pass_b", 100)]
    n = 0
    for k in range(4):
        for rep in range(2):
            for name, ms in walk:
                img, fx, fy = stacked(fr[name], meta, items_all[:k]); put(f"x_col_{n}", img, fx, fy, ms); n += 1
        for name, ms in (("bw_windup", 220), ("bw_toss", 260)):
            img, fx, fy = stacked(fr[name], meta, items_all[:k]); put(f"x_col_{n}", img, fx, fy, ms); n += 1
        img, fx, fy = stacked(fr["bw_hold"], meta, items_all[:k + 1]); put(f"x_col_{n}", img, fx, fy, 260); n += 1
    motions.append({"id": "barrow_collect", "title": "여러 개 선택 → 수레에 쌓기", "frames": out}); out = []

    # 2) 수레 엎기: 짐칸 잡초가 앞(오른쪽)으로 쏟아져 화면 밖으로
    seq = [("bw_hold", 150), ("bd_start", 150), ("bd_lift", 170), ("bd_mid", 170), ("bd_heave", 160), ("bd_heave", 160),
           ("bd_heave", 160), ("bd_back", 200), ("bd_down", 450), ("bw_hold", 300)]
    bh = meta["h"]; fx0, fy0 = meta["footX"], meta["footY"]
    bx, by = fx0 + bh * BIN_DX, fy0 + bh * BIN_DY
    vel = [(bh * (1.1 + 0.25 * i), -bh * (1.3 + 0.25 * (i % 2)), 300 + 80 * i) for i in range(len(items_all))]
    t_eject = 3  # bd_mid 부터 쏟아짐
    for i, (name, ms) in enumerate(seq):
        flies = []
        if i >= t_eject:
            tt = (i - t_eject + 1) * 0.11
            for j, it in enumerate(items_all):
                vx, vy, vr = vel[j]
                flies.append((it, bx + vx * tt, by - j * bh * 0.09 + vy * tt + bh * 3.2 * tt * tt / 2, vr * tt))
        img, fx, fy = stacked(fr[name], meta, items_all if i < t_eject else [], flies)
        put(f"x_dump_{i}", img, fx, fy, ms)
    motions.append({"id": "barrow_dump", "title": "삭제 끝 → 화면 끝에서 수레 엎기", "frames": out}); out = []

    # 3) 햇님이 튀어나오기(지운 칸에서)
    hat = [load(os.path.join(A, "garden", f"hatnim_{i}.png")) for i in range(6)]
    H = max(h.height for h in hat)
    for i, (h, ms) in enumerate(zip(hat, (160, 170, 170, 230, 180, 900))):
        c = Image.new("RGBA", (H * 2, H + 40), (0, 0, 0, 0)); c.alpha_composite(h, (H - h.width // 2, c.height - h.height - 10))
        put(f"x_hat_{i}", c, H * 1.0 - (H * 2) * 0.0, c.height - 10, ms)
    motions.append({"id": "hatnim", "title": "삭제 → 햇님이 튀어나오기", "frames": out})
    return motions
