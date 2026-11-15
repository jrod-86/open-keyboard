# Voice cloning models for Open Keyboard's Text to speech (Zonos)

ZONOS3-Mini's reference encoder (`zac-encoder.gguf`) and SSL teacher (`ssl-teacher.gguf`) by Zyphra, Apache-2.0,
from https://huggingface.co/Zyphra/ZONOS3-Mini at commit d2d142a86b1e6aeb4252679673c86ecd86177bf8, shrunk to q8_0
with ZONOS3-Mini's own `quantize-cli` (`quantize-cli <in.gguf> <out.gguf> q8_0`), then split into pieces under
GitHub's 100 MB file limit. Join the pieces in order (`cat x.gguf.?? > x.gguf`); SHA256SUMS covers the joined files.

Original SHA-256: zac-encoder.gguf 4c4335196486fd24cc56b24a40ac4548f8cc58a6400bce8e4af4e72160b18053,
ssl-teacher.gguf 59e4f150e1ccdf54e6349b06605c2274f2262139678688025b6614f1393e415a.

Licensed under the Apache License 2.0 (https://www.apache.org/licenses/LICENSE-2.0). Modified (quantized) from the
original. Copyright Zyphra.
