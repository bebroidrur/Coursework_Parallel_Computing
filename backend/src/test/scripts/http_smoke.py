"""Run from repository root after Maven verify; starts and stops its own local server."""
import json
import sys
import os
import subprocess
import time
import urllib.request
import urllib.error
from pathlib import Path

root = Path.cwd()
target = root / 'backend/target'
base = 'http://127.0.0.1:18080'
results = []

def request(path, body=None):
    started = time.perf_counter()
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=float(os.environ.get("SMOKE_HTTP_TIMEOUT", "40"))) as response:
            status, payload = response.status, json.load(response)
    except urllib.error.HTTPError as error:
        status, payload = error.code, json.load(error)
    results.append({'path': path, 'request': body, 'http': status, 'response': payload, 'wall_seconds': time.perf_counter() - started})
    return status, payload

with (target / 'http-smoke-server.log').open('w') as log:
    process = subprocess.Popen(['java', '-Djava.io.tmpdir=' + str(target), '-jar',
        str(target / 'backend-0.0.1-SNAPSHOT.jar'), '--server.address=127.0.0.1', '--server.port=18080'],
        stdout=log, stderr=subprocess.STDOUT)
    try:
        for attempt in range(100):
            if process.poll() is not None:
                raise RuntimeError('Server exited; inspect http-smoke-server.log')
            try:
                status, health = request('/api/health')
                break
            except urllib.error.URLError:
                time.sleep(0.1)
        else:
            raise RuntimeError('Server startup timeout')
        assert status == 200 and health['status'] == 'ok' and isinstance(health['ollama'], bool)
        status, initial = request('/api/stats')
        assert status == 200 and initial['documents'] == 0
        status, empty = request('/api/search', {'query': 'java', 'mode': 'keyword'})
        assert status == 200 and empty['total'] == 0
        status, indexed = request('/api/index', {'threads': 4})
        assert status == 200 and indexed['documents'] == 4 and indexed['threads_used'] == 4
        status, search = request('/api/search', {'query': 'JAVA java', 'mode': 'keyword'})
        assert status == 200 and search['total'] == 1 and search['results'][0]['document_id'] == '03-java.txt'
        status, stats = request('/api/stats')
        assert status == 200 and stats == indexed
        status, invalid = request('/api/index', {'threads': 0})
        assert status == 400 and invalid['status'] == 'error'
        status, rebuilt = request('/api/index', {'threads': 2})
        assert status == 200 and rebuilt['documents'] == 4 and rebuilt['threads_used'] == 2
        if '--llm' in sys.argv:
            query = os.environ.get('SMOKE_LLM_QUERY', 'parallel computing')
            status, expanded = request('/api/search', {'query': query, 'mode': 'llm'})
            assert status == 200
            assert expanded['stages'] == ['query_expansion', 'keyword_search', 'done']
            assert all(term in expanded['terms'] for term in query.lower().split())
            print('REAL OLLAMA RESULT:', json.dumps(expanded, ensure_ascii=False))
        output = json.dumps(results, ensure_ascii=False, indent=2)
        (target / 'http-smoke-results.json').write_text(output)
        print(output)
    finally:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
