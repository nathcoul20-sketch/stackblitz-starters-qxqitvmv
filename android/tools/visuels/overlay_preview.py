"""Aperçu (PIL) du tableau de score dessiné par OverlayRenderer.kt — mêmes cotes, repère 1920x1080."""
import sys
from PIL import Image, ImageDraw, ImageFont
ASSETS = sys.argv[1]  # dossier app/src/main/assets/fonts
YELLOW=(255,241,0,255); BLACK=(0,0,0,235); NAVY=(52,52,100,255); WHITE=(255,255,255,255); RED=(227,6,19,255)
def bebas(sz): return ImageFont.truetype(f"{ASSETS}/BebasNeue-Regular.ttf", int(sz))
def josefin(sz):
    f=ImageFont.truetype(f"{ASSETS}/JosefinSans.ttf", int(sz))
    try: f.set_variation_by_axes([600])
    except Exception: pass
    return f
def text(d, s, x, base, size, font, fill=WHITE, maxw=1e9, align="m"):
    f=font(size); w=d.textlength(s,font=f)
    if w>maxw: f=font(size*maxw/w); w=maxw
    d.text((x,base), s, font=f, fill=fill, anchor=align+"s")
    return w

X0, Y0, ROW = 48, 40, 64
NAME_W, SCORE_W, CLOCK_W, ADDED_W, SUB = 250, 150, 140, 120, 34

def scoreboard(st):
    im=Image.new("RGBA",(1920,1080),(0,0,0,0)); d=ImageDraw.Draw(im)
    x=X0
    d.rectangle([x,Y0,x+NAME_W,Y0+ROW],fill=BLACK)
    d.rectangle([x+NAME_W,Y0,x+NAME_W+SCORE_W,Y0+ROW],fill=YELLOW)
    d.rectangle([x+NAME_W+SCORE_W,Y0,x+2*NAME_W+SCORE_W,Y0+ROW],fill=BLACK)
    cx=x+2*NAME_W+SCORE_W
    d.rectangle([cx,Y0,cx+CLOCK_W,Y0+ROW],fill=NAVY)
    # liseré jaune sous les noms
    d.rectangle([x,Y0+ROW-4,x+NAME_W,Y0+ROW],fill=YELLOW)
    d.rectangle([x+NAME_W+SCORE_W,Y0+ROW-4,x+2*NAME_W+SCORE_W,Y0+ROW],fill=YELLOW)
    # cartons rouges
    def cards(n, x_edge, direction):
        for i in range(n):
            cx0 = x_edge + direction*(14 + i*16) - (12 if direction<0 else 0)
            d.rectangle([cx0, Y0+14, cx0+12, Y0+14+20], fill=RED)
    cards(st["homeRed"], x+NAME_W, -1)
    cards(st["awayRed"], x+NAME_W+SCORE_W, 1)
    hr = 18+16*st["homeRed"] if st["homeRed"] else 0
    ar = 18+16*st["awayRed"] if st["awayRed"] else 0
    text(d, st["home"].upper(), x+NAME_W/2 - hr/2, Y0+48, 46, bebas, maxw=NAME_W-28-hr)
    text(d, st["away"].upper(), x+NAME_W+SCORE_W+NAME_W/2 + ar/2, Y0+48, 46, bebas, maxw=NAME_W-28-ar)
    text(d, f'{st["hg"]} - {st["ag"]}', x+NAME_W+SCORE_W/2, Y0+52, 60, bebas, fill=(0,0,0,255))
    text(d, st["clock"], cx+CLOCK_W/2, Y0+48, 46, bebas)
    if st.get("added"):
        ax=cx+CLOCK_W
        d.rectangle([ax,Y0,ax+ADDED_W,Y0+ROW],fill=YELLOW)
        text(d, st["added"], ax+ADDED_W/2, Y0+48, 42, bebas, fill=(0,0,0,255))
    # bandeau période / compétition
    y=Y0+ROW
    w=2*NAME_W+SCORE_W
    d.rectangle([x,y,x+w,y+SUB],fill=(0,0,0,190))
    label=st["period"].upper()
    lw=text(d,label,x+16,y+24,19,josefin,fill=YELLOW,align="l")
    if st["comp"]:
        text(d,"·  "+st["comp"],x+16+lw+12,y+24,19,josefin,align="l",maxw=w-lw-44)
    y+=SUB
    if st.get("pens"):
        d.rectangle([x,y,x+w,y+40],fill=YELLOW)
        text(d,"TIRS AU BUT",x+16,y+30,30,bebas,fill=(0,0,0,255),align="l")
        text(d,st["pens"],x+w-16,y+31,34,bebas,fill=(0,0,0,255),align="r")
        y+=40
    if st.get("goal"):
        team, scorer = st["goal"]
        y+=12; h=72
        d.polygon([(x,y),(x+170,y),(x+150,y+h),(x,y+h)],fill=YELLOW)
        d.polygon([(x+176,y),(x+w,y),(x+w,y+h),(x+156,y+h)],fill=BLACK)
        text(d,"BUT !",x+80,y+56,62,bebas,fill=(0,0,0,255))
        text(d,team.upper(),x+186,y+(36 if scorer else 52),34 if scorer else 44,bebas,align="l",maxw=w-210)
        if scorer: text(d,scorer,x+186,y+62,22,josefin,fill=YELLOW,align="l",maxw=w-210)
    return im

if __name__=="__main__":
    out=sys.argv[2]
    bg=Image.open(sys.argv[3]).convert("RGBA").resize((1920,1080)) if len(sys.argv)>3 else Image.new("RGBA",(1920,1080),(60,110,60,255))
    a=scoreboard(dict(home="Maybéléger FC",away="AS Andrezé",hg=2,ag=1,homeRed=0,awayRed=1,clock="67:12",period="2e mi-temps",comp="Départemental 2",goal=("Maybéléger FC","DUPONT  67'")))
    b=scoreboard(dict(home="Maybéléger FC",away="Chemillé",hg=1,ag=1,homeRed=2,awayRed=0,clock="45:00",added="+2:13",period="1re mi-temps",comp="Départemental 2",pens="4 - 3"))
    c1=bg.copy(); c1.alpha_composite(a); c2=bg.copy(); c2.alpha_composite(b)
    sheet=Image.new("RGB",(1920,1080)); sheet.paste(c1.crop((0,0,1920,540)).convert("RGB"),(0,0)); sheet.paste(c2.crop((0,0,1920,540)).convert("RGB"),(0,540))
    sheet.save(out)


PLATE_Y, PLATE_L, PLATE_R = (420, 524), (150, 730), (1190, 1770)

def result(st):
    """Noms, score, tirs au but et buteurs posés sur les écrans Mi-temps / Fin (renderResult)."""
    im=Image.new("RGBA",(1920,1080),(0,0,0,0)); d=ImageDraw.Draw(im)
    cy=(PLATE_Y[0]+PLATE_Y[1])/2
    lx=(PLATE_L[0]+PLATE_L[1])/2; rx=(PLATE_R[0]+PLATE_R[1])/2
    text(d, st["home"].upper(), lx, cy+29, 84, bebas, maxw=500)
    text(d, st["away"].upper(), rx, cy+29, 84, bebas, maxw=500)
    text(d, f'{st["hg"]} - {st["ag"]}', 960, cy+82, 240, bebas, fill=YELLOW, maxw=420)
    if st.get("pens"):
        text(d, "T.A.B. "+st["pens"], 960, cy+150, 40, josefin, fill=YELLOW, maxw=420)
    for x, lines in ((lx, st.get("hs", [])), (rx, st.get("as", []))):
        y = 612
        for ln in lines[:7]:
            text(d, ln, x, y, 32, josefin, maxw=540)
            y += 46
    return im
