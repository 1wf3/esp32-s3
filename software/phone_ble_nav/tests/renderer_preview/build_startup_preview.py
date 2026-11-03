"""Generate a self-contained preview using the actual firmware renderer."""
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[4]
EXE = ROOT / "software/phone_ble_nav/tests/startup_tests.exe"
OUTPUT = ROOT / "output/previews/开机与手机连接预览.html"


def render(mode, at=0):
    return subprocess.check_output([str(EXE), mode, str(at)]).decode("utf-8")


frames = {
    "boot": [render("boot", at) for at in range(0, 2800, 60)],
    "idle": [render("idle", at) for at in range(0, 1500, 75)],
    **{mode: render(mode) for mode in ("pairing", "handshake", "ready", "lost")},
}
page = r'''<!doctype html>
<html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>导航屏 · 开机与手机连接预览</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#f3f5f6;color:#172431;font-family:"Microsoft YaHei",sans-serif}
main{max-width:1320px;margin:auto;padding:44px 38px}header{display:flex;justify-content:space-between;align-items:end;margin-bottom:32px}
.eyebrow{font-size:12px;letter-spacing:3px;color:#598099;margin-bottom:10px}h1{margin:0;font-size:32px;font-weight:600}header p{margin:0;color:#6d7d89;font-size:13px}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:28px}.panel{min-width:0}h2{font-size:17px;margin:0 0 16px;font-weight:600}h2 span{color:#6995ac;font-size:13px;margin-right:10px}
.bezel{background:linear-gradient(145deg,#57616a,#0d1319 12%,#252d34 85%,#67737c);padding:12px;border-radius:22px;box-shadow:0 12px 24px #1524311c,inset 0 0 0 1px #89939b}
.screen{overflow:hidden;border-radius:12px;background:#000408;aspect-ratio:3/2;border:3px solid #090d11}.screen svg{display:block;width:100%;height:100%}
.controls{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:18px 0 0}.caption{font-size:13px;color:#687b88;line-height:1.8}
button,select{font-family:inherit;font-size:13px;border:1px solid #c7d6de;background:#fff;color:#244758;padding:8px 14px;border-radius:7px;cursor:pointer}button:hover{background:#e8f6ff}
.stages{margin-top:38px;display:grid;grid-template-columns:repeat(3,1fr);gap:22px}.stage{background:#e7ecef;border:1px solid #dce3e8;padding:12px;border-radius:12px}.stage svg{width:100%;height:auto;display:block;border-radius:6px}.stage p{font-size:13px;margin:10px 0 0;color:#566a78}.stage b{color:#223d4d;margin-right:8px;font-weight:600}
footer{margin-top:28px;color:#7a8890;font-size:12px;line-height:1.8;border-top:1px solid #dce4e8;padding-top:16px}
@media(max-width:850px){main{padding:24px 18px}.grid{grid-template-columns:1fr}header{display:block}header p{margin-top:14px}.stages{gap:10px}h1{font-size:26px}}
</style><main>
<header><div><div class="eyebrow">NAVIGATION DISPLAY / STARTUP</div><h1>开机与手机连接界面</h1></div><p>480 × 320 · 固件同源绘制 · 离线动态预览</p></header>
<div class="grid">
<section class="panel"><h2><span>01</span>路线唤醒</h2><div class="bezel"><div id="boot" class="screen"></div></div><div class="controls"><div class="caption" id="phase">点亮 → 路线延展 → 即刻出发 → 等待连接</div><button id="replay">重播开机动画</button></div></section>
<section class="panel"><h2><span>02</span>等待手机连接</h2><div class="bezel"><div id="idle" class="screen"></div></div><div class="controls"><div class="caption">静态示意图 + 三点呼吸动效</div><select id="state" aria-label="预览连接状态"><option value="idle">等待连接</option><option value="pairing">蓝牙配对中</option><option value="handshake">协议握手中</option><option value="ready">已连接待导航</option><option value="lost">连接失活</option></select></div></section>
</div>
<div class="stages"><div class="stage"><div id="step1"></div><p><b>01 点亮</b>深色路网 / 导航箭头</p></div><div class="stage"><div id="step2"></div><p><b>02 路线延展</b>浅蓝路线逐段点亮</p></div><div class="stage"><div id="step3"></div><p><b>03 导航就绪</b>即刻出发 / 完成进度</p></div></div>
<footer>设备名称使用实际广播名 MOTO GPS TFT。开机动画约 2.8 秒，手机提前连接时立即进入连接流程；断开后直接返回等待页。<br>预览来自 PhoneRenderer 的绘图指令；实屏使用固件内置中文字库，字形与浏览器可能略有差异。</footer>
</main><script>
const frames=__FRAMES__;
let started=performance.now(), mode='idle';const still=new URLSearchParams(location.search).has('still');
const boot=document.getElementById('boot'),idle=document.getElementById('idle');
document.getElementById('step1').innerHTML=frames.boot[2];document.getElementById('step2').innerHTML=frames.boot[19];document.getElementById('step3').innerHTML=frames.boot[38];
function tick(now){const t=still?2280:now-started;boot.innerHTML=t<2800?frames.boot[Math.min(frames.boot.length-1,Math.floor(t/60))]:frames.idle[Math.floor(t/75)%20];let svg=mode==='idle'?frames.idle[Math.floor(now/75)%20]:frames[mode];if(mode==='pairing'||mode==='handshake'||mode==='lost'){const dots=frames.idle[Math.floor(now/75)%20].match(/<rect x='198'[\s\S]*?(?=<text)/)[0];svg=svg.replace(/<rect x='198'[\s\S]*?(?=<text)/,dots);}idle.innerHTML=svg;requestAnimationFrame(tick)}
document.getElementById('replay').onclick=()=>{started=performance.now()};document.getElementById('state').onchange=e=>{mode=e.target.value};requestAnimationFrame(tick);
</script></html>'''
OUTPUT.parent.mkdir(parents=True, exist_ok=True)
OUTPUT.write_text(page.replace("__FRAMES__", json.dumps(frames, ensure_ascii=False)), encoding="utf-8")
for mode, at, name in [("idle", 350, "等待手机连接.svg"), ("boot", 2280, "开机导航就绪.svg")]:
    OUTPUT.with_name(name).write_text(render(mode, at), encoding="utf-8")
print(OUTPUT)
