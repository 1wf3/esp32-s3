from pathlib import Path
import csv, html, json, math
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib.colors import HexColor
import pypdfium2 as pdfium

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'output/carrier_v01';OUT.mkdir(parents=True,exist_ok=True)
PDF=ROOT/'output/pdf/导航转接底板V0.1_原理图与绘制指南.pdf'
pdfmetrics.registerFont(TTFont('CN','C:/Windows/Fonts/msyh.ttc',subfontIndex=0))
W,H=1191,842
pdf=canvas.Canvas(str(PDF),pagesize=(W,H));pdf.setTitle('导航转接底板 V0.1 - 电气原理图与嘉立创 EDA 绘制指南')
INK='#19332e';MUTED='#65766e';GREEN='#287660';BLUE='#2167aa';RED='#bf413b';ORANGE='#a9620c';BG='#f7f8f3'
pages=[];svg=[]
def line(x1,y1,x2,y2,c=INK,w=1):
    pdf.setStrokeColor(HexColor(c));pdf.setLineWidth(w);pdf.line(x1,H-y1,x2,H-y2)
    svg.append(f'<path d="M{x1},{y1} L{x2},{y2}" stroke="{c}" stroke-width="{w}" fill="none"/>')
def rect(x,y,w,h,fill='#ffffff',stroke=INK,r=0):
    pdf.setFillColor(HexColor(fill));pdf.setStrokeColor(HexColor(stroke));pdf.roundRect(x,H-y-h,w,h,r,fill=1,stroke=1)
    svg.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{fill}" stroke="{stroke}"/>')
def text(x,y,s,size=11,c=INK):
    pdf.setFont('CN',size);pdf.setFillColor(HexColor(c));pdf.drawString(x,H-y-size*.85,s)
    svg.append(f'<text x="{x}" y="{y+size*.85}" fill="{c}" font-size="{size}" font-family="Microsoft YaHei, sans-serif">{html.escape(s)}</text>')
def dot(x,y,c=INK):
    pdf.setFillColor(HexColor(c));pdf.circle(x,H-y,2.4,fill=1,stroke=0);svg.append(f'<circle cx="{x}" cy="{y}" r="2.4" fill="{c}"/>')
def ground(x,y):
    line(x,y,x,y+7);line(x-8,y+7,x+8,y+7);line(x-5,y+11,x+5,y+11);line(x-2,y+15,x+2,y+15)
def resistor(x,y,ref,value,c=BLUE):
    rect(x,y-5,48,10,stroke=c);text(x-1,y-27,ref+' '+value,10,c)
def cap(x,y,ref,value):
    line(x,y,x,y+16,RED);line(x-9,y+16,x+9,y+16);line(x-9,y+22,x+9,y+22);line(x,y+22,x,y+40);ground(x,y+40)
    text(x-25,y+62,ref+' '+value,9)
def begin(title,sub,n):
    global svg
    svg=[f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">']
    rect(0,0,W,H,BG,BG);text(34,22,title,25);text(35,60,sub,11,MUTED)
    line(34,83,1157,83,GREEN,2)
    text(35,815,'电动车导航 / 2026-10-19 / V0.1 电气设计草案；封装未冻结，不可据此直接下单 PCB',9,MUTED)
    text(1080,815,f'{n} / 4',10,MUTED)
def end(name):
    svg.append('</svg>');(OUT/(name+'.svg')).write_text('\n'.join(svg),encoding='utf8');pages.append(name);pdf.showPage()
def section(x,y,title):text(x,y,title,15,GREEN)
def rows(x,y,items,step=24,size=11):
    for i,s in enumerate(items):text(x,y+i*step,s,size)
def table(x,y,widths,headers,data,rowh=27,size=10):
    total=sum(widths);rect(x,y,total,rowh,GREEN,GREEN)
    xx=x
    for h,w in zip(headers,widths):text(xx+8,y+8,h,size,'#ffffff');xx+=w
    yy=y+rowh
    for i,row in enumerate(data):
        rect(x,yy,total,rowh,'#ffffff' if i%2==0 else '#eef2eb','#dce4da')
        xx=x
        for t,w in zip(row,widths):
            assert pdfmetrics.stringWidth(str(t),'CN',size)<=w-16,(t,w)
            text(xx+8,yy+8,str(t),size);xx+=w
        yy+=rowh
    return yy

# 1. Electrical schematic. U1 is a functional symbol, NOT a physical footprint.
begin('01 / 主原理图：现有开发板 + 屏幕 + GPS','保留现有 USB 供电；GPIO 与当前 phone_ble_nav.ino 一致。U1 仅按信号名表示，物理排针序号必须实测。',1)
rect(34,98,1123,177,'#fffdf7','#d7dfd1',8)
section(49,109,'电源：只从开发板现有 USB 口输入')
rect(49,143,262,74,'#ffffff',GREEN,6)
rows(61,154,['充电宝 5 V USB → 开发板 USB','U1 板载稳压 → DEV_3V3','开发板 5V / VIN / USB 信号不接到底板'],19,11)
line(311,171,360,171,RED);dot(360,171,RED);dot(384,171,RED);line(384,171,1126,171,RED)
line(360,171,360,163,RED);line(360,163,384,163,RED,3);line(384,163,384,171,RED);text(345,140,'JP1 短路帽',10,RED)
dot(323,171,RED);line(323,171,323,222,RED);text(308,230,'TP1',9)
dot(423,171,RED);line(423,171,423,222,RED);text(408,230,'TP2',9)
dot(1126,208);ground(1126,208);text(1109,235,'TP3',9)
text(432,142,'+3V3_PERIPH（JP1 可拔下测外设总电流）',11,RED)
for x,ref,val in [(470,'C1','10uF'),(550,'C2','100nF'),(690,'C3','10uF'),(770,'C4','100nF'),(950,'C5','10uF'),(1030,'C6','100nF')]:
    dot(x,171,RED);cap(x,171,ref,val)
text(462,259,'电源入口',9,MUTED);text(681,259,'C3/C4 靠近 J2',9,MUTED);text(942,259,'C5/C6 靠近 J3',9,MUTED)
text(35,287,'所有 GND 同网。3.3 V 电流能力取决于你现有开发板的稳压器，制板前需确认型号及负载；底板不新增稳压器。',11,ORANGE)
rect(46,318,183,470,'#ffffff',GREEN,7);text(60,329,'U1 现有 ESP32-S3',13);text(60,346,'N8R8 开发板',11)
text(60,601,'仅逻辑符号',10,ORANGE)
text(60,624,'J1A / J1B 为两排母座',9,MUTED)
text(60,647,'N、针序、两排间距待实测',9,MUTED)
rect(810,318,344,377,'#ffffff',GREEN,7);text(828,329,'J2 屏幕转接接口 / 2×5 简牛',13)
channels=[('GPIO12',371,'R1','33Ω','3  SCK'),('GPIO11',409,'R2','33Ω','5  SDI / MOSI'),('GPIO10',447,'R3','0Ω','7  CS'),('GPIO9',485,'R4','0Ω','6  D/C'),('GPIO14',523,'R5','0Ω','8  RESET'),('GPIO15',561,'R6','0Ω','10 SDO / MISO')]
for gpio,y,ref,value,label in channels:
    text(148,y-12,gpio,10,BLUE);line(229,y,435,y,BLUE);resistor(435,y,ref,value);line(483,y,810,y,BLUE);text(826,y-9,label,11)
text(264,582,'R1/R2 靠 U1；R6 靠 J2（SDO 输出端）。',10,MUTED)
line(655,601,810,601,RED);text(633,584,'+3V3_PERIPH',10,RED);text(826,592,'2  VCC（3.3 V）',11)
line(655,601,655,641,RED);dot(655,601,RED);line(655,641,701,641,RED);resistor(701,641,'R7','0Ω',RED);line(749,641,810,641,RED);text(826,632,'9  LED / BL（固定供电）',11)
text(264,619,'R7 仅作背光隔离 / 测流跳线，不是限流器。',10,ORANGE)
text(264,638,'仅连接现有屏幕模块的背光输入；不可直接接裸 LED。',10,ORANGE)
line(750,676,810,676);ground(750,676);text(826,666,'1、4  GND（都要接）',11)
text(263,685,'J2 针序由本底板定义，不能直接套用屏幕原排针顺序。',10,ORANGE)
rect(810,710,344,88,'#ffffff',GREEN,6);text(827,718,'J3 / XH-4，2.50 mm，ATGM336H',11)
text(827,741,'1  VCC ← +3V3_PERIPH    2  GND',10)
text(827,763,'3  GPS TX → GPIO17      4  GPS RX：NC',10)
text(60,754,'GPIO17 / UART RX',10,GREEN);line(229,765,435,765,GREEN);resistor(435,765,'R8','1kΩ',GREEN);line(483,765,810,765,GREEN)
dot(330,765,GREEN);line(330,765,330,783,GREEN);text(342,779,'TP4',9)
text(264,704,'GPS 天线和射频线继续沿用原模块，不在底板重画射频。',10,MUTED)
text(264,722,'GPS RX / PPS 不连接；当前固件 UART RX=17、TX=-1。',10,MUTED)
end('01_主原理图')

# 2. Exact harness definitions + optional touch, no invented module pad mapping.
begin('02 / 接口针序与预留触摸','下表定义的是新底板接口。屏幕和 GPS 原板上的丝印位置可能不同，线束按名称逐根对应。',2)
section(36,102,'J2：本底板 2×5 简牛，2.54 mm')
rect(40,131,214,187,'#ffffff','#cfd9cb',7)
text(51,141,'从元件面看进板座 / 仅示意',10,MUTED)
for row in range(5):
    yy=174+row*27
    rect(90,yy,12,12,ORANGE if row==0 else '#e2e9dd');rect(157,yy,12,12,'#e2e9dd')
    text(66,yy-1,str(row*2+1),11);text(181,yy-1,str(row*2+2),11)
text(49,302,'1脚方焊盘；缺口方向按所选料规格',9,ORANGE)
j2=[('1','GND','屏幕 GND'),('2','+3V3_PERIPH','屏幕 VCC'),('3','LCD_SCK / R1 → GPIO12','屏幕 SCK'),('4','GND','第二根地线接屏幕 GND'),('5','LCD_MOSI / R2 → GPIO11','屏幕 SDI'),('6','LCD_DC / R4 → GPIO9','屏幕 D/C'),('7','LCD_CS / R3 → GPIO10','屏幕 CS'),('8','LCD_RST / R5 → GPIO14','屏幕 RESET'),('9','LCD_LED / R7 → 3.3 V','屏幕 LED / BL'),('10','LCD_MISO / R6 → GPIO15','屏幕 SDO；当前固件不依赖读回')]
table(280,130,[40,265,315],['脚','网络 / MCU 信号','线束末端'],j2,rowh=25,size=10)
section(38,432,'J3：XH-4，2.50 mm（不是 2.54 mm）')
table(38,461,[40,180,290],['脚','网络','接 GPS 现成模块'],[('1','+3V3_PERIPH','VCC'),('2','GND','GND'),('3','GPS_TX / R8 → GPIO17','TX'),('4','NC','RX 留空，不接任何网络')],rowh=29)
text(40,618,'PPS 不引出。VCC/GND/TX 按模块丝印配线，不按针脚位置猜。',10,ORANGE)
section(622,449,'J4：触摸预留 / XH-6，2.50 mm / 默认不装')
table(622,478,[36,160,295],['脚','网络','说明'],[('1','+3V3_PERIPH','现成触摸模块供电'),('2','GND','公共地'),('3','GPIO4 / CTP_SDA','可选 R9=4.7k 上拉至 3.3 V'),('4','GPIO5 / CTP_SCL','可选 R10=4.7k 上拉至 3.3 V'),('5','GPIO6 / CTP_INT','模块中断'),('6','GPIO7 / CTP_RST','模块复位')],rowh=27,size=10)
section(39,645,'J4 预留电路（不影响当前导航，默认 DNP）')
for x,ref,gpio,net in [(65,'R9','GPIO4','CTP_SDA'),(315,'R10','GPIO5','CTP_SCL')]:
    text(x,671,'+3V3_PERIPH',10,RED);line(x+55,690,x+55,700,RED)
    rect(x+50,700,10,25,stroke=RED);text(x+72,705,ref+' 4.7k / DNP',9,ORANGE);line(x+55,725,x+55,745,BLUE)
    text(x,752,gpio+' = '+net,11,BLUE);text(x,774,'直连 J4；模块已有上拉时不装',9,MUTED)
text(626,697,'C7：100nF 接 J4-1 与 J4-2，默认 DNP。',10)
text(626,720,'只有触摸硬件、供电和固件都核对后才装 J4。',10,ORANGE)
text(626,744,'主方案不包含电量、鸣笛、亮度控制、SD 卡或电池充电。',10,MUTED)
text(39,795,'DNP = 不贴装。连接器按实物 1脚和厂家封装核对；上图不是屏幕原插座的封装。',9,ORANGE)
end('02_接口针序与触摸预留')

# 3. Mechanical workflow / conceptual placement; dimensions deliberately not fabricated.
begin('03 / PCB 布局建议与嘉立创 EDA 操作','双面 FR-4 底板；下面是位置关系示意，不是按比例的封装或可直接生产的 PCB。',3)
rect(42,128,575,409,'#edf3e8',GREEN,12)
rect(66,145,160,77,'#fff1d6',ORANGE,4);text(76,157,'ESP32 蓝牙天线端',13);text(76,182,'优先伸出底板边缘',11);text(76,201,'下方 / 周围禁铜、禁金属',10,ORANGE)
rect(67,233,162,230,'#ffffff',GREEN,6);rows(82,248,['U1 开发板插接区','两侧 J1A / J1B 排母','','N = 每排针数：待测','P = 针间距：待测','D = 两排中心距：待测','','USB 插头朝板边','USB / BOOT / RST','保留操作空间'],22,11)
rect(257,248,115,64,'#ffffff',BLUE,5);rows(268,258,['R1/R2 串联电阻','紧邻 GPIO 输出','走线短且有地回流'],16,10)
rect(406,235,180,92,'#ffffff',GREEN,5);rows(419,248,['J2 屏幕接口','C3/C4 紧邻电源脚','屏幕排线尽量 ≤ 50 mm','R6 靠 SDO 来源端'],17,10)
rect(405,356,180,63,'#ffffff',GREEN,5);rows(419,366,['J3 GPS 接口','C5/C6 靠接口','GPS / 天线远离 SCK'],15,10)
rect(258,436,325,74,'#ffffff',GREEN,5);rows(272,447,['JP1 + TP1 / TP2 / TP3 / TP4','JP1：外设总电源断开 / 测流','不要把外部 3.3 V 接入本电源网'],18,10)
for x,y in [(53,139),(604,139),(53,524),(604,524)]:
    pdf.setStrokeColor(HexColor(MUTED));pdf.circle(x,H-y,4,fill=0);svg.append(f'<circle cx="{x}" cy="{y}" r="4" fill="none" stroke="{MUTED}"/>')
text(60,548,'四个安装孔位置随屏幕 / 外壳确定；示意孔不代表实际孔距。',10,MUTED)
section(656,111,'画板前必须量出的机械数据')
rows(657,142,['① 开发板：每排针数、针间距、两排中心距。','② 开发板：正反面丝印与两排各脚的实际顺序。','③ 屏幕：连接器针数 / 间距 / 丝印顺序、安装孔距。','④ 外形：开发板长宽、屏幕 PCB 外形、元件高度。','⑤ USB：插头外廓与插拔空间；天线朝向及净空。','⑥ GPS：接口丝印、模块尺寸和天线固定位置。'],27,11)
section(656,336,'在嘉立创 EDA 中按这个顺序绘制')
rows(657,367,['1. 新建原理图：先建 J1A/J1B、J2/J3、JP1。','2. 开发板排母必须按实物逐脚编号，再加网络标签。','3. 按第 1 页补电阻、电容和测试点，第 2 页核对针序。','4. 所选连接器的原理图脚号必须对应 PCB 焊盘号。','5. J3-4、其余未用引脚加未连接标记；J4 默认 DNP。','6. 运行 ERC，再按真实尺寸关联封装、转到 PCB。','7. 先定外形 / 孔 / USB / 天线，再布电源和短 SPI。','8. 地铺铜后跑 DRC；打印 1:1 纸样套实物检查。'],25,11)
section(40,598,'布线起点（工程建议，需用实际板材 / 负载复核）')
rows(41,629,['• 2 层、FR-4 1.6 mm、1 oz；信号宽约 0.25 mm，起步间距 0.25 mm，3.3 V 主干约 0.8 mm。','• 底层尽量连续 GND，SPI 下方不要开槽 / 切地；SCK 先走短，避免长支路与绕大圈。','• MCU 到 J2 的 PCB 走线目标 ≤ 30 mm；若排线较长或 40 MHz 花屏，先缩短线并降速实测。','• 天线区按对应 ESP32 模块手册留空；GPS 陶瓷面朝上，远离显示屏、时钟线和金属遮挡。','• M3 安装孔可先预留 Ø3.2 mm 非金属化孔，螺帽 / 垫圈周边禁放铜和器件；孔距以外壳为准。','• 用有锁扣或防呆接头、螺柱和应力释放固定线束；底板不能仅靠排母悬挂屏幕重量。'],25,11)
end('03_PCB布局与EDA步骤')

BOM=[
('U1','现有 ESP32-S3 N8R8 开发板','1','已有','实物型号 / 稳压器 / 引脚排列待核对'),
('LCD1','现有 4.0 寸 480×320 SPI 屏模块','1','已有','已用 ILI9488 兼容初始化；不据此确认屏芯片型号'),
('GPS1','现有 ATGM336H-5N-31 模块及原天线','1','已有','保持现有 3.3 V 接法'),
('J1A,J1B','1×N 直插排母，针距按实物','2','必装 / 待测','N、两排间距及插入高度均需实测'),
('J2','2×5 / 2.54 mm 防呆简牛，直插','1','必装','按所购料规格匹配缺口、孔径和外形'),
('J2线束','10 芯配套 IDC 母插 + 短排线','1套','必需','另一端按屏幕丝印配线；不假定能直接插原接口'),
('J3','XH-4，2.50 mm 直插座','1','必装','配同系列外壳与端子；不能套 2.54 mm 封装'),
('J3线束','XH-4 配套壳 / 端子 / 三根工作导线','1套','必需','1=3.3 V，2=GND，3=GPS TX，4空'),
('JP1','1×2 / 2.54 mm 排针 + 短路帽','1套','必装','外设 3.3 V 隔离，选额定电流满足实测负载的料'),
('R1,R2','33 Ω，0805，1%','2','必装','SPI SCK / MOSI 源端串联；可备 22 / 0 Ω 调试'),
('R3,R4,R5,R6,R7','0 Ω，0805','5','必装','R7 不是背光限流器，仅用于现有模块输入'),
('R8','1 kΩ，0805，1%','1','必装','GPS TX 到 GPIO17 串联'),
('C1,C3,C5','10 µF / ≥10 V / X7R，0805','3','必装','查 3.3 V 直流偏压有效容量；模块入口去耦'),
('C2,C4,C6','100 nF / ≥16 V / X7R，0805','3','必装','靠近对应模块接口电源脚'),
('TP1~TP4','直径约 1.5 mm 测试焊盘','4','PCB图形','DEV_3V3、PERIPH_3V3、GND、GPIO17'),
('H1~H4','M3 螺柱、螺钉、绝缘垫圈','4套','按外壳选','长度取决于开发板与屏幕底部元件高度'),
('PCB','2层 FR-4 / 1.6 mm / 1 oz','1','待封装定版','板框、孔距、排母间距未测前不要下单'),
('USB线 / 电源','已有充电宝及短 USB 数据 / 供电线','1套','优先复用','接开发板原 USB；确认充电宝不会自动断电'),
('J4','XH-6，2.50 mm 直插座及配套线束','1套','可选 / DNP','仅触摸预留，当前导航固件未启用触摸'),
('R9,R10','4.7 kΩ / 0805 / 1%','2','可选 / DNP','I2C 上拉；模块已有上拉时不重复装'),
('C7','100 nF / ≥16 V / X7R，0805','1','可选 / DNP','J4 电源去耦')]
with (OUT/'物料清单_BOM.csv').open('w',encoding='utf-8-sig',newline='') as f:
    w=csv.writer(f);w.writerow(['位号','规格 / 搜索关键词','数量','状态','备注']);w.writerows(BOM)

begin('04 / 物料、上电核验与资料依据','物料按规格搜索并匹配所购料封装；未虚构嘉立创商城编号、现货状态或价格。',4)
section(37,105,'新购核心物料（已有开发板 / 屏幕 / GPS 不重复采购）')
core=[('排母 / J1A,J1B','2条','每排 N 脚 / 针距及两排中心距按实物'),('J2及线束','1套','2×5 / 2.54 mm 防呆简牛 + 配套 10 芯排线'),('J3及线束','1套','XH-4 / 2.50 mm；GPS RX 位置留空'),('JP1','1套','2.54 mm 两针排针 + 短路帽'),('33Ω / 0805','2颗','R1/R2；建议多备少量 22Ω 和 0Ω'),('0Ω / 0805','5颗','R3~R7；背光支路不是限流设计'),('1kΩ / 0805','1颗','R8，GPS TX 串联'),('10uF / 0805','3颗','C1/C3/C5，≥10 V，X7R，有效容量需核对'),('100nF / 0805','3颗','C2/C4/C6，≥16 V，X7R'),('M3 固定件','4套','孔 / 高度随屏幕、外壳定版')]
table(37,134,[180,65,509],['器件','数量','规格 / 说明'],core,rowh=26,size=10)
text(41,428,'完整 BOM CSV 另列已有物料、PCB、测试点与可选触摸件。建议小量备损耗，勿按图示比例采购封装。',10,MUTED)
section(37,465,'焊接与首次上电顺序')
rows(38,497,['1. 未插开发板 / 屏幕 / GPS 时，逐脚通断核对 J2/J3 和网络表，检查相邻脚无短路。','2. 检查 J1A/J1B：从元件面按实物编号，核对 3V3、GND、GPIO，不能左右镜像。','3. 拔下 JP1；只插开发板并接 USB，先测 TP1 的 DEV_3V3。断电后安装其余模块。','4. 接好 JP1，再上电测 TP2、屏幕 VCC/LED、GPS VCC；观察启动 / 蓝牙 / NMEA。','5. 测稳压器型号、总负载与发热；底板增加去耦不能弥补 LDO 供电能力不足。','6. 做显示、蓝牙和 GPS 联调后再固定外壳；外出搜星时天线朝天，轻拉线束不松脱。'],26,11)
section(839,106,'本版边界')
rows(839,137,['只做现成模块的连接 / 固定底板。','不更换 MCU、不重画 USB / RF。','不设计锂电充电或车载降压。','不接车辆 12/24/48/60/72 V。','不增加电量、鸣笛或亮度控制。','','电气关系可照图绘制；','封装与机械尺寸必须补测。'],25,11)
section(839,389,'嘉立创 EDA 选件检查')
rows(839,420,['符号脚号 = 封装焊盘号。','XH 是 2.50 mm，需匹配系列。','简牛的缺口方向不等于针序确认。','排母高度要避开底部元件。','所有线束都做 1脚 / 电压丝印。','GPS 插反会把电源接错，先断电核线。'],25,11)
section(38,678,'依据与复核范围')
rows(39,710,['已核对项目源码：software/phone_ble_nav/phone_ble_nav.ino；历史大屏及 GNSS 接线记录。','Espressif：DevKitC-1 供电指南、ESP32-S3 PCB 布局与天线净空指南；仅作通用参考，不证明你的克隆板封装相同。','中科微：ATGM336H-5N 产品资料；裸模块为 2.7~3.6 V，现成 GPS 转接板仍应按实物资料核对。','官方网页链接及可复制的逐针网络表，见同目录《嘉立创绘制说明.md》。本版未在 EDA 中完成 ERC / DRC。'],22,10)
end('04_物料与上电检查')
pdf.save()

PIN_ROWS=[]
for p,net,mc,desc in [(1,'GND','GND','屏幕GND'),(2,'+3V3_PERIPH','DEV_3V3 via JP1','屏幕VCC'),(3,'LCD_SCK','GPIO12 via R1 33R','屏幕SCK'),(4,'GND','GND','第二根屏幕GND'),(5,'LCD_MOSI','GPIO11 via R2 33R','屏幕SDI'),(6,'LCD_DC','GPIO9 via R4 0R','屏幕D/C'),(7,'LCD_CS','GPIO10 via R3 0R','屏幕CS'),(8,'LCD_RST','GPIO14 via R5 0R','屏幕RESET'),(9,'LCD_LED','+3V3_PERIPH via R7 0R','现成屏幕LED输入'),(10,'LCD_MISO','GPIO15 via R6 0R','屏幕SDO')]:PIN_ROWS.append(('J2',p,net,mc,desc,'必装'))
PIN_ROWS += [('J3',1,'+3V3_PERIPH','DEV_3V3 via JP1','GPS VCC','必装'),('J3',2,'GND','GND','GPS GND','必装'),('J3',3,'GPS_TX','GPIO17 via R8 1k','GPS TX','必装'),('J3',4,'NC','未连接','GPS RX不接','空脚')]
PIN_ROWS += [('J4',p,net,net,desc,'默认DNP') for p,net,desc in [(1,'+3V3_PERIPH','触摸VCC'),(2,'GND','触摸GND'),(3,'GPIO4','SDA / R9可选上拉'),(4,'GPIO5','SCL / R10可选上拉'),(5,'GPIO6','INT'),(6,'GPIO7','RST')]]
with (OUT/'接口逐针网络表.csv').open('w',encoding='utf-8-sig',newline='') as f:
    w=csv.writer(f);w.writerow(['接口','脚号','网络名','开发板/连接关系','模块端丝印','状态']);w.writerows(PIN_ROWS)

md='''# 导航转接底板 V0.1：嘉立创 EDA 绘制说明

设计日期：2026-10-19。用户选择保留现有开发板，制作插接底板。已生成电气原理图与位置示意，尚无实际 EDA 工程、ERC/DRC 或可生产 Gerber。开发板排针封装、屏幕原插座顺序与安装孔距需要实测，不能按图中比例猜。

## 一、设计选择

充电宝 → 开发板原 USB 口 → 开发板原稳压器 → DEV_3V3 → JP1 → +3V3_PERIPH。保持与现有面包板一致的 3.3 V 供电及 GPIO 分配。底板不新增 USB、电源芯片、车载电源、锂电充电、电量、鸣笛或亮度电路。GPS 沿用现成 ATGM336H 板及原天线，底板不做射频线路。

JP1 是两针短路帽，只隔离屏幕/GPS外设电源。拔下 JP1 不会关闭开发板；要完全关机需断开 USB。不要给 TP1/TP2 或外设网络同时灌入第二路电源。R7 为 0Ω 跳线，不承担背光恒流/限流，限于目前已点亮的现成屏幕背板输入。

屏幕使用已验证的 ILI9488 兼容初始化，这不等于已确定液晶驱动芯片型号。当前固件 40 MHz SPI；外设逻辑和电源均为 3.3 V。GPIO12=SCK、11=SDI、10=CS、9=D/C、14=RESET、15=SDO；GPS TX→R8→GPIO17，GPS RX/PPS 不接。触摸 GPIO4/5/6/7 仅预留，当前固件不启用。

## 二、在嘉立创 EDA 画原理图

1. 先建 J1A/J1B 两条开发板排母：实测 N（每排针数）、P（针间距）、D（两排中心距）后，按元件面实际顺序逐脚编号。图中 U1 为功能表示，不能把 GPIO 编号直接当排母焊盘编号，也不能套官方 DevKitC-1 封装假定兼容。
2. 复制“接口逐针网络表.csv”中的网络标签；ESP32 端 GPIO 名与实物的排针脚号需要你逐项对应。每一颗串联电阻两侧是不同网络，不能把两端用同一标签短接。
3. J2 用防呆 2×5/2.54 mm 简牛，符号脚号与所选 PCB 焊盘对应。针序是本底板定义，不是现有屏幕的原排针序。排线另一端按屏幕丝印配线；若要直接插屏幕，需额外按原接口实测制作小转接头。
4. J3 用 XH-4/2.50 mm，1=3.3 V、2=GND、3=GPS TX、4=NC。此针序同样为底板定义，GPS 原模块接口不一定相同。J3-4 加未连接标记，线束对应孔留空。
5. 串联电阻：R1/R2=33Ω；R3/R4/R5/R6/R7=0Ω；R8=1kΩ。先按图安装，R1/R2可备22Ω/0Ω用于波形或兼容性调试；阻值不是所有线长下的保证。
6. C1/C3/C5=10µF，C2/C4/C6=100nF，分别跨接 +3V3_PERIPH 与 GND。C1/C2靠JP1，C3/C4靠J2，C5/C6靠J3。0805仅是本方案建议封装；10µF/≥10V/X7R需要核对直流偏压后的有效电容。
7. J4 为可选 XH-6：1=3.3V、2=GND、3=GPIO4/SDA、4=GPIO5/SCL、5=GPIO6/INT、6=GPIO7/RST。R9/R10为4.7k上拉，分别从GPIO4/5到3.3V，C7为J4供电去耦。J4、R9/R10、C7默认不装；模块已有上拉时不重复装。
8. 测试点：TP1=DEV_3V3，TP2=+3V3_PERIPH，TP3=GND，TP4=GPIO17（R8的ESP32侧）。不用的开发板引脚标NC；5V/VIN、EN/BOOT、USB19/20等保持原开发板电路，不另外并接。
9. ERC后再转PCB。若输入电源被ERC认为未驱动，使用工具相应的电源标识表达“由开发板输出”，不要为消除报错把不同电源网短接。

## 三、底板封装和布线

机械待测表：

| 项目 | 数值 | 怎么测 |
|---|---|---|
| 每排针数 N | 待测 | 左右分别计数，不假定20/22脚 |
| 排针脚间距 P | 待测 | 测多脚中心距离除以间隔数，避免单格误差 |
| 两排中心距 D | 待测 | 从同一端对应排针中心量到另一排中心 |
| 屏幕原接口针序/间距 | 待测 | 正反面丝印照片+厂家尺寸图+万用表 |
| 屏幕/开发板尺寸与安装孔距 | 待测 | 卡尺，注明元件面与孔中心 |
| 排母高度/USB空间 | 待测 | 检查开发板背面元件不顶到底板 |
| LDO型号及额定/热能力 | 待测 | 板上器件丝印、实际板资料、负载测量 |

建议2层FR-4/1.6mm/1oz；本方案不是ESP32裸模块重设计。信号起步0.25mm、间距0.25mm，3.3V主干起步0.8mm，按负载和可制造性复核。底层尽量完整GND，不在SPI下方跨地分割。R1/R2尽量靠ESP32源端，R6靠SDO输出端J2。PCB上MCU到J2目标≤30mm，排线尽量≤50mm；这些是布线目标，不是40MHz必定稳定的承诺。

ESP32天线端优先伸出底板边缘；天线下方不要铺铜、走线、放金属或电池，净空按对应模块手册。GPS原天线朝天，远离时钟线和屏幕；保留原射频线，不随意换有源/无源天线。底板边框、孔距随实物与外壳确定，本图不给未经测量的80×60等尺寸。四个M3孔可先按Ø3.2mm非金属化孔考虑，但孔距、螺母净空和螺柱高度都要复核。打印1:1纸样套实物后再下单。

## 四、电源预算是定版条件

这是无源连接底板，外设电流全部经过现有开发板LDO。应核对 I_LDO总=I_ESP32板+I_LCD含背光+I_GPS+I_可选触摸，并用实际板电路确认额定、热余量与启动瞬态。不能从“USB 5V 1A”推导3.3V端也能输出1A。

先保持现有供电路径：USB只接开发板，外设只接3.3V。如果LDO过热、3.3V跌落或超出规格，暂不投板，改为独立稳压并进行电源隔离的新版本；不要把额外稳压器直接并到开发板3V3输出。当前没有开发板稳压芯片型号，未虚构其可用输出电流。

焊接前断电；JP1拔下可区分开发板与外设负载。完成线束和电源通断检查后，先只给开发板上电测TP1，再断电插模块、合JP1测TP2。GPS RX/PPS不接，TX只能接GPIO17输入。依次检查显示、蓝牙、NMEA和室外搜星；便携安装应固定线束和模块，避免仅靠排母承重。

## 五、物料清单

完整见“物料清单_BOM.csv”。已有ESP32-S3、屏幕、GPS及天线复用。新购主要为两条排母、J2简牛与短线束、J3 XH-4线束、JP1短路帽、电阻8颗、电容6颗、PCB与安装件。可选触摸件不计入上述8颗电阻/6颗电容。测试点是PCB焊盘，不额外采购。连接器在嘉立创按规格找料，确认针距、脚号、方向、外形与孔径，不按本图选未经匹配的商城编号；没有声明价格或库存。

## 六、来源与状态

- 本地接线：software/phone_ble_nav/phone_ble_nav.ino；GNSS模块接线与测试.md；GNSS首次搜星诊断.md；大屏真实导航使用说明.md。仅采用其中当前仍有效的硬件接线，不沿用历史Wi-Fi独立导航方案。
- Espressif供电参考：https://docs.espressif.com/projects/esp-dev-kits/en/latest/esp32s3/esp32-s3-devkitc-1/user_guide_v1.0.html （官方板参考，不能证明你的克隆板封装或电流能力相同）
- Espressif天线/PCB布局：https://docs.espressif.com/projects/esp-hardware-design-guidelines/en/latest/esp32s3/pcb-layout-design.html （本方案的两层载板建议属于基于指南的工程推导，不等于官方完整设计）
- JST XH 官方规格：https://www.jst.com/wp-content/uploads/2025/06/eXH.pdf （针距2.50 mm；连接器和压接端子需同系列匹配）
- 中科微ATGM336H-5N：https://icofchina.com/daohang/danpin/2525.html （裸模块2.7~3.6V；你的成品转接板供电仍需实物核对）

本设计已在文档层核对接口脚号、BOM位号及固件GPIO；未接入嘉立创EDA，未跑ERC/DRC，未制作或上电测试新PCB。下一步需要开发板、屏幕、GPS正反面清晰照片和上述机械数据，才能冻结排母封装与板框。
'''
(OUT/'嘉立创绘制说明.md').write_text(md,encoding='utf8')
# Screen/GPS pin consistency and BOM references are checked before rendering.
assert len([x for x in PIN_ROWS if x[0]=='J2'])==10
assert len({(x[0],x[1]) for x in PIN_ROWS})==20
assert [r[1] for r in PIN_ROWS if r[0]=='J2']==list(range(1,11))
assert 'pin_sclk = 12' in (ROOT/'software/phone_ble_nav/phone_ble_nav.ino').read_text('utf8')
doc=pdfium.PdfDocument(str(PDF));assert len(doc)==4
for i,page in enumerate(doc):
    image=page.render(scale=1.5).to_pil();image.save(OUT/f'图纸预览_{i+1:02d}.png')
overview=doc[0].render(scale=1.2).to_pil();overview.save(OUT/'主原理图预览.png')
html_body=''.join(f'<section><img src="{name}.svg" alt="{name}"></section>' for name in pages)
(OUT/'图纸总览.html').write_text('<!doctype html><meta charset="utf-8"><title>导航转接底板 V0.1</title><style>body{margin:0;background:#dfe5dc;font-family:system-ui}header{padding:24px;background:#183b32;color:white}section{max-width:1400px;margin:18px auto;background:white}img{width:100%;display:block}a{color:#176648}nav{padding:16px;background:white}@media print{header,nav{display:none}section{break-after:page;margin:0}}</style><header><h1>导航转接底板 V0.1</h1><p>电气连接草案 / 复用现有模块 / 开发板排母与机械尺寸待实测</p></header><nav><a href="../pdf/导航转接底板V0.1_原理图与绘制指南.pdf">PDF图纸</a> · <a href="物料清单_BOM.csv">物料清单</a> · <a href="接口逐针网络表.csv">逐针网络表</a> · <a href="嘉立创绘制说明.md">完整说明</a></nav>'+html_body,encoding='utf8')
print('Created 4-page A3 PDF, 4 SVG sheets, BOM, pin table, drawing guide and PNG previews.')
