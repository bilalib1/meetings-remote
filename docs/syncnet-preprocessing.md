# SyncNet preprocessing spec (from joonson/syncnet_python, weights syncnet_v2.model)

Source of truth: `SyncNetInstance.py` (`evaluate()`) + `SyncNetModel.py` (class `S`).
Assumes video at **25 fps** and audio at **16 kHz mono** (the repo re-encodes with
`ffmpeg -ac 1 -ar 16000 -acodec pcm_s16le`). Both branches emit a 1024-dim embedding;
sync is scored via L2 distance between the two embeddings.

## Audio branch (`forward_aud`, ONNX `syncnet_audio.onnx`, input `mfcc`, output `aemb`)

1. Audio: 16000 Hz, mono, int16 PCM (passed to MFCC as raw int16 values — do NOT
   scale to [-1,1]; python_speech_features works on the raw sample values and the
   log inside MFCC makes this a constant offset in c0, which the model was trained with).
2. MFCC with `python_speech_features.mfcc(audio, 16000)` — **all defaults**:
   - `winlen = 0.025` s (400 samples), `winstep = 0.01` s (160 samples) → 100 MFCC frames/s
   - `numcep = 13`, `nfilt = 26`, `nfft = 512`
   - `lowfreq = 0`, `highfreq = 8000` (sr/2)
   - `preemph = 0.97`
   - `ceplifter = 22`
   - `appendEnergy = True` (coefficient 0 is replaced with log of total frame energy)
   - window function: rectangular (`winfunc = lambda x: ones(...)` — the default, NOT hamming)
   - DCT type 2 with `norm='ortho'`
3. Output of mfcc is `(T, 13)`. Transpose to `(13, T)` (repo does `zip(*mfcc)`), i.e.
   rows = 13 cepstral coefficients, columns = time.
4. No mean/variance normalization of MFCCs. Values used raw.
5. Per video frame `v` (25 fps), take a 20-column window starting at MFCC frame `4*v`
   (100 MFCC fps / 25 video fps = 4):
   ```python
   window = mfcc_13xT[:, 4*v : 4*v + 20]     # (13, 20) = 200 ms of audio
   ```
6. Tensor shape fed to the model: `(B, 1, 13, 20)` float32.
   (`cc = mfcc[None, None, :, :]`, batched over `v`.)

## Visual branch (`forward_lip`, ONNX `syncnet_visual.onnx`, input `frames`, output `vemb`)

1. Input video is the **face-cropped 224x224** clip at 25 fps. In the full repo the crop is
   produced by `run_pipeline.py` (S3FD face detection + tracking, then
   `crop_video()` crops a square of side `bs*2 * (1+2*crop_scale)` around the smoothed
   detection center with `crop_scale=0.40`, and resizes to **224x224**).
   `SyncNetInstance.evaluate()` itself does **no cropping or resizing** — it consumes
   the pre-cropped 224x224 video as-is. The model requires exactly 224x224 input
   (the final Conv3d(1,6,6) reduces the spatial map 6x6 -> 1x1 only for 224 input).
2. Frames are read with `cv2.imread` → **BGR**, uint8.
3. **No scaling, no mean subtraction**: pixels are cast to float32 with raw values 0..255.
4. Stack **5 consecutive frames** (frames `v .. v+4`) along a temporal axis.
5. Tensor shape fed to the model: `(B, 3, 5, 224, 224)` float32 = (batch, BGR channel,
   time, height, width). Repo code:
   ```python
   im = numpy.stack(images, axis=3)          # (H, W, C, T)
   im = numpy.expand_dims(im, axis=0)        # (1, H, W, C, T)
   im = numpy.transpose(im, (0, 3, 4, 1, 2)) # (1, C, T, H, W), C in BGR order
   # per window: im[:, :, v:v+5, :, :]
   ```

## Offset scoring (`calc_pdist` logic)

For each frame index `i` (0 .. N-6 where N = min(num_frames, floor(num_audio_samples/640))):
video embedding `vemb[i]` (frames i..i+4) and audio embedding `aemb[i]` (MFCC cols
4i..4i+20). Pad `aemb` with `vshift` (=15 here) zero rows on both sides, then

```python
dists[i][k] = || vemb[i] - aemb_padded[i + k] ||_2 ,  k = 0..2*vshift
mdist[k] = mean_i dists[i][k]
offset  = vshift - argmin_k mdist[k]     # >0 means audio LEADS video (audio must be delayed)
conf    = median(mdist) - min(mdist)
```

One video frame of offset = 40 ms.
