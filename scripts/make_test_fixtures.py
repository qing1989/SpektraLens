#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
"""Generate an original Bayer DNG with no thumbnail/preview IFD (standard library only).
Generated chart pixels and metadata are CC0. No third-party camera photo is bundled.
"""
import struct
from pathlib import Path
ROOT = Path(__file__).resolve().parent.parent

def dng(path, width=256, height=192):
    tags=[]
    def tag(code, kind, values):
        if kind==2: payload=values.encode()+b'\0';count=len(payload)
        elif kind==1: payload=bytes(values);count=len(payload)
        elif kind in (3,4): payload=struct.pack('<'+('H' if kind==3 else 'I')*len(values),*values);count=len(values)
        elif kind in (5,10): payload=b''.join(struct.pack('<'+('II' if kind==5 else 'ii'),*v) for v in values);count=len(values)
        tags.append([code,kind,count,payload])
    for code,kind,values in [
        (254,4,[0]),(256,4,[width]),(257,4,[height]),(258,3,[16]),(259,3,[1]),(262,3,[32803]),
        (271,2,'SpektraLens Test'),(272,2,'Bayer no-preview'),(273,4,[0]),(274,3,[1]),(277,3,[1]),
        (278,4,[height]),(279,4,[width*height*2]),(284,3,[1]),(33421,3,[2,2]),(33422,1,[0,1,1,2]),
        (50706,1,[1,4,0,0]),(50707,1,[1,1,0,0]),(50708,2,'SpektraLens Bayer Fixture'),
        (50710,1,[0,1,2]),(50711,3,[1]),(50713,3,[1,1]),(50714,5,[(0,1)]),(50717,4,[65535]),
        (50721,10,[(10000,10000),(0,10000),(0,10000),(0,10000),(10000,10000),(0,10000),(0,10000),(0,10000),(10000,10000)]),
        (50728,5,[(1,1),(1,1),(1,1)]),(50778,3,[21]),(50829,4,[0,0,height,width])]: tag(code,kind,values)
    tags.sort();offset=8+2+len(tags)*12+4;extra=bytearray();entries=[]
    for code,kind,count,payload in tags:
        if len(payload)<=4:field=payload.ljust(4,b'\0')
        else:
            if (offset+len(extra))%2:extra.append(0)
            field=struct.pack('<I',offset+len(extra));extra+=payload
        entries.append([code,kind,count,field])
    pixel_start=offset+len(extra)
    for e in entries:
        if e[0]==273:e[3]=struct.pack('<I',pixel_start)
    raw=bytearray()
    for y in range(height):
        for x in range(width):
            channel=(0,1,1,2)[(y%2)*2+x%2]
            rgb=(.55,.15,.08) if x<width//3 else ((.12,.4,.12) if x<width*2//3 else (.08,.15,.6))
            value=int((.05+rgb[channel]*(.45+.5*y/max(height-1,1)))*65535)
            raw+=struct.pack('<H',value)
    data=b'II*\0'+struct.pack('<I',8)+struct.pack('<H',len(entries))+b''.join(struct.pack('<HHI',c,k,n)+v for c,k,n,v in entries)+b'\0'*4+extra+raw
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
    print(path, len(data),'bytes; no embedded preview')

if __name__=='__main__':dng(ROOT/'android/app/src/androidTest/assets/bayer-no-preview.dng')
