#!/usr/bin/env python3
"""Reproduce two bounded Controller diagnostics from the exact W10 artifact.
No network/Provider/database calls. Requires Python 3 and JDK 21+. These probes
are not a replacement for the full MarketOps integration tests.
"""
import argparse,hashlib,zipfile,pathlib,subprocess,shutil
from html.parser import HTMLParser
class Pre(HTMLParser):
 def __init__(self): super().__init__(convert_charrefs=True); self.active=False; self.parts=[]
 def handle_starttag(self,tag,attrs):
  if tag=='pre' and 'source' in dict(attrs).get('class','').split(): self.active=True
 def handle_endtag(self,tag):
  if tag=='pre': self.active=False
 def handle_data(self,data):
  if self.active:self.parts.append(data)
def main():
 p=argparse.ArgumentParser();p.add_argument('--backend-artifact',required=True,type=pathlib.Path);p.add_argument('--out',required=True,type=pathlib.Path);a=p.parse_args()
 expected='de89d479c7132e64324adfab885dacef9fd15edaaea192de3bc4e6f79c7c7caf'
 if hashlib.sha256(a.backend_artifact.read_bytes()).hexdigest()!=expected:raise SystemExit('Wrong artifact SHA-256; refusing.')
 if a.out.exists():raise SystemExit('Output path already exists; choose a fresh disposable path.')
 a.out.mkdir(parents=True);src=a.out/'src';classes=a.out/'classes';classes.mkdir()
 with zipfile.ZipFile(a.backend_artifact) as z:
  for n in z.namelist():
   if not n.endswith('.java.html') or '/site/jacoco/' not in n:continue
   pkg=pathlib.PurePosixPath(n).parent.name
   if not pkg.startswith('com.mimococo.'):continue
   html=Pre();html.feed(z.read(n).decode('utf-8'));content=''.join(html.parts)
   target=src/pathlib.Path(*pkg.split('.'))/pathlib.PurePosixPath(n).name.removesuffix('.html');target.parent.mkdir(parents=True,exist_ok=True);target.write_text(content,encoding='utf-8')
 here=pathlib.Path(__file__).resolve().parent
 import json
 for pin in json.loads((here/'SOURCE-PINS.json').read_text()):
  rel=pin['path'].split('/src/main/java/',1)[1];actual=hashlib.sha256((src/rel).read_bytes()).hexdigest()
  if actual!=pin['sha256']:raise SystemExit('Decoded source hash mismatch: '+rel)
 for name in ['CauseBoundProbe','OutcomeSpendAgePredicateProbe']:
  target=a.out/(name+'.java');shutil.copyfile(here/'counterexamples'/(name+'.java'),target)
  subprocess.run(['javac','-d',str(classes),'-sourcepath',str(src),str(target)],check=True)
  result=subprocess.run(['java','-cp',str(classes),name],capture_output=True,text=True,check=True)
  (a.out/(name+'.out')).write_text(result.stdout,encoding='utf-8');print(result.stdout,end='')
if __name__=='__main__':main()
