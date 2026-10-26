#!/usr/bin/env python3
"""Record actual APK files plus conservative source/license attribution inventory."""
import argparse,datetime,hashlib,json,pathlib,uuid,zipfile,re
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--apk',type=pathlib.Path,required=True);p.add_argument('--out',type=pathlib.Path,required=True);a=p.parse_args()
sha=lambda b:hashlib.sha256(b).hexdigest()
components=[]
for i,c in enumerate(json.loads((ROOT/'licenses/runtime-components.json').read_text())['components']):
 entry={'type':'library','bom-ref':f'source-{i}','name':c['name'],'licenses':[{'license':{'name':c['license']}}],
   'properties':[{'name':'motoai:attribution_scope','value':'Conservative pinned source/component notice inventory; individual linked symbol coverage is not asserted'}]}
 if c.get('version'):entry['version']=c['version']
 if c.get('jar_sha256'):entry['hashes']=[{'alg':'SHA-256','content':c['jar_sha256']}]
 if c.get('source'):entry['properties'].append({'name':'motoai:source','value':c['source']})
 components.append(entry)
with zipfile.ZipFile(a.apk) as z:
 for n in z.namelist():
  if n.endswith(('.so','.dex')) or n.startswith('assets/licenses/'):
   components.append({'type':'file','bom-ref':'apk-'+sha(n.encode())[:16],'name':n,'hashes':[{'alg':'SHA-256','content':sha(z.read(n))}]})
version=re.search(r'versionName = "([^"]+)"',(ROOT/'app/build.gradle.kts').read_text())[1]
app={'type':'application','bom-ref':'MotoAI','name':'MotoAI','version':version,'licenses':[{'license':{'id':'Apache-2.0'}}],'hashes':[{'alg':'SHA-256','content':hashlib.file_digest(a.apk.open('rb'),'sha256').hexdigest()}]}
bom={'bomFormat':'CycloneDX','specVersion':'1.6','serialNumber':'urn:uuid:'+str(uuid.uuid4()),'version':1,
 'metadata':{'timestamp':datetime.datetime.now(datetime.timezone.utc).isoformat(),'component':app,'properties':[{'name':'motoai:scope','value':'Actual APK file SHA inventory and conservative source/license notices, not legal certification, F-Droid acceptance or independent artifact authentication. Some source notices cover unused helpers. Models and proprietary accelerator binaries are not packaged.'}]},'components':components}
a.out.write_text(json.dumps(bom,indent=2)+'\n');print(a.out)
