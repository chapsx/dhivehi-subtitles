"""Downloads real Dhivehi speech (dash8x/dv-presidential-speech, test split) and computes
word-level ground-truth timings with a Dhivehi CTC model (forced alignment)."""
import csv, io, json, os, sys, tarfile, unicodedata
import numpy as np, soundfile as sf, torch, torchaudio
from huggingface_hub import hf_hub_download
from transformers import Wav2Vec2ForCTC, Wav2Vec2Processor

OUT = sys.argv[1]
os.makedirs(f"{OUT}/clips", exist_ok=True)
REPO = "dash8x/dv-presidential-speech"
meta = hf_hub_download(REPO, "data/metadata_test.csv", repo_type="dataset")
tarp = hf_hub_download(REPO, "data/audio_test.tar.gz", repo_type="dataset")
text = {}
with open(meta, encoding="utf-8") as f:
    for row in csv.reader(f):
        if len(row) >= 2:
            text[os.path.splitext(os.path.basename(row[0]))[0]] = row[1]
print("transcripts:", len(text))

CTC = "shahukareem/wav2vec2-large-xlsr-53-dhivehi"
proc = Wav2Vec2Processor.from_pretrained(CTC)
model = Wav2Vec2ForCTC.from_pretrained(CTC).eval()
vocab = proc.tokenizer.get_vocab()
blank = proc.tokenizer.pad_token_id
delim = vocab.get("|")
print("vocab size", len(vocab), "blank", blank, "delim", delim)

def align(wav, sentence):
    words = [w for w in sentence.split() if w]
    toks, owner = [], []
    for wi, w in enumerate(words):
        for ch in unicodedata.normalize("NFC", w):
            if ch in vocab and vocab[ch] not in (blank, delim):
                toks.append(vocab[ch]); owner.append(wi)
    if not toks:
        return None
    with torch.inference_mode():
        x = proc(wav, sampling_rate=16000, return_tensors="pt").input_values
        em = torch.log_softmax(model(x).logits, dim=-1)
    frame_s = wav.shape[0] / 16000 / em.shape[1]
    ali, scores = torchaudio.functional.forced_align(em, torch.tensor([toks], dtype=torch.int32), blank=blank)
    ali = ali[0].tolist()
    # token spans: consecutive non-blank frames advance through the token sequence
    spans, ti, prev = [], -1, blank
    for fi, a in enumerate(ali):
        if a != blank and (a != prev or prev == blank):
            ti += 1; spans.append([fi, fi + 1])
        elif a != blank and spans:
            spans[-1][1] = fi + 1
        prev = a
    res = []
    for wi, w in enumerate(words):
        idx = [k for k in range(min(len(spans), len(owner))) if owner[k] == wi]
        if not idx:
            continue
        res.append({"text": w, "start": round(spans[idx[0]][0] * frame_s, 3), "end": round(spans[idx[-1]][1] * frame_s, 3)})
    return res

clips = []
with tarfile.open(tarp) as tf:
    members = sorted([m for m in tf.getmembers() if m.name.endswith(".wav")], key=lambda m: m.name)
    print("wav files:", len(members))
    for m in members:
        key = os.path.splitext(os.path.basename(m.name))[0]
        if key not in text:
            continue
        wav, sr = sf.read(io.BytesIO(tf.extractfile(m).read()), dtype="float32")
        if wav.ndim > 1: wav = wav.mean(axis=1)
        if sr != 16000:
            wav = torchaudio.functional.resample(torch.from_numpy(wav), sr, 16000).numpy()
        words = align(wav, text[key])
        if not words:
            continue
        sf.write(f"{OUT}/clips/{key}.wav", wav, 16000, subtype="PCM_16")
        clips.append({"id": key, "sentence": text[key], "duration": round(len(wav) / 16000, 3), "words": words})
        if len(clips) >= 120:
            break
json.dump(clips, open(f"{OUT}/truth.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print("saved", len(clips), "clips")
