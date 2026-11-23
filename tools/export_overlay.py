"""홈 화면 오버레이용 프레임 내보내기.

art/anim/keys 의 키 그림을 웹 뷰어(build_web.py)와 같은 방식(머리 폭 기준 크기·발 위치 맞춤)으로 정렬한 뒤
흰 배경을 투명하게 만들고, 모든 프레임에 같은 자르기 상자를 써서
android/app/src/main/assets/frames/<이름>.png 로 저장한다. 발 위치(아래 중앙)가 모든 프레임에서 같다.
"""
import json
import os
from collections import deque

from PIL import Image

import build_web as bw

OUT = os.path.join(bw.ROOT, "android", "app", "src", "main", "assets", "frames")
H = 320  # 내보낼 높이(px). 앱에서 화면 크기에 맞춰 다시 줄인다.

FRAMES = [
    "master", "k01_blink",
    "walk_lift_left", "walk_pass_left_to_right", "walk_lift_right", "walk_pass_right_to_left",
    "clap_in_v2", "clap_hit_v2", "clap_out_v2",
    "i01a_water", "i01_water", "i01b_water",
    "wn_lower1", "wn_lower2", "wn_grab_v2", "wn_pop",
    "b01_barrow",
    "e00_sprout", "e01_cat", "e01_cat_L", "e01_cat_R", "e01_cat_in", "e02_blink", "e03_hands", "e04_knee", "e05_rise",
    "pull_right_mid", "pull_right_mid2", "pull_right_peak", "pull_right_struggle_a", "pull_right_struggle_b",
    "h01_notice", "m05_harvest", "m05b_harvest_v2", "h03_popout", "k05_harvest",
    "m06_invite", "m06b_invite", "k06_invite",
    "fall_0", "fall_1", "fall_2",
    "push_a", "push_pass_a", "push_b", "push_pass_b", "bw2_toss", "bw2_regrip", "bw2_lift", "wn_tug", "wn_rise", "bw2_windup", "dump_p1", "dump_p2", "dump_p3", "dump_p4", "dump_l1", "dump_l2", "crash_stumble", "crash_windmill", "crash_laugh",
    "tk_squirm_a", "tk_squirm_b", "tk_laugh_a", "tk_laugh_b", "tk_calm", "lift_crouch", "lift_mid", "lift_top", "lift_ease",
]


def load(name):
    """투명 배경 그림은 흰 바탕에 얹어서 읽는다(RGB로 바로 바꾸면 투명 부분이 검게 된다)."""
    p = bw.MASTER if name == "master" else os.path.join(bw.KEYS, name + ".png")
    src = Image.open(p).convert("RGBA")
    white = Image.new("RGBA", src.size, (255, 255, 255, 255))
    white.alpha_composite(src)
    return white.convert("RGB")


def aligned(name, m_cx, m_bot, m_head):
    """build_web.main 과 같은 정렬(1254 캔버스, 흰 배경)."""
    if name == "master":
        return load("master").resize((1254, 1254))
    im = bw._wh(load(name).resize((1254, 1254)))
    hw = bw.head_w(im)
    k = bw.SCALE.get(name) or (m_head / hw if (hw and 0.6 < m_head / hw < 1.7) else 1)
    if abs(k - 1) > 0.02:
        b0 = bw.bbox(im)
        big = im.resize((round(1254 * k), round(1254 * k)), Image.NEAREST)
        cx, by = (b0[0] + b0[2]) / 2 * k, b0[3] * k
        im = Image.new("RGB", (1254, 1254), "white")
        im.paste(big, (round((b0[0] + b0[2]) / 2 - cx), round(b0[3] - by)))
    b = bw.bbox(im)
    canvas = Image.new("RGB", (1254, 1254), "white")
    if name in bw.KEEP_POS:  # 흙먼지 때문에 외곽 상자가 흔들리는 프레임: 그린 자리 그대로, 머리 가운데만 맞춤
        canvas.paste(im, (round(bw.head_cx(load("master").resize((1254, 1254))) - bw.head_cx(im)), 0))
    else:
        canvas.paste(im, (m_cx - (b[0] + b[2]) // 2, m_bot - b[3]))
    return canvas


def clear_bg(im):
    """가장자리에서 이어진 흰색(밝은 무채색)만 투명으로. 테두리 안쪽 흰 옷·날개는 그대로 둔다."""
    im = im.convert("RGBA")
    px = im.load()
    w, h = im.size
    bright = lambda c: min(c[:3]) > 232 and max(c[:3]) - min(c[:3]) < 14
    seen = bytearray(w * h)
    q = deque()
    for x in range(w):
        q.append((x, 0)); q.append((x, h - 1))
    for y in range(h):
        q.append((0, y)); q.append((w - 1, y))
    while q:
        x, y = q.popleft()
        i = y * w + x
        if seen[i]:
            continue
        seen[i] = 1
        if not bright(px[x, y]):
            continue
        px[x, y] = (255, 255, 255, 0)
        if x > 0: q.append((x - 1, y))
        if x < w - 1: q.append((x + 1, y))
        if y > 0: q.append((x, y - 1))
        if y < h - 1: q.append((x, y + 1))
    return im


def main():
    os.makedirs(OUT, exist_ok=True)
    m = load("master").resize((1254, 1254))
    mb = bw.bbox(m)
    m_cx, m_bot, m_head = (mb[0] + mb[2]) // 2, mb[3], bw.head_w(m)
    imgs = {n: clear_bg(aligned(n, m_cx, m_bot, m_head)) for n in FRAMES}
    # 모든 프레임을 덮는 상자. 가로는 발 중심(m_cx) 기준 좌우 대칭으로 잡아 뒤집어도 발 위치가 같게 한다.
    boxes = [im.getbbox() for im in imgs.values()]
    half = max(max(m_cx - b[0], b[2] - m_cx) for b in boxes) + 4
    top = min(b[1] for b in boxes) - 4
    box = (m_cx - half, top, m_cx + half, m_bot + 2)
    k = H / (box[3] - box[1])
    size = (round((box[2] - box[0]) * k), H)
    for n, im in imgs.items():
        im.crop(box).resize(size, Image.LANCZOS).save(os.path.join(OUT, n + ".png"), optimize=True)
    # 앱이 쓰는 정보: 캔버스 크기, 발 위치(아래 중앙), 스탠딩 몸 높이 비율
    meta = {"w": size[0], "h": size[1], "footX": size[0] / 2, "footY": round((m_bot - box[1]) * k),
            "bodyTop": round((mb[1] - box[1]) * k), "frames": FRAMES}
    json.dump(meta, open(os.path.join(OUT, "meta.json"), "w"), indent=1)
    print("frames", len(FRAMES), size, "->", OUT)


if __name__ == "__main__":
    main()
