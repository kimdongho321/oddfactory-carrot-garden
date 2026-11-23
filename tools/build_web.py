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
SCALE = {"fall_0": 1.0, "fall_1": 1.0, "fall_2": 1.0, "m05_harvest": 0.92, "m05b_harvest": 0.89, "k05_harvest": 0.97, "h01_notice": 0.98, "h03_popout": 0.85, "i02a_rub": 1.0, "i02_rub": 1.0}

# 모션 = (이름, 설명, [(파일, 보여줄 시간 ms, 바닥 맞춤 여부)])
# 앱(Stage.java)에 실제로 들어간 모션만. "프레임@0.3" = 세로 배율, "프레임^0.1" = 키의 10%만큼 위로
def C(*a):
    out = []
    for i in range(0, len(a), 2):
        n, ms = a[i], a[i + 1]; tf = ""
        if "^" in n: n, d = n.split("^"); tf = f"translateY(-{float(d) * 85:.1f}%)"
        if "@" in n: n, sy = n.split("@"); tf = f"scaleY({sy})"
        out.append((n, ms, True, tf) if tf else (n, ms, True))
    return out


ENTER = C("e00_sprout@0.3", 80, "e00_sprout@1.15", 90, "e00_sprout@0.92", 80, "e00_sprout", 700,
          "e01_cat@0.25", 90, "e01_cat@1.22", 110, "e01_cat@0.86", 100, "e01_cat@1.07", 90, "e01_cat@0.97", 80, "e01_cat", 300,
          "e01_cat_L", 82, "e01_cat", 62, "e01_cat_R", 82, "e01_cat", 62, "e01_cat_L", 82, "e01_cat", 62, "e01_cat_R", 82, "e01_cat", 200,
          "e01_cat_in", 220, "e02_blink", 140, "e03_hands", 300, "e04_knee", 280, "e05_rise", 240, "master", 320)
EXIT = C("master", 300, "e05_rise", 200, "e04_knee", 200, "e03_hands", 200, "e01_cat_in", 200, "e02_blink", 120,
         "e01_cat", 160, "e01_cat@1.12", 70, "e01_cat@0.6", 70, "e01_cat@0.25", 70, "e00_sprout", 300, "e00_sprout@1.15", 70,
         "e00_sprout@0.4", 70, "e00_sprout@0.05", 60)
WALK = C("walk_lift_left", 150, "walk_pass_left_to_right", 110, "walk_lift_right", 150, "walk_pass_right_to_left", 110)
CLAP = C("clap_in_v2", 137, "clap_hit_v2", 114, "clap_out_v2", 91, "clap_hit_v2", 114, "clap_out_v2", 91,
         "clap_hit_v2", 114, "clap_out_v2", 143, "master", 171)
WATER = C("i01a_water", 173, "i01_water", 200, "i01b_water", 200, "i01_water", 200, "i01b_water", 200,
          "i01_water", 200, "i01b_water", 200, "i01a_water", 173, "master", 200)
PULL = C("master", 300, "pull_right_mid", 69, "pull_right_mid2", 80, "pull_right_peak", 91) +        C("pull_right_struggle_a", 86, "pull_right_struggle_b", 86) * 4 + C("pull_right_peak", 80, "pull_right_mid", 69, "master", 114)
HARVEST = C("master", 200, "h01_notice", 220, "m05_harvest", 240, "m05b_harvest_v2", 300, "h03_popout", 220, "k05_harvest", 1400, "master", 300)
INVITE = C("master", 150, "m06_invite", 160, "m06b_invite", 160, "k06_invite", 1100, "m06b_invite", 160, "m06_invite", 160, "master", 200)
DELETE = C("master", 300, "wn_lower1", 160, "wn_lower2", 150, "wn_grab_v2", 220) + C("wn_grab_v2", 200, "wn_tug", 260) * 3 +          C("wn_pop", 120, "wn_pop^0.05", 80, "fall_0^0.13", 90, "fall_0^0.16", 90, "fall_0^0.09", 80,
           "fall_1@0.92", 70, "fall_1", 450, "fall_2", 900, "e05_rise", 220, "master", 200)

MOTIONS = [
    ("start", "앱 시작 · 땅에서 솟아오름", ENTER),
    ("idle", "대기 · 눈 깜빡임", C("master", 1600, "k01_blink", 160)),
    ("walk", "걷기 (홈 화면 산책)", WALK * 3),
    ("clap", "박수 (산책 중 무작위)", CLAP),
    ("water", "물주기 (산책 중 무작위)", WATER),
    ("tickle", "문지르기 → 간지럼(꾹 참고 움찔)", C("master", 300) + C("tk_squirm_a", 150, "tk_squirm_b", 150) * 6 + C("tk_squirm_a", 160, "k01_blink", 140, "master", 400)),
    ("invite", "텃밭 열기 → 정원 초대", INVITE),
    ("pull", "꾹 눌러 끌기 → 볼 당김", PULL),
    ("delete_fall", "앱 하나 삭제 → 잡초 뽑고 엉덩방아", DELETE),
    ("exit", "종료 → 땅으로 쏙", EXIT),
]

def load(name):
    if name == "master":
        return Image.open(MASTER).convert("RGB")
    p = os.path.join(KEYS, name + ".png")
    if not os.path.exists(p):
        return None
    # 투명 배경 그림은 흰 바탕에 얹어서(그냥 RGB로 바꾸면 투명 부분이 검게 됨)
    src = Image.open(p).convert("RGBA")
    white = Image.new("RGBA", src.size, (255, 255, 255, 255))
    white.alpha_composite(src)
    return white.convert("RGB")


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


# 흙먼지·팔 때문에 외곽 상자가 흔들리는 프레임: 그린 자리 그대로 두고, 머리 가운데만 스탠딩에 맞춘다
KEEP_POS = {"fall_0", "fall_1", "fall_2"}


def head_cx(im):
    px = im.load()
    xs = [x for y in range(0, int(im.height * 0.45), 3) for x in range(0, im.width, 3)
          if px[x, y][0] > 225 and 95 < px[x, y][1] < 175 and px[x, y][2] < 90]
    xs.sort()
    return (xs[len(xs) // 10] + xs[len(xs) * 9 // 10]) / 2 if xs else im.width / 2


def bbox(im):
    g = im.convert("L").point(lambda v: 255 if v < 235 else 0)
    return g.getbbox()


def main():
    os.makedirs(FR, exist_ok=True)
    m = load("master").resize((1254, 1254))
    mb = bbox(m)
    m_cx, m_bot = (mb[0] + mb[2]) // 2, mb[3]
    m_head = head_w(m)
    m_hx = head_cx(m)
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
                if name in KEEP_POS:
                    dx = round(m_hx - head_cx(im)); dy = 0
                elif ground and name != "master":
                    dx = m_cx - (b[0] + b[2]) // 2
                    dy = m_bot - b[3]
                else:
                    dx = dy = 0
                canvas.paste(im, (dx, dy + DROP.get(name, 0)))
                canvas.resize((SIZE, SIZE), Image.LANCZOS).save(os.path.join(FR, name + ".png"))
                done[name] = True
            frames.append({"src": "frames/" + name + ".png?v=" + str(int(time.time())), "ms": ms, "name": name, "tf": tf})
        data.append({"id": mid, "title": title, "frames": frames})
    try:  # 앱 자산으로 만드는 추가 모션(수레·햇님이)
        import web_extra
        data.extend(web_extra.build(FR, str(int(time.time()))))
    except Exception as e:
        print("web_extra 실패:", e)
    html = open(os.path.join(os.path.dirname(__file__), "viewer_template.html"), encoding="utf-8").read()
    html = html.replace("__DATA__", json.dumps(data, ensure_ascii=False))
    open(os.path.join(OUT, "index.html"), "w", encoding="utf-8").write(html)
    print("frames:", sorted(done), "->", OUT)


if __name__ == "__main__":
    main()
