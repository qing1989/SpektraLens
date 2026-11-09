#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
"""Regenerate independent Metal reference images; requires numpy and an upstream Metal dylib."""
import argparse,sys,json,hashlib
from pathlib import Path
import numpy as np
sys.dont_write_bytecode = True
ROOT=Path(__file__).resolve().parent.parent
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--library', type=Path, required=True)
p.add_argument('--resources', type=Path, required=True)
p.add_argument('--output', type=Path, default=ROOT/'android/app/src/androidTest/assets/film-reference')
p.add_argument('--notes', default='Unmodified upstream numerical kernels and profiles; safe math and precise functions.')
args=p.parse_args()
sys.path.insert(0,str(ROOT/'SpektraLab/engine/tests'))
from spk_ctypes import Engine
out=args.output;out.mkdir(parents=True,exist_ok=True)
w,h=96,64
colors=np.array([[198,134,110],[88,125,72],[83,130,185],[190,36,32],[28,180,38],[25,48,192],[180,45,179],[185,180,34],[30,180,180],[25,25,25],[116,116,116],[240,240,240]],dtype=np.uint8)
image=np.zeros((h,w,3),dtype=np.uint8)
for y in range(h):
 for x in range(w):
  image[y,x]=colors[(y//16)*4+x//24] if y<48 else [round(255*x/(w-1))]*3
(out/'chart.rgb8').write_bytes(image.tobytes())
catalog=json.loads((ROOT/'android/app/src/main/assets/catalog.json').read_text())['films']
cases=[]
for stock in catalog:
 params=dict(film_stock=stock['stock'],print_stock=stock['target_print'] or 'kodak_portra_endura',scan_film=stock['type']=='positive',input_color_space='sRGB',input_cctf_decoding=True,output_color_space='sRGB',output_cctf_encoding=True,auto_exposure=False,grain_active=False,glare_active=False,halation_active=False,scene_latitude_active=False,contrast_mask_active=False,overscan_active=False,date_imprint_active=False,exposure_compensation_ev=0,print_exposure=1,preview_long_edge=1024,film_format_mm=36)
 cases.append(dict(name=stock['stock'],params=params))
extra=[('portra_exposure_up',{'exposure_compensation_ev':1.5}),('portra_exposure_down',{'exposure_compensation_ev':-2}),('portra_gamma',{'density_curve_gamma':1.4}),('portra_halation',{'halation_active':True}),('portra_auto_center',{'auto_exposure':True,'auto_exposure_method':'center_weighted'})]
base=next(c['params'] for c in cases if c['name']=='kodak_portra_400')
for name,delta in extra:cases.append(dict(name=name,params=base|delta))
metadata=dict(width=w,height=h,source_commit='267ee24380091e5bee87658d00f6c33d77321194',reference='SpektraLab native Metal; safe math + precise functions; source kernels unmodified',runtime_adapter=args.notes,cases=[])
engine=Engine(library=args.library.resolve(),resources=args.resources.resolve())
backend=engine.capabilities()['backend']
assert backend['render_core']=='native-metal', 'A Vulkan run is not an independent Metal reference'
assert 'safe' in backend['math_mode'].lower(), 'Reference must pass upstream safe-math guard'
for case in cases:
 with engine.open(image.astype(np.float32)/255.,case['params']|dict(product_defaults=True)) as session:
  rgba,_=session.render('full'); progress=session.progress()
  rgb=((rgba[:,:,:3].astype(np.uint32)+128)//257).astype(np.uint8)
  (out/(case['name']+'.rgb8')).write_bytes(rgb.tobytes())
  metadata['cases'].append(case|dict(sha256=hashlib.sha256(rgb.tobytes()).hexdigest()))
  print(case['name'],float(rgb.mean()),progress.get('auto_exposure_ev'),flush=True)
engine.close()
(out/'manifest.json').write_text(json.dumps(metadata,indent=2))
print('Reference cases',len(cases))
