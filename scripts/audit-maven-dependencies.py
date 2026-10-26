#!/usr/bin/env python3
"""Inventory cached Maven POM license declarations and locked artifact SHA-256."""
import hashlib,json,pathlib,xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parents[1]
CACHES=[ROOT/'.tools/gradle/caches/modules-2/files-2.1',ROOT/'.tools/linux-maven-artifacts']
def xml(path):
 tree=ET.parse(path)
 for node in tree.iter():node.tag=node.tag.split('}')[-1]
 return tree.getroot()
def declared_licenses(group,name,version,seen=()):
 key=(group,name,version)
 if key in seen:return []
 matches=[p for cache in CACHES for p in (cache/group/name/version).glob('*/*.pom')]
 if not matches:return []
 node=xml(matches[0]);items=[{i.tag:i.text for i in license} for license in node.findall('licenses/license')]
 if items:return items
 parent=node.find('parent')
 if parent is not None:
  keys=[parent.findtext(n) for n in ['groupId','artifactId','version']]
  if all(keys):return declared_licenses(*keys,seen=seen+(key,))
 return []
components=[]
for component in xml(ROOT/'gradle/verification-metadata.xml').findall('components/component'):
 group,name,version=[component.get(k) for k in ['group','name','version']];artifacts=[]
 for artifact in component.findall('artifact'):
  hashes=[v.get('value') for v in artifact.findall('sha256')]
  files=[p for cache in CACHES for p in (cache/group/name/version).glob('*/'+artifact.get('name'))]
  actual=[hashlib.file_digest(p.open('rb'),'sha256').hexdigest() for p in files]
  artifacts.append(dict(name=artifact.get('name'),locked_sha256=hashes,cache_match=bool(set(actual)&set(hashes))))
 metadata_only=all(a['name'].endswith(('.pom','.module')) for a in artifacts)
 components.append(dict(group=group,name=name,version=version,metadata_only=metadata_only,licenses_from_pom=declared_licenses(group,name,version),artifacts=artifacts))
report=dict(scope='Offline cached Maven build/developer dependency inventory, including build/test graph. POM declarations, not legal certification or independent upstream artifact authentication. Packaged runtime Maven graph is only Kotlin stdlib/annotations.',components=components,
 missing_binary_license=[f"{c['group']}:{c['name']}:{c['version']}" for c in components if not c['metadata_only'] and not c['licenses_from_pom']],
 unmatched_artifacts=[f"{c['group']}:{c['name']}:{a['name']}" for c in components for a in c['artifacts'] if not a['cache_match']])
(ROOT/'licenses/build-components.json').write_text(json.dumps(report,indent=2)+'\n')
print(len(components),'components; missing binary license:',report['missing_binary_license'],'; unmatched artifacts:',report['unmatched_artifacts'])
raise SystemExit(1 if report['missing_binary_license'] or report['unmatched_artifacts'] else 0)
