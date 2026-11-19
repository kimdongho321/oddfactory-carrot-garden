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
    ("idle", "대기 · 눈 깜빡임", [("master", 1600, True), ("k01_blink", 160, True)]),
    ("snack", "사탕수수 한입 (대기 중 무작위)", [
        ("master", 500, True), ("m03_snack", 220, True), ("k03_snack", 1200, True),
        ("m03_snack", 220, True)]),
    ("pull", "볼 당김 (길게 눌러 끌기)", [
        ("master", 500, True), ("m04_pull", 160, False), ("k04_pull", 900, False),
        ("m04_pull", 160, False)]),
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


def bbox(im):
    g = im.convert("L").point(lambda v: 255 if v < 235 else 0)
    return g.getbbox()


def main():
    os.makedirs(FR, exist_ok=True)
    m = load("master").resize((1254, 1254))
    mb = bbox(m)
    m_cx, m_bot = (mb[0] + mb[2]) // 2, mb[3]
    data, done = [], {}
    for mid, title, seq in MOTIONS:
        frames = []
        for name, ms, ground in seq:
            if name not in done:
                im = load(name)
                if im is None:
                    continue
                im = im.resize((1254, 1254))
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
            frames.append({"src": "frames/" + name + ".png", "ms": ms, "name": name})
        data.append({"id": mid, "title": title, "frames": frames})
    html = open(os.path.join(os.path.dirname(__file__), "viewer_template.html"), encoding="utf-8").read()
    html = html.replace("__DATA__", json.dumps(data, ensure_ascii=False))
    open(os.path.join(OUT, "index.html"), "w", encoding="utf-8").write(html)
    print("frames:", sorted(done), "->", OUT)


if __name__ == "__main__":
    main()
