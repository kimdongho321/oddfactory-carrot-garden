"""수레 모션 프레임 내보내기(GPT 시트 → 낱장, 투명 배경, 같은 캔버스).

캐릭터 머리(주황 머리카락) 가운데를 기준점으로 맞추고, 바닥(발·바퀴)을 같은 높이에 둔다.
크기는 스탠딩 머리 폭에 맞춘다. 수레까지 들어가므로 일반 프레임보다 넓은 캔버스를 쓰고,
기준점(footX, footY)과 수레 짐칸 위치(bin)를 frames/barrow.json 에 적는다.
사용: python export_barrow.py <시트 경로> <프레임 이름들(쉼표)> [<시트> <이름들>] ...
"""
import json
import os
import sys

from PIL import Image

import build_web as bw
import export_overlay as eo
import slice_garden as sg

OUT = os.path.join(bw.ROOT, "android", "app", "src", "main", "assets", "frames")
H = 320


def strip_ground(im):
    """시트마다 그려진 가는 바닥선(가로로 긴 진한 줄)을 지운다."""
    px = im.load(); w, h = im.size
    for y in range(h - 1, max(0, h - 40), -1):
        dark = sum(1 for x in range(w) if px[x, y][3] > 0 and sum(px[x, y][:3]) < 330)
        if dark > w * 0.55:
            for x in range(w): px[x, y] = (0, 0, 0, 0)
    return im


def strip_line(rgb):
    """배경 제거 전에: 시트 아래쪽의 가는 바닥선(진한 가로줄)을 흰색으로 지운다. 선에 갇힌 흰 바닥 자국도 같이 지워진다."""
    px = rgb.load(); w, h = rgb.size
    cand = []
    for y in range(int(h * 0.5), h):
        run = best = 0
        for x in range(w):  # 끊김 없이 이어진 진한 픽셀의 가장 긴 길이
            run = run + 1 if sum(px[x, y]) < 360 else 0
            best = max(best, run)
        if best > w * 0.12:
            cand.append(y)
    if cand:  # 바닥선은 그중 가장 아래 묶음뿐(수레 테두리 같은 위쪽 곧은 선은 남긴다)
        y1 = max(cand); y0 = y1
        while y0 - 1 in cand: y0 -= 1
        for y in range(max(0, y0 - 3), min(h, y1 + 4)):
            for x in range(w): px[x, y] = (255, 255, 255)
    return rgb


def head(im):
    """머리카락(주황) 가로 가운데와 폭(위쪽 40%에서)."""
    px = im.load(); w, h = im.size
    xs = [x for y in range(0, int(h * 0.45), 2) for x in range(0, w, 2)
          if px[x, y][3] > 0 and px[x, y][0] > 225 and 95 < px[x, y][1] < 175 and px[x, y][2] < 90]
    xs.sort()
    lo, hi = xs[int(len(xs) * 0.03)], xs[int(len(xs) * 0.97)]
    return (lo + hi) / 2, hi - lo


def main(args):
    m = eo.load("master").resize((1254, 1254))
    m_head = bw.head_w(m)
    m_bb = bw.bbox(m)
    char_h = m_bb[3] - m_bb[1]                    # 스탠딩 키(px, 1254 기준)
    frames, sheet_of = {}, {}
    for si, (sheet, names) in enumerate(zip(args[0::2], args[1::2])):
        names = names.split(",")
        im = eo.clear_bg(strip_line(Image.open(sheet).convert("RGB")))
        a = im.getchannel("A").load(); W, Hh = im.size
        cols = [any(a[x, y] for y in range(0, Hh, 2)) for x in range(W)]
        parts = sg.groups(cols, len(names))
        if len(parts) < len(names):  # 그림끼리 붙어 있으면 같은 폭으로 나눔
            parts = [(i * W // len(names), (i + 1) * W // len(names) - 1) for i in range(len(names))]
        for n, (x0, x1) in zip(names, parts):
            spr = im.crop((x0, 0, x1 + 1, Hh))
            spr = spr.crop(spr.getbbox())
            # 옆 그림에서 잘려 들어온 부스러기 세로줄(본체와 빈 칸으로 떨어진 것) 버리기
            al = spr.getchannel("A").load()
            occ = [any(al[x, y] for y in range(spr.height)) for x in range(spr.width)]
            runs, cur = [], None
            for x, on in enumerate(occ):
                if on: cur = [x, x] if cur is None or x - cur[1] > 10 else [cur[0], x]
                elif cur is not None and x - cur[1] > 10: runs.append(cur); cur = None
            if cur: runs.append(cur)
            if len(runs) > 1:
                big = max(runs, key=lambda r: r[1] - r[0])
                spr = spr.crop((big[0], 0, big[1] + 1, spr.height)); spr = spr.crop(spr.getbbox())
            frames[n] = spr; sheet_of[n] = si
    # 크기: 시트마다 가장 키 큰(서 있는) 프레임의 키를 스탠딩 키(일반 프레임 기준)에 맞춘다
    meta = json.load(open(os.path.join(OUT, "meta.json")))
    target = meta["footY"] - meta["bodyTop"]
    tallest = {}
    for n, spr in frames.items(): tallest[sheet_of[n]] = max(tallest.get(sheet_of[n], 0), spr.height)
    scaled = {}
    for n, spr in frames.items():
        hx, hw = head(spr)
        k = target / tallest[sheet_of[n]]
        s = spr.resize((max(1, round(spr.width * k)), max(1, round(spr.height * k))), Image.NEAREST)
        scaled[n] = (s, hx * k)
    left = max(hx for s, hx in scaled.values()) + 6
    right = max(s.width - hx for s, hx in scaled.values()) + 6
    top = max(s.height for s, hx in scaled.values()) + 6
    cw, ch = round(left + right), round(top)
    for n, (s, hx) in scaled.items():
        c = Image.new("RGBA", (cw, ch), (0, 0, 0, 0))
        c.alpha_composite(s, (round(left - hx), ch - s.height))
        c.save(os.path.join(OUT, n + ".png"))
    meta = {"w": cw, "h": ch, "footX": round(left), "footY": ch - 2, "frames": list(scaled)}
    json.dump(meta, open(os.path.join(OUT, "barrow.json"), "w"), indent=1)
    print(meta)


if __name__ == "__main__":
    main(sys.argv[1:])
