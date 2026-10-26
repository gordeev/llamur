#!/usr/bin/env python3
"""Verify and aggregate the recorded 2026-10-26 bounded release-candidate gates."""
import pathlib,json,hashlib,datetime
ROOT=pathlib.Path(__file__).resolve().parents[1];dest=ROOT/'build/release-candidate-20261026';seq=ROOT/'bench/results/final-phone-sequence-20261026T071652Z/report.json'
read=lambda p:json.loads(p.read_text())
sha=lambda p:hashlib.file_digest(p.open('rb'),'sha256').hexdigest()
r=read(seq);assert r['paused_by_owner'] is True and r['quality_boundary_completed'] is True and len(r['jobs'])==5
resumed=ROOT/'bench/results/final-phone-resume-VALIDATED/report.json'
new=read(resumed);assert new['passed'] is True and len(new['jobs'])==4
assert new['prior_sequence_sha256']==sha(seq)
# Preserve the owner pause, and do not invent a host return code for the completed last child.
for job in r['jobs']:
 if job['name']=='context-gemma-4k':
  assert job['completed_report_passed'] and read(ROOT/job['completed_report'])['passed']
  job['results']=[str(pathlib.PurePosixPath(job['completed_report']).parent)]
 else:assert job['returncode']==0
jobs=r['jobs']+new['jobs']
paths={
'm2_harness':'bench/m2-acceptance-20261025.json','m3_comparison':'bench/m3-comparison-acceptance-20261026.json',
'final_linux_pair':'bench/results/linux-product-url-copy-pair-20261026/report.json',
'final_native_bridge':'bench/results/linux-url-copy-native-bridge-20261026/report.json',
'e_debug_native_bridge':'bench/results/linux-release-test-kernel-bridge-20261026/report.json',
'url_ui_change_scope':'bench/results/url-copy-change-scope-20261026/report.json',
'final_apk_audit':'bench/results/release-audit-url-copy-signed-20261026/report.json',
'final_binary_scanner':'bench/results/fdroid-binary-scan-url-copy-signed-20261026/report.json',
'actual_fdroid_prebuild':'bench/results/fdroid-prebuild-url-copy-executed-20261026/report.json',
'final_sbom_schema':'bench/results/release-sbom-url-copy-schema-20261026/report.json',
'final_release_emulator_ui':'bench/results/release-ui-emulator-20261026T070445Z/report.json',
'final_ui_product_emulator':'bench/results/product-emulator-20261026T065732Z/report.json',
'final_kernel_emulator_framework':'bench/results/emulator-16k-20261026T054809Z/report.json',
'final_kernel_emulator_product':'bench/results/product-emulator-20261026T054803Z/report.json',
'copy_original_deletion':'bench/results/saf-copy-delete-emulator-20261026T051733Z/report.json',
'saf_actual_reboot':'bench/results/saf-reboot-20261026T045541Z/report.json',
'public_hf_download':'bench/results/downloads-emulator-20261026T045817Z/report.json',
'storage':'bench/results/storage-emulator-20261026T045812Z/report.json',
'host_parser':'bench/results/product-parser-host-auto-format-20261026/report.json',
'ornith16k':'bench/results/context-ornith-16384-20261026T021721Z/report.json',
'gemma16k':'bench/results/context-gemma-16384-20261026T030548Z/report.json',
'ornith32k':'bench/results/context-ornith-32768-20261026T040306Z/report.json',
'gemma32k':'bench/results/context-gemma-32768-20261026T055945Z/report.json',
'final_phone_idle':'bench/results/release-phone-idle-20261026T123539Z/report.json',
'host_controller_unit':'bench/results/host-final-unit-20261026/report.json','host_script_syntax':'bench/results/host-final-syntax-20261026/report.json',
'keyguard_diagnosis':'bench/results/background-keyguard-diagnosis-20261026/report.json',
'usb_transport_diagnosis':'bench/results/usb-relay-option-diagnosis-20261026/report.json',
'quality_checkpoint':'bench/m4-m5-quality-checkpoint-20261026.json','phone_resume_sequence':str(resumed.relative_to(ROOT))}
for job in jobs:
 assert (job.get('returncode')==0 or job.get('completed_report_passed') is True) and job.get('results'),job
 candidates=list(dict.fromkeys(path for path in job['results'] if (ROOT/path/'report.json').is_file()));assert len(candidates)==1,candidates
 paths['phone_'+job['name'].replace('-','_')]=candidates[0]+'/report.json'
for prefix,label in [('release-ui-phone-','actual_release_phone_ui'),('fixture-cleanup-phone-','fixture_cleanup')]:
 found=sorted((ROOT/'bench/results').glob(prefix+'*/report.json'));assert found,label;paths[label]=str(found[-1].relative_to(ROOT))
evidence={}
for label,name in paths.items():
 p=ROOT/name;d=read(p);field={'m2_harness':'milestone_accepted','m3_comparison':'comparison_complete'}.get(label,'passed');assert d.get(field) is True,(label,d.get('error'))
 evidence[label]={'path':name,'sha256':sha(p),'acceptance_field':field}
for label in ['phone_context_ornith_4k','phone_context_gemma_4k']:
 d=read(ROOT/paths[label]);assert d['context']==4096 and d['prompt_tokens']==3584 and d['finish_reason']=='eog' and d['quality']['exact_ordered_identifiers']
for label in ['phone_product','phone_context_ornith_4k','phone_context_gemma_4k','phone_ui_ornith','phone_ui_gemma','phone_aider']:
 assert read(ROOT/paths[label])['apk_sha256']=='d281a711f30249cbd0282393a313cbb222fd366ab03f4597b389553fa4e23980'
actual=read(ROOT/paths['actual_release_phone_ui']);release=dest/'MotoAI-0.1.0-dev-arm64-development-signed.apk';assert actual['apk_sha256']==sha(release) and actual['non_debug_release'] and actual['main_model_screen_off_completed'] and actual['actual_notification_tap_opens_answer'] and actual['completed_status']=='eog' and actual['visible_answer_has_expected_number']
identity=read(dest/'production-source-identity.json');assert identity['passed'] and len(identity['files'])==105
for name,h in identity['files'].items():assert sha(ROOT/name)==h,name
models=read(ROOT/'bench/models.lock.json')['models'];clean=read(ROOT/paths['fixture_cleanup'])
for m in models:
 if m['id']!='smoke':assert clean['main_models_sha256'][m['id']]==m['sha256']
report={'passed':True,'accepted_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
 'scope':'Bounded standalone0.1.0-dev release candidate for owner review, one physical phone/two original GGUFs and16KiB ARM64 emulator. Not arbitrary models/devices/tasks, every context quality, production identity, catalog inclusion or publication.',
 'milestones':{'M0':'Toolchain/upstream/model/license sources locked','M1':'Official baseline plus independent APK engine/service mechanics','M2':'Fixed CPU-reference numeric/template/token/complete-code harness','M3':'Bounded backend/scheduler screening closed; CPU4 retained, failed GPU candidates excluded','M4':'Measured numeric/loading/workspace/state corrections with original weights/F16KV; optional scratch/cache off by default; no blanket speedup','M5':'Default4K actual filled controls, prior sustained scheduling/cancel/recovery;16K/32K single exact experimental slow retrieval points both models','M6':'RAM-only dialogs/UI/profiles/storage/publicHF/background/notifications; limited device/a11y scope','M7':'Authenticated loopback/LAN TLS text API, contract/cancel/recovery and real two-turn Aider','M8':'Clean byte-equal Linux pair, real non-debug APK, audited dependencies/notices/SBOM, executed draft recipe/source+binary scanner, source package/checksums prepared separately; no publish'},
 'planned_not_implemented':{'M9':'External OpenRouter/OpenAI/Anthropic/custom provider client in PLAN section17; not part of this APK or acceptance'},
 'defaults':{'backend':'CPU','threads':4,'context':4096,'kv':'F16','weights':'original native representation','load':'mmap/no full prefault','swa_full':True,'transient_repack':False,'prefix_cache_mib':0},
 'evidence':evidence,
 'artifacts':{p.name:{'sha256':sha(p),'bytes':p.stat().st_size} for p in [release,dest/'MotoAI-0.1.0-dev-arm64-unsigned.apk',dest/'MotoAI-0.1.0-dev.cdx.json',dest/'production-source-identity.json',dest/'linux-build-environment.json']},
 'retained_failed_attempts':[{
  'path':name,'sha256':sha(ROOT/name),'error':read(ROOT/name).get('error'),
  'scope':'Failed setup/transport/notification acceptance, not a passed model-quality result; retained without changing weights/native production inputs or widening gates.'
 } for name in ['bench/results/background-phone-20261026T113625Z/report.json','bench/results/background-phone-20261026T114028Z/report.json','bench/results/aider-phone-20261026T114607Z/report.json','bench/results/aider-phone-20261026T115119Z/report.json','bench/results/release-ui-phone-20261026T121900Z/report.json']],
 'limitations':['Final-kernel Aider uses explicit developer USB opaque TLS relay because current Wi-Fi subnets differ. Prior real direct Wi-Fi CA/hostname acceptance is retained separately; no new direct LAN claim.','Single-file GGUF only; split/sharded GGUF import is not implemented.','16K/32K used731a experimental transientON; single retrieval per point, very slow, not arbitrary dialogue quality/default4K timing. Current native14-library bridge covers final kernel tests on d281; only later5-line URL-copy UI differs and was checked in actual normal release.','Only MotorolaEdge40Pro/12GB/Android16 physical; API36 ARM64 emulator has actual16KiB pages. Other devices/Android versions/unseen GGUFs are unqualified.','UI is Russian; no all-orientation/accessibility certification.','Public HF HTTPS/resume/cancel and Keystore tested; valid gated/private account token not supplied/tested.','FGS/wake lease do not bypass deepDoze/OEM policy; no forced battery/governor/whitelist, no discharge-energy claim.','Dependency hashes bootstrapped from existing cache; no independent upstream artifact-signature attestation. Linux byte equality scoped to recorded environment/canonicalpath.','Signed with existing development certificate, not production identity. Draft F-Droid public URLs/appcommit/eligibility/signing require owner decisions before publication; nothing uploaded/committed/pushed.']}
out=ROOT/'bench/release-acceptance-20261026.json';out.write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n');print(out,'PASS',len(evidence),'evidence summaries')
