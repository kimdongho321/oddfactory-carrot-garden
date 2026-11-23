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


def compose(pose, tilt, wheel_x=None, char_h=320, free_x=None, lift=0):
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
    foot_x = hx - (h[0] - fx) if free_x is None else free_x  # 잡고 있으면 주먹을 손잡이에, 놓쳤으면 정한 자리에
    foot_y = ground
    c = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    c.alpha_composite(big, (round(wheel_x - pax), round(ground - pay)))
    c.alpha_composite(fr, (round(foot_x - fx), round(foot_y - lift - fy)))  # lift: 발끝 들고 뛰어오름
    return c, (foot_x, foot_y), (wheel_x + bx, ground + by), wheel_x


def compose_lift(pose, char_h=320, foot_x=440, wheel_x=None, want=None):
    """손잡이 들어 쏟기: 캐릭터는 제자리, 주먹 높이에 손잡이 끝이 오도록 수레 기울기를 구하고
    바퀴(바닥)를 그만큼 앞뒤로 옮긴다(수레는 바퀴로 굴러가며 들린다). wheel_x를 주면 기울기만 구하고 바퀴는 거기."""
    meta = json.load(open(os.path.join(FR, "meta.json")))
    fr = load(os.path.join(FR, pose + ".png"))
    k = char_h / meta["h"]
    fr = fr.resize((round(fr.width * k), round(fr.height * k)), Image.NEAREST)
    fx, fy = meta["footX"] * k, meta["footY"] * k
    b = json.load(open(os.path.join(A, "barrow.json")))
    s = b["handH"] * k / b["gripH"]
    ground = 700
    h = fist(fr); hx, hy = foot_x + (h[0] - fx), ground + (h[1] - fy)
    gx, gy = (b["gripX"] - b["axleX"]) * s, (b["gripY"] - b["groundY"]) * s
    if want is not None:  # 이 기울기까지 들려면 몸을 얼마나 띄워야 하나(발끝 들고 폴짝)
        a_ = math.radians(want); need = gx * math.sin(a_) + gy * math.cos(a_)
        return want, max(0.0, (hy - ground) - need)
    best = min(range(-12, 90), key=lambda t: abs(gx * math.sin(math.radians(t)) + gy * math.cos(math.radians(t)) - (hy - ground)))
    a_ = math.radians(best); rgx = gx * math.cos(a_) - gy * math.sin(a_)
    wx = hx - rgx if wheel_x is None else wheel_x
    return best, wx


# 손잡이 들어 쏟기: 캬롯은 바닥에 선 자리 그대로(발 위치 고정), 주먹 높이만큼 수레가 기울고 바퀴가 그만큼 살짝 구른다.
# 얼굴 높이까지 든 3번 칸에서 짐이 쏟아진다.
SEQ = [("push_a", 160), ("lift_crouch", 200), ("lift_up1", 160), ("lift_up2", 650), ("lift_ease", 300), ("push_a", 380)]

if __name__ == "__main__":
    meta = []
    for i, (pose, ms) in enumerate(SEQ):
        t, wx = compose_lift(pose)
        im, foot, binp, _ = compose(pose, t, wheel_x=wx)
        im.save(os.path.join(FR, f"dump_{i}.png"))
        meta.append({"ms": ms, "tilt": t, "foot": [round(foot[0], 1), foot[1]], "bin": [round(binp[0], 1), round(binp[1], 1)]})
    for j in range(len(SEQ), 20):
        q = os.path.join(FR, f"dump_{j}.png")
        if os.path.exists(q): os.remove(q)
    json.dump({"w": im.width, "h": im.height, "charH": 320, "frames": meta}, open(os.path.join(FR, "dump.json"), "w"), indent=1)
    print(meta)
