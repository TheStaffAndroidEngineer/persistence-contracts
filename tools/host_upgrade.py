#!/usr/bin/env python3
"""Read-only screen witnesses plus actions in this disposable catalog fixture only."""
import argparse, hashlib, json, re, shutil, subprocess, time, urllib.request
from pathlib import Path
from fresh_android_xml import read_ui_xml
from common_goods_ui import CommonGoodsUi
from datetime import datetime, timezone
import xml.etree.ElementTree as ET
p=argparse.ArgumentParser();p.add_argument('--adb',required=True);p.add_argument('--serial',required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--legacy-apk',type=Path,required=True);p.add_argument('--current-apk',type=Path,required=True)
a=p.parse_args();out=a.output.resolve();out.mkdir(parents=True,exist_ok=False)
for helper_name in (Path(__file__).name, 'common_goods_ui.py', 'fresh_android_xml.py'):
    helper = Path(__file__).with_name(helper_name)
    if helper.is_file():
        (out / helper_name).write_bytes(helper.read_bytes())
commands=[];package='staff.android.persistence';base='http://127.0.0.1:8787/'
result={'date':datetime.now(timezone.utc).isoformat(),'serial':a.serial,'passed':False,'scenarios':[]}
result['source_apks']={'legacy':str(a.legacy_apk.resolve()),'current':str(a.current_apk.resolve())}
for role in ['legacy','current']:
    source=getattr(a,role+'_apk').resolve();retained=out/('tested-'+role+'.apk')
    shutil.copy2(source,retained);setattr(a,role+'_apk',retained)
result['apk_sha256']={role:hashlib.sha256(getattr(a,role+'_apk').read_bytes()).hexdigest()
    for role in ['legacy','current']}
(out/'artifacts.json').write_text(json.dumps({'source_apks':result['source_apks'],
    'apk_sha256':result['apk_sha256']},indent=2)+'\n')
def adb(*args,binary=False):
 r=subprocess.run([a.adb,'-s',a.serial,*map(str,args)],capture_output=True)
 commands.append({'args':args,'exit':r.returncode});r.check_returncode()
 return r.stdout if binary else r.stdout.decode().strip()
def tree():
    content = read_ui_xml(adb, '/sdcard/chapter12-host.xml', {package})
    return ET.fromstring(content), content

ui = CommonGoodsUi(lambda: tree()[1], lambda *parts: adb(*parts, binary=True), out)
def find(root,text):return next((n for n in root.iter('node') if n.get('text')==text),None)
def expect(text):
 end=time.monotonic()+20
 while time.monotonic()<end:
  n=ui.text(text)
  if n is not None:return n
 raise AssertionError('Missing visible text: '+text)
def tap(text):ui.tap(text)
def capture(name):
 frames=ui.observe(name)
 texts=[n.get('text') for _,content in frames for n in ET.fromstring(content).iter('node') if n.get('text')]
 return {'capture':name,'viewports':[label for label,_ in frames],'texts':texts}

try:
 adb('install','-r','-d',a.legacy_apk.resolve())
 adb('shell','pm','clear',package)
 adb('shell','am','start','-W','-n',package+'/.MainActivity')
 tap('Seed version 1');expect('Saved note: Compare lenses');before=capture('01-version1-saved')
 adb('shell','am','force-stop',package)
 adb('install','-r',a.current_apk.resolve())
 adb('shell','am','start','-W','-n',package+'/.MainActivity')
 expect('Saved Camera: Compare lenses');migrated=capture('02-version2-preserved')
 tap('Accept price update');expect('Camera: USD 119900 minor units')
 expect('Saved Camera: Compare lenses');updated=capture('03-updated-price-saved-note')
 tap('Change sort');expect('Recent 50 order: Title')
 adb('shell','am','force-stop',package)
 adb('shell','am','start','-W','-n',package+'/.MainActivity')
 expect('Recent 50 order: Title');expect('Saved Camera: Compare lenses')
 expect('Camera: USD 119900 minor units');reopened=capture('04-reopened-data-and-preference')
 result['scenarios'].append({'name':'actual-v1-install-upgrade-and-process-reopen','before':before,'migrated':migrated,'updated':updated,'reopened':reopened})
 result['apk_sha256']={'legacy':hashlib.sha256(a.legacy_apk.read_bytes()).hexdigest(),'current':hashlib.sha256(a.current_apk.read_bytes()).hexdigest()}
 result['device']={key:adb('shell','getprop',key) for key in ['ro.product.model','ro.build.version.release','ro.build.version.sdk','ro.build.fingerprint']}
 result['passed']=True
finally:
 result['commands']=commands
 result['artifacts_sha256']={f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in out.iterdir() if f.is_file() and f.name!='results.json'}
 (out/'results.json').write_text(json.dumps(result,indent=2,default=str)+'\n')
print(json.dumps({'passed':result['passed'],'scenarios':len(result['scenarios'])}))
