#!/usr/bin/env python3
"""从 icon/icon_1024.png 生成 Windows app.ico（多尺寸）。"""
from PIL import Image
import os

SRC = os.path.join(os.path.dirname(__file__), "..", "icon", "icon_1024.png")
OUT = os.path.join(os.path.dirname(__file__), "..", "app.ico")

img = Image.open(SRC).convert("RGBA")
img.save(OUT, format="ICO", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
print("written", OUT)
