"""캬롯 모션 미리보기 페이지 만들기.

art/anim/keys 의 키·중간 프레임을 같은 크기 캔버스에 발 위치(바닥 중앙)로 맞춰
art/anim/web/frames/ 에 저장하고, art/anim/web/index.html 을 만든다.
"""
import json
import os
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KEYS = os.path.join(ROOT, "art", "anim", "keys")
MASTER = os.path.join(ROOT, "art", "final", "carrot_master.png")
OUT = os.path.join(ROOT, "art", "anim", "web")
FR = os.path.join(OUT, "frames")
SIZE = 512

# 모션 = (이름, 설명, [(파일, 보여줄 시간 ms, 바닥 맞춤 여부)])
MOTIONS = [
    ("start", "앱 시작 · 땅에서 쑤욱", [
        ("e00_sprout", 80, True, "scaleY(0.3)"), ("e00_sprout", 90, True, "scaleY(1.15)"),
        ("e00_sprout", 80, True, "scaleY(0.92)"), ("e00_sprout", 700, True),
        ("e01_cat", 90, True, "scaleY(0.25)"), ("e01_cat", 110, True, "scaleY(1.22)"),
        ("e01_cat", 100, True, "scaleY(0.86)"), ("e01_cat", 90, True, "scaleY(1.07)"),
        ("e01_cat", 80, True, "scaleY(0.97)"), ("e01_cat", 300, True),
        ("e01_cat_L", 160, True), ("e01_cat", 120, True), ("e01_cat_R", 160, True), ("e01_cat", 120, True),
        ("e01_cat_L", 160, True), ("e01_cat", 120, True), ("e01_cat_R", 160, True), ("e01_cat", 400, True),
        ("e03_hands", 320, True), ("e04_knee", 320, True), ("e05_rise", 260, True),
        ("master", 1600, True)]),
    ("idle", "대기 · 눈 깜빡임", [("master", 1600, True), ("k01_blink", 160, True)]),
    ("snack", "사탕수수 한입 (대기 중 무작위)", [
        ("master", 500, True), ("m03_snack", 220, True), ("k03_snack", 1200, True),
        ("m03_snack", 220, True)]),
    ("pull", "볼 당김 (길게 눌러 끌기)", [
        ("master", 400, True), ("m04a_pull", 140, True), ("m04_pull", 160, False), ("k04_pull", 900, False),
        ("m04_pull", 160, False), ("m04a_pull", 140, True)]),
    ("harvest", "오늘의 수확 (한 번 터치)", [
        ("master", 500, True), ("m05_harvest", 300, True), ("k05_harvest", 1200, True),
        ("m05_harvest", 200, True)]),
    ("invite", "정원 초대 (두 번 터치 메뉴)", [
        ("master", 500, True), ("m06_invite", 180, True), ("k06_invite", 1100, True),
        ("m06_invite", 180, True)]),
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
            if r > 228 and g > 228 and b > 228:
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
                if name != "master" and hw and 0.75 < m_head / hw < 1.3 and abs(m_head / hw - 1) > 0.02:
                    k = m_head / hw
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
                canvas.paste(im, (dx, dy))
                canvas.resize((SIZE, SIZE), Image.LANCZOS).save(os.path.join(FR, name + ".png"))
                done[name] = True
            frames.append({"src": "frames/" + name + ".png", "ms": ms, "name": name, "tf": tf})
        data.append({"id": mid, "title": title, "frames": frames})
    html = open(os.path.join(os.path.dirname(__file__), "viewer_template.html"), encoding="utf-8").read()
    html = html.replace("__DATA__", json.dumps(data, ensure_ascii=False))
    open(os.path.join(OUT, "index.html"), "w", encoding="utf-8").write(html)
    print("frames:", sorted(done), "->", OUT)


if __name__ == "__main__":
    main()
