"""Usage: python3 make_icon.py <foreground.xml> <monochrome.xml> [preview.svg]  (run from dahri-android/)"""
# Generates the launcher icon as an Android vector (foreground + gradient background) and an SVG preview.
import sys
def fmt(v):
    # plain decimal, at most 2 places, never "-0" — Android's path parser rejects malformed numbers
    v = round(v, 2)
    if v == 0: v = 0.0
    return f"{v:g}"
def rrect(x,y,w,h,r):
    r = min(r, w/2, h/2)          # corner radius can't exceed half the side, or the path breaks
    W, H = w-2*r, h-2*r
    return (f"M{fmt(x+r)},{fmt(y)}h{fmt(W)}a{fmt(r)},{fmt(r)} 0 0 1 {fmt(r)},{fmt(r)}v{fmt(H)}a{fmt(r)},{fmt(r)} 0 0 1 {fmt(-r)},{fmt(r)}"
            f"h{fmt(-W)}a{fmt(r)},{fmt(r)} 0 0 1 {fmt(-r)},{fmt(-r)}v{fmt(-H)}a{fmt(r)},{fmt(r)} 0 0 1 {fmt(r)},{fmt(-r)}z")
fg=[]  # (pathData, fill, stroke, strokeWidth, alpha)
# magnifier: soft shadow, handle, lens
fg.append(("M71,69 L79,77", None, "#033B2C", 9, 0.35))
fg.append(("M69,67 L77,75", None, "#FFFFFF", 8, 1))
fg.append(("M48,23a25,25 0 1 1 0,50a25,25 0 1 1 0,-50z", "#F3FBF6", "#FFFFFF", 4, 1))
# spine inside the lens: 4 vertebrae with a gentle lumbar curve
vert=[(46.2,30.5,17,6.4),(47.4,40.0,18,6.6),(48.2,49.7,19,6.8),(47.6,59.6,20,6.6)]
discs=[]
for i,(cx,y,w,h) in enumerate(vert):
    # spinous process to the left (back)
    fg.append((rrect(cx-w/2-6,y+h/2-1.3,7,2.6,1.3),"#D9CBB0",None,0,1))
    fg.append((rrect(cx-w/2,y,w,h,2.4),"#FFF8EA","#C8B48E",0.8,1))
    if i<3:
        n=vert[i+1]; dy=y+h+0.4; dh=n[1]-dy-0.4; dcx=(cx+n[0])/2; dw=min(w,n[2])-1.5
        discs.append((dcx,dy,dw,dh))
for i,(dcx,dy,dw,dh) in enumerate(discs):
    fg.append((rrect(dcx-dw/2,dy,dw,dh,1.4),"#F2B544",None,0,1))
    # nerve roots leaving each side, going out and down
    fg.append((f"M{fmt(dcx-dw/2-0.5)},{fmt(dy+dh/2)} q-4,1 -6,5", None, "#F5C211", 1.6, 1))
    fg.append((f"M{fmt(dcx+dw/2+0.5)},{fmt(dy+dh/2)} q4,1 6,5", None, "#E53935" if i==2 else "#F5C211", 1.6, 1))
# herniation at the third disc, front-right edge, with a glow
dcx,dy,dw,dh=discs[2]
hx,hy=dcx+dw/2+0.6,dy+dh/2
fg.append((f"M{fmt(hx)},{fmt(hy-5.2)}a5.2,5.2 0 1 1 0,10.4a5.2,5.2 0 1 1 0,-10.4z","#E53935",None,0,0.22))
fg.append((f"M{fmt(hx)},{fmt(hy-2.9)}a2.9,2.9 0 1 1 0,5.8a2.9,2.9 0 1 1 0,-5.8z","#E53935","#FFFFFF",0.9,1))

def android_fg():
    out=['<?xml version="1.0" encoding="utf-8"?>',
     '<!-- A magnifying glass over a lumbar spine: ivory vertebrae, amber discs, yellow nerves,',
     '     and one red herniated disc pressing on a nerve (red). Generated; keep the design in sync with the preview. -->',
     '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
     '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">']
    for d,f,s,sw,a in fg:
        attrs=[f'android:pathData="{d}"']
        if f: attrs.append(f'android:fillColor="{f}"')
        if s: attrs+= [f'android:strokeColor="{s}"',f'android:strokeWidth="{sw}"','android:strokeLineCap="round"','android:strokeLineJoin="round"']
        if a!=1: attrs.append(f'android:fillAlpha="{a}"'); attrs.append(f'android:strokeAlpha="{a}"')
        out.append('    <path '+' '.join(attrs)+'/>')
    out.append('</vector>')
    return "\n".join(out)+"\n"
def svg():
    o=['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="432" height="432">',
       '<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#14A06C"/><stop offset="1" stop-color="#065240"/></linearGradient>',
       '<clipPath id="c"><circle cx="54" cy="54" r="46"/></clipPath></defs><g clip-path="url(#c)"><rect width="108" height="108" fill="url(#g)"/>']
    for d,f,s,sw,a in fg:
        o.append(f'<path d="{d}" fill="{f or "none"}" stroke="{s or "none"}" stroke-width="{sw}" stroke-linecap="round" opacity="{a}"/>')
    o.append('</g></svg>'); return "\n".join(o)
open(sys.argv[1],'w').write(android_fg())
open(sys.argv[3] if len(sys.argv) > 3 else 'icon-preview.svg','w').write(svg())
mono=[("M69,67 L77,75",None,"#FFFFFF",8,1),("M48,23a25,25 0 1 1 0,50a25,25 0 1 1 0,-50z",None,"#FFFFFF",4,1)]
for (cx,y,w,h) in vert: mono.append((rrect(cx-w/2,y,w,h,2.4),"#FFFFFF",None,0,1))
mono.append((f"M{fmt(hx)},{fmt(hy-2.9)}a2.9,2.9 0 1 1 0,5.8a2.9,2.9 0 1 1 0,-5.8z","#FFFFFF",None,0,1))
fg[:]=mono
open(sys.argv[2],'w').write(android_fg().replace('<!-- A magnifying glass','<!-- One-colour version for themed icons. A magnifying glass'))
