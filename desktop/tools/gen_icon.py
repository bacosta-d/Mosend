#!/usr/bin/env python3
"""生成 Windows .ico（64x64 32bpp BMP/DIB），用于安装包与 exe 图标。"""
import struct

W = H = 64
BG = (26, 115, 232)      # #1A73E8
FG = (255, 255, 255)


def rounded_alpha(x, y, r=14):
    # 圆角矩形内为 255，外部 0（四角做简单圆形裁剪）
    cx = min(x, W - 1 - x)
    cy = min(y, H - 1 - y)
    if cx >= r or cy >= r:
        return 255
    dx = r - cx
    dy = r - cy
    return 255 if dx * dx + dy * dy <= r * r else 0


def in_tri(px, py, a, b, c):
    def sign(p1, p2, p3):
        return (p1[0] - p3[0]) * (p2[1] - p3[1]) - (p2[0] - p3[0]) * (p1[1] - p3[1])
    d1 = sign((px, py), a, b)
    d2 = sign((px, py), b, c)
    d3 = sign((px, py), c, a)
    neg = (d1 < 0) or (d2 < 0) or (d3 < 0)
    pos = (d1 > 0) or (d2 > 0) or (d3 > 0)
    return not (neg and pos)


def pixel(x, y):
    a = rounded_alpha(x, y)
    if a == 0:
        return (0, 0, 0, 0)
    # 上箭头（向右）
    if 16 <= y <= 28 and 12 <= x <= 38:
        return FG + (255,)
    if in_tri(x, y, (38, 12), (52, 22), (38, 32)):
        return FG + (255,)
    # 下箭头（向左）
    if 34 <= y <= 46 and 26 <= x <= 52:
        return FG + (255,)
    if in_tri(x, y, (26, 30), (12, 40), (26, 50)):
        return FG + (255,)
    return BG + (a,)


def bmp_rows():
    # BMP DIB 像素数据：BGRA，自下而上
    out = bytearray()
    for y in range(H - 1, -1, -1):
        for x in range(W):
            r, g, b, a = pixel(x, y)
            out += bytes((b, g, r, a))
    return bytes(out)


def and_mask():
    row_bytes = ((W + 31) // 32) * 4
    out = bytearray()
    for y in range(H - 1, -1, -1):
        row = bytearray(row_bytes)
        for x in range(W):
            r, g, b, a = pixel(x, y)
            if a == 0:
                row[x // 8] |= (1 << (7 - (x % 8)))
        out += row
    return bytes(out)


def bitmap_info_header():
    return struct.pack('<IiiHHIIiiII',
                       40, W, H * 2, 1, 32, 0, 0, 0, 0, 0, 0)


def main():
    pixels = bmp_rows()
    mask = and_mask()
    data = bitmap_info_header() + pixels + mask
    header = struct.pack('<HHH', 0, 1, 1)
    entry = struct.pack('<BBBBHHII', W, H, 0, 0, 1, 32, len(data), 22)
    with open('app.ico', 'wb') as f:
        f.write(header + entry + data)
    print('app.ico', 22 + len(data), 'bytes')


if __name__ == '__main__':
    main()
