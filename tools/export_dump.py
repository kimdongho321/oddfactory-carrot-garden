"""수레째 엎기 6프레임(art/anim/keys/dump_sheet.png, 한 줄 6칸 같은 폭) → assets/frames/dump_0..5.png + dump.json.
칸을 같은 폭으로 잘라 GPT가 그린 자리(바퀴 고정)를 그대로 유지. 크기는 1번 프레임 키를 스탠딩 키에 맞춤."""
import json, os
from PIL import Image
import build_web as bw, export_overlay as eo

FR = os.path.join(bw.ROOT, "android", "app", "src", "main", "assets", "frames")
src = eo.clear_bg(Image.open(os.path.join(bw.KEYS, "dump_sheet.png")).convert("RGB"))
W, H = src.size; n = 6; cw0 = W // n
cells = [src.crop((i * cw0, 0, (i + 1) * cw0, H)) for i in range(n)]


def drop_edge_bits(c):
    """칸 경계에 걸려 들어온 옆 그림 부스러기(가장자리에 닿은 작은 덩어리) 지우기."""
    from collections import deque
    a = c.load(); w, h = c.size; seen = set()
    for sx in (0, w - 1):
        for sy in range(h):
            if (sx, sy) in seen or a[sx, sy][3] == 0: continue
            q = deque([(sx, sy)]); seen.add((sx, sy)); comp = []
            while q:
                x, y = q.popleft(); comp.append((x, y))
                for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                    if 0 <= nx < w and 0 <= ny < h and (nx, ny) not in seen and a[nx, ny][3] > 0:
                        seen.add((nx, ny)); q.append((nx, ny))
            if len(comp) < 3000:
                for p in comp: a[p] = (0, 0, 0, 0)
    return c


cells = [drop_edge_bits(c) for c in cells]
union = None
for c in cells:
    b = c.getbbox(); union = b if union is None else (min(union[0], b[0]), min(union[1], b[1]), max(union[2], b[2]), max(union[3], b[3]))
cells = [c.crop(union) for c in cells]
meta = json.load(open(os.path.join(FR, "meta.json")))
b0 = cells[0].getbbox(); k = (meta["footY"] - meta["bodyTop"]) / (b0[3] - b0[1])
cells = [c.resize((round(c.width * k), round(c.height * k)), Image.NEAREST) for c in cells]
for i, c in enumerate(cells): c.save(os.path.join(FR, f"dump_{i}.png"))
# 발 기준점: 1번 프레임 아래쪽 20% 중 가장 왼쪽 덩어리(발) 가운데
f = cells[0]; a = f.getchannel("A").load(); hh = f.height
xs = [x for y in range(int(hh * 0.85), hh) for x in range(int(f.width * 0.45)) if a[x, y] > 0]
foot_x = (min(xs) + max(xs)) / 2 if xs else f.width * 0.2
json.dump({"w": f.width, "h": hh, "footX": foot_x, "footY": hh - 1}, open(os.path.join(FR, "dump.json"), "w"))
print(f.size, foot_x)
