"""빈 수레 그림(art/anim/keys/barrow_empty.png) → assets/barrow.png·barrow_front.png·barrow.json.
수레 기준점(손잡이 끝·바닥·바퀴축·짐칸)은 원본 1254px 그림에서 잰 값. 밀 때 손 위치는 push_a 프레임에서 잰다."""
import json, os
from PIL import Image
import build_web as bw, export_overlay as eo

A = os.path.join(bw.ROOT, "android", "app", "src", "main", "assets")
src = eo.clear_bg(Image.open(os.path.join(bw.KEYS, "barrow_empty.png")).convert("RGB"))
bb = src.getbbox(); im = src.crop(bb); ox, oy = bb[0], bb[1]
P = {"gripX": 200, "gripY": 522, "groundY": 937, "axleX": 975, "binL": 470, "binR": 1040, "rimY": 450, "frontY": 552}
m = {k: (v - ox if k in ("gripX", "axleX", "binL", "binR") else v - oy) for k, v in P.items()}
m["gripH"] = m["groundY"] - m["gripY"]
im.save(os.path.join(A, "barrow.png"))
front = im.copy(); px = front.load()
for y in range(front.height):
    for x in range(front.width):
        if y < m["frontY"]: px[x, y] = (0, 0, 0, 0)
front.save(os.path.join(A, "barrow_front.png"))
# 손 위치(밀기 프레임): 가운데 높이 띠에서 가장 오른쪽 픽셀
f = Image.open(os.path.join(A, "frames", "push_a.png")); meta = json.load(open(os.path.join(A, "frames", "meta.json")))
a = f.getchannel("A").load(); H = f.height
best = (0, 0)
for y in range(int(H * 0.35), int(H * 0.7)):
    for x in range(f.width - 1, 0, -1):
        if a[x, y] > 0:
            if x > best[0]: best = (x, y)
            break
m["handX"] = best[0] - meta["footX"] - 4; m["handH"] = meta["footY"] - best[1]
json.dump(m, open(os.path.join(A, "barrow.json"), "w"), indent=1); print(m, im.size)
