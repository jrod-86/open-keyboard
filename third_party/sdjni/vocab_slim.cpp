// SPDX-License-Identifier: MIT
// Open Keyboard only runs SD 1.x style models and Z-Image (Qwen3 text model), so only the CLIP and Qwen tokenizer data is built in;
// this replaces stable-diffusion.cpp's src/tokenizers/vocab/vocab.cpp to keep the app small.
#include "vocab.h"
#include "clip_merges.hpp"
#include "qwen_merges.hpp"

std::string load_clip_merges() {
    return std::string(reinterpret_cast<const char*>(clip_merges_utf8_c_str), sizeof(clip_merges_utf8_c_str));
}
std::string load_qwen2_merges() {
    return std::string(reinterpret_cast<const char*>(qwen2_merges_utf8_c_str), sizeof(qwen2_merges_utf8_c_str));
}
std::string load_mistral_merges() { return ""; }
std::string load_mistral_vocab_json() { return ""; }
std::string load_t5_tokenizer_json() { return ""; }
std::string load_umt5_tokenizer_json() { return ""; }
std::string load_gemma_merges() { return ""; }
std::string load_gemma_vocab_json() { return ""; }
