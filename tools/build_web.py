"""캬롯 모션 미리보기 페이지 만들기.

art/anim/keys 의 키·중간 프레임을 같은 크기 캔버스에 발 위치(바닥 중앙)로 맞춰
art/anim/web/frames/ 에 저장하고, art/anim/web/index.html 을 만든다.
"""
import json
import time
import os
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KEYS = os.path.join(ROOT, "art", "anim", "keys")
MASTER = os.path.join(ROOT, "art", "final", "carrot_master.png")
OUT = os.path.join(ROOT, "art", "anim", "web")
FR = os.path.join(OUT, "frames")
SIZE = 512
# 땅에서 올라오는 중간 자세는 머리가 스탠딩보다 낮아야 올라오는 느낌이 난다
DROP = {}
# 머리 폭 자동 측정이 안 맞는 프레임은 눈으로 맞춘 배율을 쓴다(스탠딩 머리 크기 기준)
SCALE = {"m05_harvest": 0.92, "m05b_harvest": 0.89, "k05_harvest": 0.97, "h01_notice": 0.98, "h03_popout": 0.85}

# 모션 = (이름, 설명, [(파일, 보여줄 시간 ms, 바닥 맞춤 여부)])
MOTIONS = [
    ("start", "앱 시작 · 땅에서 쑤욱", [
        ("e00_sprout", 80, True, "scaleY(0.3)"), ("e00_sprout", 90, True, "scaleY(1.15)"),
        ("e00_sprout", 80, True, "scaleY(0.92)"), ("e00_sprout", 700, True),
        ("e01_cat", 90, True, "scaleY(0.25)"), ("e01_cat", 110, True, "scaleY(1.22)"),
        ("e01_cat", 100, True, "scaleY(0.86)"), ("e01_cat", 90, True, "scaleY(1.07)"),
        ("e01_cat", 80, True, "scaleY(0.97)"), ("e01_cat", 300, True),
        ("e01_cat_L", 82, True), ("e01_cat", 62, True), ("e01_cat_R", 82, True), ("e01_cat", 62, True),
        ("e01_cat_L", 82, True), ("e01_cat", 62, True), ("e01_cat_R", 82, True), ("e01_cat", 200, True),
        ("e01_cat_in", 220, True), ("e02_blink", 140, True),
        ("e03_hands", 300, True), ("e04_knee", 280, True), ("e05_rise", 240, True),
        ("master", 1600, True)]),
    ("idle", "대기 · 눈 깜빡임", [("master", 1600, True), ("k01_blink", 160, True)]),
    ("snack", "사탕수수 한입 (대기 중 무작위)", [
        ("master", 500, True), ("m03a_snack", 160, True), ("m03_snack", 220, True), ("m03b_snack", 220, True),
        ("k03_snack", 220, True), ("k03b_chew", 180, True), ("k03_snack", 180, True), ("k03b_chew", 180, True),
        ("k03_snack", 180, True), ("k03b_chew", 300, True), ("m03b_snack", 200, True), ("m03_snack", 200, True),
        ("m03a_snack", 160, True)]),
    ("pull", "볼 당김 (길게 눌러 끌기)", [
        ("master", 400, True), ("p2_open", 160, True), ("p2_lift", 140, True),
        ("p2_flailA", 130, True), ("p2_flailM", 100, True), ("p2_flailB", 130, True), ("p2_flailM", 100, True),
        ("p2_flailA", 130, True), ("p2_flailM", 100, True), ("p2_flailB", 130, True), ("p2_flailM", 100, True),
        ("p2_flailA", 130, True), ("p2_lift", 140, True), ("p2_open", 160, True)]),
    ("water", "물뿌리개로 물 주기 (대기 중 무작위)", [
        ("master", 500, True), ("i01a_water", 260, True), ("i01_water", 300, True), ("i01b_water", 300, True),
        ("i01_water", 300, True), ("i01b_water", 300, True), ("i01a_water", 260, True)]),
    ("rub", "사탕수수 볼 비비기 (대기 중 무작위)", [
        ("master", 500, True), ("i02a_rub", 300, True), ("i02_rub", 1200, True), ("i02a_rub", 300, True)]),
    ("harvest", "오늘의 수확 (한 번 터치)", [
        ("master", 500, True), ("h01_notice", 220, True), ("m05_harvest", 240, True), ("m05b_harvest", 300, True),
        ("h03_popout", 220, True), ("k05_harvest", 1400, True)]),
    ("invite", "정원 초대 (두 번 터치 메뉴)", [
        ("master", 500, True), ("m06_invite", 160, True), ("m06b_invite", 160, True),
        ("k06_invite", 1100, True), ("m06b_invite", 160, True), ("m06_invite", 160, True)]),
    ("weed", "잡초 발견 → 손으로 뽑기", [
        ("master", 500, True), ("w00_notice", 220, True), ("w01_find", 800, True),
        ("w01b_bend", 220, True), ("w02_grab", 400, True), ("w03_pull", 260, True), ("w03b_popped", 220, True), ("w04_out", 1000, True), ("w03b_popped", 200, True)]),
    ("exit", "퇴장 (앱 끌 때)", [
        ("master", 400, True), ("e05_rise", 200, True), ("e04_knee", 200, True), ("e03_hands", 200, True),
        ("e01_cat_in", 200, True), ("e02_blink", 120, True), ("e01_cat", 160, True),
        ("e01_cat", 70, True, "scaleY(1.12)"), ("e01_cat", 70, True, "scaleY(0.6)"), ("e01_cat", 70, True, "scaleY(0.25)"),
        ("e00_sprout", 300, True), ("e00_sprout", 70, True, "scaleY(1.15)"), ("e00_sprout", 70, True, "scaleY(0.4)"),
        ("e00_sprout", 60, True, "scaleY(0.05)")]),
]


def load(name):
    if name == "master":
        return Image.open(MASTER).convert("RGB")
    p = os.path.join(KEYS, name + ".png")
    return Image.open(p).convert("RGB") if os.path.exists(p) else None


def _wh(im):
    px = im.load()
    for y in range(im.height):
        for x in range(im.width):
            r, g, b = px[x, y]
            # 무채색(회색끼) 밝은 점만 흰색으로. 밝은 피부색(살구빛)은 건드리지 않는다.
            if min(r, g, b) > 228 and max(r, g, b) - min(r, g, b) <= 8:
                px[x, y] = (255, 255, 255)
    return im


def head_w(im):
    """머리(주황 머리카락) 윗부분 폭. 프레임마다 그림 크기가 달라 스탠딩에 맞출 때 쓴다."""
    px = im.load()
    isor = lambda c: c[0] > 225 and 95 < c[1] < 175 and c[2] < 90
    top = next((y for y in range(0, im.height, 2)
                if sum(1 for x in range(0, im.width, 3) if isor(px[x, y])) >= 20), None)
    if top is None:
        return None
    ws = []
    for y in range(top, min(top + 200, im.height), 4):
        xs = [x for x in range(0, im.width, 2) if isor(px[x, y])]
        if xs:
            ws.append(max(xs) - min(xs))
    return max(ws) if ws else None


def bbox(im):
    g = im.convert("L").point(lambda v: 255 if v < 235 else 0)
    return g.getbbox()


def main():
    os.makedirs(FR, exist_ok=True)
    m = load("master").resize((1254, 1254))
    mb = bbox(m)
    m_cx, m_bot = (mb[0] + mb[2]) // 2, mb[3]
    m_head = head_w(m)
    data, done = [], {}
    for mid, title, seq in MOTIONS:
        frames = []
        for item in seq:
            name, ms, ground = item[:3]
            tf = item[3] if len(item) > 3 else ""
            if name not in done:
                im = load(name)
                if im is None:
                    continue
                im = _wh(im.resize((1254, 1254)))
                hw = head_w(im)
                k = SCALE.get(name) or (m_head / hw if (name != "master" and hw and 0.6 < m_head / hw < 1.7) else 1)
                if abs(k - 1) > 0.02:
                    b0 = bbox(im)
                    big = im.resize((round(1254 * k), round(1254 * k)), Image.NEAREST)
                    cx, by = (b0[0] + b0[2]) / 2 * k, b0[3] * k
                    im = Image.new("RGB", (1254, 1254), "white")
                    im.paste(big, (round((b0[0] + b0[2]) / 2 - cx), round(b0[3] - by)))
                canvas = Image.new("RGB", (1254, 1254), "white")
                b = bbox(im)
                if ground and name != "master":
                    dx = m_cx - (b[0] + b[2]) // 2
                    dy = m_bot - b[3]
                else:
                    dx = dy = 0
                canvas.paste(im, (dx, dy + DROP.get(name, 0)))
                canvas.resize((SIZE, SIZE), Image.LANCZOS).save(os.path.join(FR, name + ".png"))
                done[name] = True
            frames.append({"src": "frames/" + name + ".png?v=" + str(int(time.time())), "ms": ms, "name": name, "tf": tf})
        data.append({"id": mid, "title": title, "frames": frames})
    html = open(os.path.join(os.path.dirname(__file__), "viewer_template.html"), encoding="utf-8").read()
    html = html.replace("__DATA__", json.dumps(data, ensure_ascii=False))
    open(os.path.join(OUT, "index.html"), "w", encoding="utf-8").write(html)
    print("frames:", sorted(done), "->", OUT)


if __name__ == "__main__":
    main()
