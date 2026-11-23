"""Download ElevenLabs clips, split the batch at its longest pauses, write docs/voices/*.mp3."""
import json, os, re, subprocess, urllib.request

cfg = json.load(open('voices/urls.json'))
out = 'docs/voices'
os.makedirs(out, exist_ok=True)
os.makedirs('/tmp/v', exist_ok=True)

def fetch(url, path):
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(req, timeout=60) as r, open(path, 'wb') as f:
        f.write(r.read())

def encode(src, dst, start=None, end=None):
    cmd = ['ffmpeg', '-y', '-loglevel', 'error']
    if start is not None: cmd += ['-ss', f'{start:.3f}']
    if end is not None: cmd += ['-to', f'{end:.3f}']
    cmd += ['-i', src, '-af',
            'silenceremove=start_periods=1:start_threshold=-45dB,areverse,silenceremove=start_periods=1:start_threshold=-45dB,areverse,loudnorm=I=-16:TP=-1.5,apad=pad_dur=0.15',
            '-ac', '1', '-ar', '44100', '-b:a', '64k', dst]
    subprocess.run(cmd, check=True)

for name, url in cfg.get('single', {}).items():
    raw = f'/tmp/v/{name}.mp3'
    fetch(url, raw)
    encode(raw, f'{out}/{name}.mp3')
    print('single', name)

def split_batch(b, tag):
    raw = f'/tmp/v/{tag}.mp3'
    fetch(b['url'], raw)
    n = len(b['order'])
    dur = float(subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', raw],
                               capture_output=True, text=True).stdout)
    log = subprocess.run(['ffmpeg', '-i', raw, '-af', 'silencedetect=noise=-40dB:d=0.25', '-f', 'null', '-'],
                         capture_output=True, text=True).stderr
    starts = [float(x) for x in re.findall(r'silence_start: ([\d.]+)', log)]
    ends = [float(x) for x in re.findall(r'silence_end: ([\d.]+)', log)]
    gaps = [(e - s, (s + e) / 2) for s, e in zip(starts, ends) if 0.3 < s and e < dur - 0.3]
    print(f'duration {dur:.1f}s, {len(gaps)} pauses found, need {n - 1}')
    if len(gaps) < n - 1:
        raise SystemExit('not enough pauses to split the batch')
    # expected cut positions from each line's length (letters only, no diacritics)
    lens = [max(1, len(re.sub(r'[\u064B-\u0652\u0670\s\W]', '', t))) for t in b.get('texts', [''] * n)]
    tot = sum(lens); acc = 0; exp = []
    for L in lens[:-1]:
        acc += L; exp.append(dur * acc / tot)
    cand = sorted(gaps, key=lambda g: g[1])
    # DP: pick n-1 increasing pauses, each near its expected spot, preferring longer pauses
    INF = float('inf'); m = len(cand)
    cost = [[INF] * m for _ in range(n - 1)]; back = [[-1] * m for _ in range(n - 1)]
    def c(j, i): return (cand[i][1] - exp[j]) ** 2 - 4.0 * cand[i][0]
    for i in range(m): cost[0][i] = c(0, i)
    for j in range(1, n - 1):
        best, bi = INF, -1
        for i in range(m):
            if i > 0 and cost[j - 1][i - 1] < best: best, bi = cost[j - 1][i - 1], i - 1
            if bi >= 0: cost[j][i] = best + c(j, i); back[j][i] = bi
    i = min(range(m), key=lambda k: cost[n - 2][k]); picks = []
    for j in range(n - 2, -1, -1):
        picks.append(i); i = back[j][i]
    cuts = [cand[k][1] for k in reversed(picks)]
    print('expected', [round(x, 2) for x in exp]); print('pauses', [(round(g[1], 2), round(g[0], 2)) for g in cand])
    bounds = [0.0] + cuts + [dur]
    for i, name in enumerate(b['order']):
        encode(raw, f'{out}/{name}.mp3', bounds[i], bounds[i + 1])
        print('batch', name, f'{bounds[i]:.2f}-{bounds[i+1]:.2f}')

batches = cfg.get('batches') or ([cfg['batch']] if cfg.get('batch') else [])
for i, b in enumerate(batches):
    split_batch(b, f'batch{i}')
print('files:', len(os.listdir(out)))
