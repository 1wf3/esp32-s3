"""After capture_startup_preview.cjs --gif, assemble native-size frames."""
from pathlib import Path
from PIL import Image

root = Path(__file__).resolve().parents[4]
frames = [Image.open(path).convert("RGB") for path in sorted((root / "build/startup_gif").glob("*.png"))]
if len(frames) != 67:
    raise RuntimeError("Run capture_startup_preview.cjs --gif first (expected 67 frames)")
frames[0].save(root / "output/previews/开机与等待连接动画.gif", save_all=True,
               append_images=frames[1:], duration=[60] * 47 + [75] * 20, loop=0, disposal=2)
print("GIF exported: 47 boot frames + 20 waiting frames")
