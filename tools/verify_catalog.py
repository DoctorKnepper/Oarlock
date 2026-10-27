"""HEAD-checks every download URL in catalog.json (books via their resolved URLs)."""
import json, sys, ssl, urllib.request
ctx = ssl.create_default_context(cafile='/root/.ccr/ca-bundle.crt')
cat = json.load(open(sys.argv[1]))
def check(url):
    req = urllib.request.Request(url, method='HEAD', headers={'User-Agent': 'ArkOfflineLibrary/1.0 (verify)'})
    try:
        with urllib.request.urlopen(req, context=ctx, timeout=60) as r:
            return r.status, r.headers.get('Content-Length')
    except Exception as e:
        return str(e)[:80], None
bad = 0
for p in cat['packs']:
    urls = []
    if p['kind'] == 'zim': urls.append(p['url'])
    if p['kind'] == 'wikipedia': urls += [p['dumpUrl'], p['indexUrl']]
    if p['kind'] == 'gutenberg': urls.append(p['url'])
    if p['kind'] == 'books':
        for it in p['items']:
            s = it['src']
            if s.startswith('pg:'): urls.append(f"https://www.gutenberg.org/ebooks/{s[3:]}.epub3.images")
            else: urls.append(f"https://archive.org/download/{s[3:]}/{s[3:]}.epub")
    for u in urls:
        st, ln = check(u)
        ok = st == 200
        if not ok: bad += 1
        print(('OK  ' if ok else 'BAD ') + str(st) + ' ' + str(ln) + ' ' + u, flush=True)
print('bad', bad)
