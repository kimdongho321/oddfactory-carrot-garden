"""웹 미리보기용 추가 모션: 수레(뽑아서 싣기·엎기)·햇님이. 앱(Stage.java)과 같은 계산으로 프레임을 미리 그린다."""
import json
import math
import os

from PIL import Image

import build_web as bw

A = os.path.join(bw.ROOT, "android", "app", "src", "main", "assets")
FR = os.path.join(A, "frames")
SIZE = 512
CW, CH = 1150, 520          # 장면 캔버스
CHAR_H = 300                # 캐릭터 키(px)


def load(p):
    return Image.open(p).convert("RGBA")


class Scene:
    def __init__(self):
        self.meta = json.load(open(os.path.join(FR, "meta.json")))
        self.b = json.load(open(os.path.join(A, "barrow.json")))
        self.barrow = load(os.path.join(A, "barrow.png"))
        self.front = load(os.path.join(A, "barrow_front.png"))
        self.k = CHAR_H / self.meta["h"]
        self.s = self.b["handH"] * self.k / self.b["gripH"]
        self.cache = {}

    def frame(self, n):
        if n not in self.cache: self.cache[n] = load(os.path.join(FR, n + ".png"))
        return self.cache[n]

    def bpt(self, x, y, bx, by, tilt):
        b, s = self.b, self.s
        gx = x + b["handX"] * self.k
        lx, ly = (bx - b["gripX"]) * s, (by - b["groundY"]) * s
        ax = (b["axleX"] - b["gripX"]) * s
        a = math.radians(tilt); rx, ry = lx - ax, ly
        qx = rx * math.cos(a) - ry * math.sin(a) + ax; qy = rx * math.sin(a) + ry * math.cos(a)  # 화면에서 시계 방향(앞으로 기울기)
        return gx + qx, y + qy

    def slot(self, x, y, n, tilt):
        b = self.b; row, col = n // 3, n % 3
        bx = b["binL"] + (b["binR"] - b["binL"]) * (col + 0.5 + (row % 2) * 0.25) / 3
        by = b["rimY"] + (b["frontY"] - b["rimY"]) * 0.6 - row * (b["frontY"] - b["rimY"]) * 0.55
        return self.bpt(x, y, bx, by, tilt)

    def item_w(self): return (self.b["binR"] - self.b["binL"]) * self.s * 0.42

    def paste_barrow(self, c, img, x, y, tilt):
        s = self.s; b = self.b
        im = img.resize((round(img.width * s), round(img.height * s)), Image.NEAREST)
        pivot = ((b["axleX"]) * s, b["groundY"] * s)
        big = Image.new("RGBA", (im.width * 2, im.height * 2), (0, 0, 0, 0))
        off = (round(im.width - pivot[0]), round(im.height - pivot[1]))
        big.alpha_composite(im, off)
        big = big.rotate(-tilt, center=(im.width, im.height), resample=Image.NEAREST)
        px, py = self.bpt(x, y, b["axleX"], b["groundY"], tilt)
        c.alpha_composite(big, (round(px - im.width), round(py - im.height)))

    def item(self, c, it, cx, bottom, rot):
        iw = self.item_w(); ih = iw * it.height / it.width
        s = it.resize((max(1, round(iw)), max(1, round(ih))), Image.NEAREST).rotate(-rot, expand=True, resample=Image.NEAREST)
        c.alpha_composite(s, (round(cx - s.width / 2), round(bottom - ih / 2 - s.height / 2)))

    def render(self, name, x, y, tilt=0, stack=(), flies=(), ground=None):
        c = Image.new("RGBA", (CW, CH), (255, 255, 255, 255))
        self.paste_barrow(c, self.barrow, x, y, tilt)
        if ground is not None:  # 아직 안 뽑은 잡초(칸): 수레 손잡이 앞에 보이게
            it, gx = ground; self.item(c, it, gx, y, 0)
        for i, it in enumerate(stack):
            sx, sy = self.slot(x, y, i, tilt); self.item(c, it, sx, sy, ((i * 53) % 21 - 10) + tilt)
        self.paste_barrow(c, self.front, x, y, tilt)
        f = self.frame(name); k = self.k
        if tilt:  # 손잡이가 앞으로 밀려 올라가는 만큼 캬롯도 따라 앞으로
            x += self.bpt(x, y, self.b["gripX"], self.b["gripY"], tilt)[0] - self.bpt(x, y, self.b["gripX"], self.b["gripY"], 0)[0]
        fi = f.resize((round(f.width * k), round(f.height * k)), Image.NEAREST)
        c.alpha_composite(fi, (round(x - self.meta["footX"] * k), round(y - self.meta["footY"] * k)))
        for it, fx, fy, r in flies: self.item(c, it, fx, fy, r)
        return c.convert("RGB")


def build(out_dir, stamp):
    sc = Scene()
    weed = load(os.path.join(A, "weed.png"))
    items = [weed, load(os.path.join(A, "garden", "carrot_2.png")), weed, load(os.path.join(A, "garden", "radish_2.png"))]
    out, motions = [], []
    Y = 440

    def put(name, img, ms):
        img.resize((round(CW * 0.8), round(CH * 0.8)), Image.NEAREST).save(os.path.join(out_dir, name + ".png"))  # 가로로 넓은 장면
        out.append({"src": "frames/" + name + ".png?v=" + stamp, "ms": ms, "name": name, "tf": ""})

    # 1) 수레로 모으기: 밀고 가서 → 평소 잡초 뽑기 → 던져 넣기, 4번
    n = 0; x = 60.0
    pick = [("wn_lower1", 140), ("wn_lower2", 130), ("wn_grab_v2", 200), ("wn_tug", 240), ("wn_grab_v2", 150), ("wn_tug", 260), ("wn_pop", 220), ("wn_rise", 200), ("bw2_windup", 180)]
    for k in range(4):
        tx = 40 + k * 200                     # 고른 앱 칸까지 수레를 밀고 간다
        steps = max(4, round(abs(tx - x) / 22))
        for i in range(steps):
            xx = x + (tx - x) * (i + 1) / steps
            put(f"x_col_{n}", sc.render(("push_a", "push_pass_a", "push_b", "push_pass_b")[i % 4], xx, Y, 0, items[:k],
                                         ground=(items[k], xx + CHAR_H * 0.285 + (tx - xx))), 130); n += 1
        x = tx; gx = x + CHAR_H * 0.285
        for j, (f, ms) in enumerate(pick):
            put(f"x_col_{n}", sc.render(f, x, Y, 0, items[:k], ground=(items[k], gx) if j < len(pick) - 3 else None), ms); n += 1
        # 던지기: 손 → 짐칸 포물선 4단계
        x0, y0 = x + CHAR_H * 0.3, Y - CHAR_H * 0.55
        x1, y1 = sc.slot(x, Y, k, 0)
        for j in range(4):
            t = (j + 1) / 4; fx = x0 + (x1 - x0) * t; fy = y0 + (y1 - sc.item_w() * 0.5 - y0) * t - CHAR_H * 0.35 * 4 * t * (1 - t)
            # 던진 손은 바로 손잡이로 돌아가고(push_a), 당근만 날아간다
            put(f"x_col_{n}", sc.render(("bw2_toss", "bw2_toss", "bw2_regrip", "push_a")[j], x, Y, 0, items[:k], [(items[k], fx, fy + sc.item_w() * 0.5, 360 * t)] if j < 3 else ()), 90); n += 1
        put(f"x_col_{n}", sc.render("push_a", x, Y, 0, items[:k + 1]), 250); n += 1
    motions.append({"id": "barrow_collect", "title": "여러 개 선택 → 뽑아서 수레에 싣기", "frames": out}); out = []

    # 2) 손잡이 번쩍 들어 쏟기(dump_compose.py): 스탠딩 기준 자세 + 주먹 높이만큼 기울인 수레, 3번 칸에서 짐이 쏟아짐
    dm = json.load(open(os.path.join(FR, "dump.json")))
    kk = CHAR_H / dm["charH"]
    flies = []; stack = list(items); n = 0
    for i, f in enumerate(dm["frames"]):
        d = load(os.path.join(FR, f"dump_{i}.png")); d = d.resize((round(d.width * kk), round(d.height * kk)), Image.NEAREST)
        ox = 60 - dm["frames"][0]["foot"][0] * kk + 200; oy = Y - dm["ground"] * kk if "ground" in dm else Y - f["foot"][1] * kk
        steps = max(1, f["ms"] // 80) if (flies or (i == 3 and stack)) else 1  # 짐이 날아갈 때만 쪼개고, 같은 그림 반복 없음
        for st in range(steps):
            c = Image.new("RGBA", (CW, CH), (255, 255, 255, 255)); c.alpha_composite(d, (round(ox), round(oy)))
            if i == 3 and st == 0 and stack:
                for j, it in enumerate(stack):
                    flies.append([it, ox + f["bin"][0] * kk + (j % 3 - 1) * 20, oy + f["bin"][1] * kk - (j // 3) * 18,
                                  1.1 + 0.25 * j, -(0.9 + 0.2 * (j % 2)), 400 + 80 * j, 0])
                stack = []
            for j, it in enumerate(stack):
                sc.item(c, it, ox + f["bin"][0] * kk + (j % 3 - 1) * 20, oy + f["bin"][1] * kk - (j // 3) * 18, ((j * 53) % 21 - 10) + f["tilt"])
            flies = [fl for fl in flies if fl[1] < CW + 80 and fl[2] < CH + 80]  # 화면 밖으로 나간 짐은 뺌
            for fl in flies:
                dt = 0.08; fl[4] += 3.2 * dt; fl[1] += fl[3] * CHAR_H * dt; fl[2] += fl[4] * CHAR_H * dt; fl[6] += fl[5] * dt
                sc.item(c, fl[0], fl[1], fl[2], fl[6])
            put(f"x_dump_{n}", c.convert("RGB"), round(f["ms"] / steps)); n += 1
    motions.append({"id": "barrow_dump", "title": "삭제 끝 → 손잡이 들어 털어 쏟기", "frames": out}); out = []

    # 3) 햇님이
    hat = [load(os.path.join(A, "garden", f"hatnim_{i}.png")) for i in range(6)]
    H = max(h.height for h in hat)
    for i, (h, ms) in enumerate(zip(hat, (160, 170, 170, 230, 180, 900))):
        c = Image.new("RGBA", (H * 2, H * 2), (255, 255, 255, 255)); c.alpha_composite(h, (H - h.width // 2, int(H * 1.7) - h.height))
        c.convert("RGB").resize((SIZE, SIZE), Image.NEAREST).save(os.path.join(out_dir, f"x_hat_{i}.png"))
        out.append({"src": f"frames/x_hat_{i}.png?v=" + stamp, "ms": ms, "name": f"x_hat_{i}", "tf": ""})
    motions.append({"id": "hatnim", "title": "삭제 → 햇님이 튀어나오기", "frames": out})
    return motions
