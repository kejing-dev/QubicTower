"""Contrast table (D13): every text colour x the background it sits on, WCAG relative luminance ratio.
  python3 tools/contrast.py      prints a markdown table; fails (exit 1) when a pair is under its threshold."""
import sys
def lum(h):
    c=[int(h[i:i+2],16)/255 for i in (0,2,4)]
    c=[v/12.92 if v<=0.04045 else ((v+0.055)/1.055)**2.4 for v in c]
    return 0.2126*c[0]+0.7152*c[1]+0.0722*c[2]
def ratio(a,b):
    la,lb=sorted([lum(a),lum(b)],reverse=True); return (la+0.05)/(lb+0.05)
GLASS="B4B3B1"  # darkest measured window glass (royal-ur / kings-row QA)
PAIRS=[("Ink 正文 / 标题","16181D",GLASS,4.5),("InkSecondary 小字 / 副标题","353A42",GLASS,4.5),
("Ink on CardFace 状态卡 / 结果卡","16181D","F7F6F2",4.5),("InkSecondary on CardFace 状态句第二行 / 图例","353A42","F7F6F2",4.5),
("ErrorText on CardFace 满柱 / 必挡句","7A1A18","F7F6F2",4.5),("AmberInk 线芯片 on white","8A4A12","FFFFFF",4.5),
("CeladonInk 线芯片 on white","0F5E58","FFFFFF",4.5),("死线芯片 #5A6068 on white","5A6068","FFFFFF",4.5),("空线芯片 InkSecondary on white","353A42","FFFFFF",4.5),
("OnPrimary on Primary 开始 / 提示开","FFFFFF","2A2D5C",4.5),("OnError on Error 退出 / 认输确认","FFFFFF","B3261E",4.5),
("labelPrimary on fillSecondary 次按钮(玻璃上取暗值)","16181D","C9C8C6",4.5),("OnPrimary 规则序号 on Primary","FFFFFF","2A2D5C",4.5),
("TagInk on TagFace 席位牌 / 计数","F2EFE8","15171B",4.5),("Ink on CardFace S00 载入页同","16181D","F7F6F2",4.5),
("琥珀珠 vs 石墨座（图形 3:1）","E8873A","2B2F36",3.0),("青瓷珠 vs 石墨座（图形 3:1）","2FA39A","2B2F36",3.0),("红色必挡线 vs 石墨（图形 3:1）","E5483F","2B2F36",3.0)]
bad=0
print("| 文字 / 图形 | 前景 | 底色 | 对比度 | 门槛 | 结论 |\n|---|---|---|---|---|---|")
for n,f,b,t in PAIRS:
    r=ratio(f,b); ok=r>=t; bad+=not ok
    print(f"| {n} | #{f} | #{b} | {r:.2f}:1 | {t}:1 | {'过' if ok else '不过'} |")
sys.exit(1 if bad else 0)
