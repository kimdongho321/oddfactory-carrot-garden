"""수레 엎기 프레임 합성: 스탠딩 기준 캐릭터 자세(앱 frames/*.png) + 따로 그린 빈 수레(assets/barrow.png)를
바퀴를 축으로 기울여 손잡이 끝이 캐릭터 주먹에 오도록 놓는다. 수레는 단단한 그림 그대로 돌리므로 휘지 않는다.

hand(name): 프레임에서 주먹 위치(가장 앞쪽 = 오른쪽 끝 살색 덩어리)를 찾아 그 점에 손잡이 끝을 맞춘다.
"""
import json, math, os
from PIL import Image

import build_web as bw

A = os.path.join(bw.ROOT, "android", "app", "src", "main", "assets")
FR = os.path.join(A, "frames")


def load(p): return Image.open(p).convert("RGBA")


def fist(frame):
    """프레임에서 가장 오른쪽에 있는 살색(주먹) 덩어리의 가운데."""
    px = frame.load(); W, H = frame.size
    skin = lambda c: c[3] > 0 and c[0] > 230 and 180 < c[1] < 230 and 170 < c[2] < 225
    pts = [(x, y) for y in range(int(H * 0.05), int(H * 0.8)) for x in range(W) if skin(px[x, y])]
    if not pts: return None
    mx = max(p[0] for p in pts)
    near = [p for p in pts if p[0] > mx - 18]
    return sum(p[0] for p in near) / len(near) - 4, sum(p[1] for p in near) / len(near)


def rotated_barrow(bar, s, tilt):
    """수레를 s배로 키우고 바퀴 바닥(axle, groundY)을 축으로 tilt도(앞으로) 돌린 그림과 그 축의 위치."""
    b = json.load(open(os.path.join(A, "barrow.json")))
    im = bar.resize((round(bar.width * s), round(bar.height * s)), Image.NEAREST)
    ax, ay = b["axleX"] * s, b["groundY"] * s
    pad = max(im.size)
    big = Image.new("RGBA", (im.width + 2 * pad, im.height + 2 * pad), (0, 0, 0, 0))
    big.alpha_composite(im, (pad, pad))
    big = big.rotate(-tilt, center=(pad + ax, pad + ay), resample=Image.NEAREST)
    return big, (pad + ax, pad + ay), b


def compose(pose, tilt, wheel_x=None, char_h=320):
    """한 장면: 바퀴는 wheel_x(바닥)에 고정, 캐릭터는 주먹이 손잡이 끝에 오도록 놓는다.
    반환: RGBA, 캐릭터 발 위치, 짐칸 가운데(바닥쪽) 위치, 바퀴 x."""
    meta = json.load(open(os.path.join(FR, "meta.json")))
    fr = load(os.path.join(FR, pose + ".png"))
    k = char_h / meta["h"]
    fr = fr.resize((round(fr.width * k), round(fr.height * k)), Image.NEAREST)
    fx, fy = meta["footX"] * k, meta["footY"] * k
    bar = load(os.path.join(A, "barrow.png"))
    b = json.load(open(os.path.join(A, "barrow.json")))
    s = b["handH"] * k / b["gripH"]
    big, (pax, pay), _ = rotated_barrow(bar, s, tilt)
    a = math.radians(tilt)
    rot = lambda dx, dy: (dx * math.cos(a) - dy * math.sin(a), dx * math.sin(a) + dy * math.cos(a))
    rgx, rgy = rot((b["gripX"] - b["axleX"]) * s, (b["gripY"] - b["groundY"]) * s)
    bx, by = rot(((b["binL"] + b["binR"]) / 2 - b["axleX"]) * s, (b["rimY"] + (b["frontY"] - b["rimY"]) * 0.6 - b["groundY"]) * s)
    h = fist(fr)
    W, H = 1100, 760; ground = 700
    if wheel_x is None: wheel_x = 760
    hx, hy = wheel_x + rgx, ground + rgy           # 손잡이 끝 = 주먹이 있어야 할 곳
    foot_x = hx - (h[0] - fx)                        # 캐릭터를 옮겨 주먹을 거기에
    foot_y = ground
    c = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    c.alpha_composite(big, (round(wheel_x - pax), round(ground - pay)))
    c.alpha_composite(fr, (round(foot_x - fx), round(foot_y - fy)))
    return c, (foot_x, foot_y), (wheel_x + bx, ground + by), wheel_x


SEQ = [("push_a", 0, 200), ("dump_p1", 16, 200), ("dump_p2", 32, 200), ("dump_p3", 48, 380),
       ("dump_l1", 30, 180), ("dump_l2", 14, 170), ("push_a", 0, 160), ("dump_p4", 0, 420), ("bw2_regrip", 0, 140), ("push_a", 0, 400)]

if __name__ == "__main__":
    meta = []
    for i, (pose, t, ms) in enumerate(SEQ):
        im, foot, binp, wx = compose(pose, t)
        im.save(os.path.join(FR, f"dump_{i}.png"))
        meta.append({"ms": ms, "tilt": t, "foot": [round(foot[0], 1), foot[1]], "bin": [round(binp[0], 1), round(binp[1], 1)]})
    json.dump({"w": im.width, "h": im.height, "charH": 320, "frames": meta}, open(os.path.join(FR, "dump.json"), "w"), indent=1)
    print(meta)
