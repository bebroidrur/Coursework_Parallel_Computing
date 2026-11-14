import json,time,urllib.request
from pathlib import Path
body={'model':'llama3.2:1b','prompt':'','stream':False,'keep_alive':'5m','options':{'num_ctx':2048}}
start=time.perf_counter()
try:
 req=urllib.request.Request('http://127.0.0.1:11434/api/generate',data=json.dumps(body).encode(),headers={'Content-Type':'application/json'})
 with urllib.request.urlopen(req,timeout=90) as response: result={'response':json.load(response)}
except Exception as e: result={'error':str(e)}
result['wall_seconds']=time.perf_counter()-start
Path('benchmarks/ollama/preload-result.json').write_text(json.dumps(result,indent=2))
print(json.dumps(result),flush=True)
