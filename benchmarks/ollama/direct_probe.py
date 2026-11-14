import json, time, urllib.request, os
from pathlib import Path
payload={'model':'llama3.2:1b','prompt':'parallel computing','system':'Return JSON {"terms":["word"]} with 3-5 related search keywords. No explanation.','stream':False,'format':'json','keep_alive':'5m','options':{'temperature':0,'num_predict':48,'num_ctx':2048}}
results=[]
for label in ['first','warm']:
    start=time.perf_counter()
    entry={'run':label,'request':payload}
    try:
        req=urllib.request.Request('http://127.0.0.1:11434/api/generate', data=json.dumps(payload).encode(),headers={'Content-Type':'application/json'})
        with urllib.request.urlopen(req,timeout=45) as r: entry['response']=json.load(r)
    except Exception as e: entry['error']=str(e)
    entry['wall_seconds']=time.perf_counter()-start
    results.append(entry)
    print(json.dumps(entry),flush=True)
    Path(os.environ.get('PROBE_OUTPUT', 'benchmarks/ollama/direct-results.json')).write_text(json.dumps(results,indent=2))
