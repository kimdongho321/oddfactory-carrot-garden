"""GPT로 그린 작물 시트·팻말 시트를 낱장 PNG(투명 배경)로 자른다 → android/app/src/main/assets/garden/."""
import json, os, sys
from PIL import Image
import export_overlay as eo

OUT = os.path.join(eo.bw.ROOT, "android", "app", "src", "main", "assets", "garden")


def groups(mask_cols, n, min_gap=6):
    """비어 있지 않은 세로줄 구간을 찾아, 가장 넓은 빈틈 n-1개로 나눠 n개 묶음(start, end)으로."""
    runs, cur = [], None
    for x, on in enumerate(mask_cols):
        if on:
            cur = [x, x] if cur is None else [cur[0], x]
        elif cur is not None:
            runs.append(cur); cur = None
    if cur: runs.append(cur)
    gaps = sorted(range(len(runs) - 1), key=lambda i: runs[i + 1][0] - runs[i][1], reverse=True)[:n - 1]
    cuts = sorted(gaps)
    out, s0 = [], runs[0][0]
    for i in cuts:
        out.append((s0, runs[i][1])); s0 = runs[i + 1][0]
    out.append((s0, runs[-1][1]))
    return out


def sheet(path, rows, names, cols=4):
    im = eo.clear_bg(Image.open(path).convert("RGB"))
    W, H = im.size
    a = im.getchannel("A").load()
    # 줄 나누기: 빈 가로줄로
    rowcols = [any(a[x, y] for x in range(0, W, 2)) for y in range(H)]
    bands = groups(rowcols, rows)
    for r, (y0, y1) in enumerate(bands):
        band = im.crop((0, y0, W, y1 + 1)); b = band.getchannel("A").load()
        colmask = [any(b[x, y] for y in range(0, y1 - y0 + 1, 2)) for x in range(W)]
        parts = groups(colmask, cols)
        crops = []
        for (x0, x1) in parts:
            spr = band.crop((x0, 0, x1 + 1, y1 - y0 + 1)); bb = spr.getbbox(); crops.append((x0, bb))
        top = min(bb[1] for _, bb in crops)
        for c, (x0, bb) in enumerate(crops):
            band.crop((x0 + bb[0], top, x0 + bb[2], bb[3])).save(os.path.join(OUT, f"{names[r]}_{c}.png"))


def signs(path):
    im = eo.clear_bg(Image.open(path).convert("RGB"))
    W, H = im.size
    meta = {}
    for i, n in enumerate("abc"):
        part = im.crop((i * W // 3, 0, (i + 1) * W // 3, H)); part = part.crop(part.getbbox())
        part.save(os.path.join(OUT, f"sign_{n}.png"))
        # 아이콘 자리(밝은 크림색 큰 네모) 찾기
        px = part.load(); w, h = part.size
        cream = lambda p: p[3] > 0 and p[0] > 235 and p[1] > 222 and 170 < p[2] < 235
        ys = [y for y in range(h) if sum(1 for x in range(w) if cream(px[x, y])) > w * 0.35]
        # 가장 긴 연속 구간 = 아이콘 판
        best, cur = (0, 0), None
        for y in ys:
            if cur and y == cur[1] + 1: cur = (cur[0], y)
            else: cur = (y, y)
            if cur[1] - cur[0] > best[1] - best[0]: best = cur
        y0, y1 = best; mid = (y0 + y1) // 2
        xs = [x for x in range(w) if cream(px[x, mid])]
        meta[n] = {"w": w, "h": h, "panel": [min(xs) / w, y0 / h, max(xs) / w, y1 / h]}
    json.dump(meta, open(os.path.join(OUT, "signs.json"), "w"), indent=1)
    print(meta)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    a, b, s = sys.argv[1:4]
    sheet(a, 4, ["pumpkin", "carrot", "corn", "tomato"])
    sheet(b, 3, ["cabbage", "potato", "radish"])
    signs(s)
