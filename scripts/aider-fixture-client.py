#!/usr/bin/env python3
"""Runs the real pinned Aider library twice against only an explicitly configured local API."""
import argparse,json,os,pathlib,sys,hashlib,time
p=argparse.ArgumentParser();p.add_argument('--model',required=True);p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--setup-only',action='store_true');a=p.parse_args()
if not os.environ.get('OPENAI_API_BASE','').startswith('https://'):p.error('Verified LAN HTTPS is required for this acceptance')
if not os.environ.get('SSL_CERT_FILE') or not os.environ.get('REQUESTS_CA_BUNDLE'):p.error('Exported public CA trust is required')
os.environ['LITELLM_LOCAL_MODEL_COST_MAP']='True'
os.environ['DO_NOT_TRACK']='1';os.environ['HF_HUB_DISABLE_TELEMETRY']='1';os.environ['TOKENIZERS_PARALLELISM']='false'
from aider import __version__
from aider.models import Model,ModelSettings,MODEL_SETTINGS,model_info_manager
from aider.io import InputOutput
from aider.coders import Coder
from aider import models
import litellm
litellm.telemetry=False;litellm.callbacks=[];litellm.success_callback=[];litellm.failure_callback=[]
if __version__!='0.86.2':raise RuntimeError('Pinned Aider version differs')
name='openai/'+a.model
metadata={'max_tokens':8192,'max_input_tokens':6144,'max_output_tokens':2048,'input_cost_per_token':0,'output_cost_per_token':0,'litellm_provider':'openai','mode':'chat','supports_function_calling':False,'supports_vision':False}
model_info_manager.local_model_metadata[name]=metadata
model_info_manager.cache_dir=a.output/'client-cache';model_info_manager.cache_file=model_info_manager.cache_dir/'model-info.json'
litellm.register_model({name:metadata})
MODEL_SETTINGS.insert(0,ModelSettings(name=name,edit_format='whole',weak_model_name=None,editor_model_name=None,use_repo_map=False,
 extra_params={'max_tokens':2048,'seed':42,'timeout':1800,'extra_headers':{'X-MotoAI-Session':os.environ['MOTOAI_API_SESSION']}},use_temperature=True))
model=Model(name,weak_model=name,editor_model=name);model.max_chat_history_tokens=8192
models.request_timeout=1800
io=InputOutput(yes=True,pretty=False,fancy_input=False,input_history_file=None,chat_history_file=None,llm_history_file=None,root=str(pathlib.Path.cwd()))
coder=Coder.create(main_model=model,io=io,edit_format='whole',fnames=['math_tools.py'],use_git=False,auto_commits=False,dirty_commits=False,
 analytics=type("NoAnalytics",(),{"event":lambda *args,**kwargs:None})(),map_tokens=0,stream=True,auto_lint=False,auto_test=False,suggest_shell_commands=False,detect_urls=False,restore_chat_history=False)
coder.max_reflections=0
assert model.weak_model is model and model.editor_model is model
if a.setup_only:
 print('Pinned Aider, single local main/weak/editor model, no Git/analytics/auto tests: setup PASS');sys.exit(0)
report={'passed':False,'aider_version':__version__,'cloud_fallback':False,'git_commits':False,'turns':[],'scope':'Real Aider library, same coder two turns, whole-file edit format; tests executed separately in networkless read-only container'}
try:
 for i,message in enumerate([
 'Implement clamp(value, lower, upper) in math_tools.py. Clamp to inclusive bounds; raise ValueError when lower is greater than upper. No imports or dependencies. Preserve the API. Apply the file edit.',
 'Now add normalise_name(name) to math_tools.py. Trim surrounding whitespace, collapse each internal run of whitespace to a single space, and use Unicode casefold. Raise ValueError if the result is empty. Keep clamp working unchanged. No imports or dependencies. Apply the file edit.'
 ]):
  start=time.monotonic();coder.run(with_message=message);content=pathlib.Path('math_tools.py').read_bytes()
  (a.output/f'turn-{i+1}-math_tools.py').write_bytes(content)
  report['turns'].append({'seconds':time.monotonic()-start,'file_sha256':hashlib.sha256(content).hexdigest(),'bytes':len(content),'reflections':coder.num_reflections})
 report['passed']=len(report['turns'])==2
finally:(a.output/'client-report.json').write_text(json.dumps(report,indent=2)+'\n')
