#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (c) 2026 Spektra contributors. See NOTICE for upstream attribution.
"""Read-only localhost preview of the project's own Android emulator."""
import argparse
import json
import os
import re
from pathlib import Path
import secrets
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser()
parser.add_argument('--port', type=int, default=4173)
parser.add_argument('--serial', default='emulator-5554')
parser.add_argument('--token', help='Reuse an existing local preview URL after restarting')
args = parser.parse_args()
if not args.serial.startswith('emulator-'):
    raise SystemExit('This development preview accepts emulator targets only.')
adb = Path(os.environ.get('ANDROID_HOME', ROOT / '.tooling/sdk')) / 'platform-tools/adb'
token = args.token or secrets.token_urlsafe(18)
if not re.fullmatch(r'[A-Za-z0-9_-]{18,80}', token):
    raise SystemExit('Invalid preview token')
base = '/' + token
lock = threading.Lock()
frame = b''
updated = 0.0
error = '等待模拟器画面'
stop = threading.Event()
PAGE = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Spektra · Android 实时预览</title>
<style>*{box-sizing:border-box}body{margin:0;background:#151517;color:#f4f4f6;font:14px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}header{padding:16px 18px;display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid #2c2c30;gap:12px}strong{font-weight:600}small{color:#a3a3ab;font-size:12px}main{display:flex;align-items:flex-start;justify-content:center;padding:16px;min-height:70vh}img{display:block;max-width:100%;max-height:calc(100vh - 112px);width:auto;height:auto;object-fit:contain;border-radius:12px;background:#000}#state{color:#ff8c42;white-space:nowrap}footer{padding:0 18px 12px;text-align:center;font-size:12px;color:#a3a3ab}</style>
<header><div><strong>Spektra</strong><br><small>Android 模拟器实时预览</small></div><span id="state">连接中</span></header><main><img id="screen" alt="Android 模拟器当前画面"></main><footer>只读画面 · 约每 1.2 秒更新 · 编译安装后显示最新版本</footer>
<script>const img=document.getElementById('screen'),state=document.getElementById('state');let pending=false;function refresh(){if(pending)return;pending=true;img.src=location.pathname+'frame.png?t='+Date.now()}img.onload=()=>{pending=false;state.textContent='实时画面';setTimeout(refresh,1200)};img.onerror=()=>{pending=false;state.textContent='等待模拟器';setTimeout(refresh,1800)};refresh();</script></html>'''.encode()

def capture():
    global frame, updated, error
    while not stop.is_set():
        started = time.monotonic()
        try:
            result = subprocess.run([str(adb), '-s', args.serial, 'exec-out', 'screencap', '-p'],
                                    capture_output=True, timeout=6)
            if result.returncode or not result.stdout.startswith(b'\x89PNG\r\n\x1a\n'):
                raise RuntimeError('等待模拟器画面')
            with lock:
                frame, updated, error = result.stdout, time.time(), ''
        except (OSError, subprocess.SubprocessError, RuntimeError) as ex:
            with lock: error = str(ex)
        stop.wait(max(.1, 1.2 - (time.monotonic() - started)))

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = self.path.split('?', 1)[0]
        if path == base + '/':
            payload, kind, code = PAGE, 'text/html; charset=utf-8', 200
        elif path == base + '/frame.png':
            with lock: payload, stamp = frame, updated
            code = 200 if payload and time.time() - stamp < 15 else 503
            kind = 'image/png' if code == 200 else 'text/plain'
        elif path == base + '/status':
            with lock: payload = json.dumps({'updated': updated, 'error': error}).encode()
            kind, code = 'application/json', 200
        else:
            payload, kind, code = b'Not found', 'text/plain', 404
        self.send_response(code)
        self.send_header('Content-Type', kind)
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        try: self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError): pass
    def log_message(self, *_): pass

server = ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
threading.Thread(target=capture, daemon=True).start()
url = f'http://127.0.0.1:{args.port}{base}/'
state_file = ROOT / '.tooling/android-preview-url.txt'
state_file.parent.mkdir(exist_ok=True)
state_file.write_text(url + '\n')
print(url, flush=True)
try:
    server.serve_forever()
except KeyboardInterrupt:
    pass
finally:
    stop.set()
    server.server_close()
