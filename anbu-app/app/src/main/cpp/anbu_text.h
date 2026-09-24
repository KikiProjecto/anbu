// Pure text helpers shared by the RAG store and the JNI layer.
// No Android, no llama.cpp, no SQLite — compiles on the host so it can be tested
// without the NDK. See tools/test_text.cpp.
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>

namespace anbu {

// ---------------------------------------------------------------- UTF-8 ----

// Byte length of the longest prefix of `s` that ends on a whole UTF-8 sequence.
// llama.cpp emits one token at a time and a token may end mid-codepoint, so the
// JNI layer buffers the tail and only forwards complete sequences.
inline std::size_t utf8_complete_prefix(std::string_view s) {
    std::size_t i = 0, last = 0;
    while (i < s.size()) {
        const auto c = static_cast<unsigned char>(s[i]);
        std::size_t need;
        if (c < 0x80)            need = 1;
        else if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        else { ++i; last = i; continue; }   // stray continuation byte: decode as U+FFFD
        if (i + need > s.size()) break;     // truncated tail — keep for the next call
        i += need;
        last = i;
    }
    return last;
}

// Standard UTF-8 -> UTF-16. JNI's NewStringUTF takes *modified* UTF-8, which
// cannot represent anything outside the BMP, so astral codepoints (emoji, CJK-B)
// would corrupt. Decode ourselves and hand ART a UTF-16 array instead.
inline std::u16string utf8_to_utf16(std::string_view s) {
    std::u16string out;
    out.reserve(s.size());
    std::size_t i = 0;
    while (i < s.size()) {
        const auto c = static_cast<unsigned char>(s[i]);
        std::uint32_t cp;
        std::size_t need;
        if (c < 0x80)                 { cp = c;           need = 1; }
        else if ((c & 0xE0) == 0xC0)  { cp = c & 0x1Fu;   need = 2; }
        else if ((c & 0xF0) == 0xE0)  { cp = c & 0x0Fu;   need = 3; }
        else if ((c & 0xF8) == 0xF0)  { cp = c & 0x07u;   need = 4; }
        else                          { out.push_back(0xFFFD); ++i; continue; }

        if (i + need > s.size()) { out.push_back(0xFFFD); break; }

        bool ok = true;
        for (std::size_t k = 1; k < need; ++k) {
            const auto cc = static_cast<unsigned char>(s[i + k]);
            if ((cc & 0xC0) != 0x80) { ok = false; break; }
            cp = (cp << 6) | (cc & 0x3Fu);
        }
        if (!ok) { out.push_back(0xFFFD); ++i; continue; }

        const bool overlong = (need == 2 && cp < 0x80) ||
                              (need == 3 && cp < 0x800) ||
                              (need == 4 && cp < 0x10000);
        if (overlong || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
            out.push_back(0xFFFD);
            i += need;
            continue;
        }

        if (cp < 0x10000) {
            out.push_back(static_cast<char16_t>(cp));
        } else {
            cp -= 0x10000;
            out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
        }
        i += need;
    }
    return out;
}

// Standard UTF-16 -> UTF-8 (inverse of utf8_to_utf16). JNI's GetStringUTFChars
// returns *modified* UTF-8, so reading a jstring with an astral codepoint through
// it corrupts the text; GetStringChars gives real UTF-16 and we decode ourselves.
inline std::string utf16_to_utf8(std::u16string_view s) {
    std::string out;
    out.reserve(s.size() * 3 / 2);
    std::size_t i = 0;
    while (i < s.size()) {
        std::uint32_t cp = static_cast<std::uint32_t>(s[i]);
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < s.size()) {
            const std::uint32_t lo = static_cast<std::uint32_t>(s[i + 1]);
            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                ++i;
            }
        }
        if (cp >= 0xD800 && cp <= 0xDFFF) cp = 0xFFFD;   // unpaired surrogate
        if (cp < 0x80) {
            out.push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
        ++i;
    }
    return out;
}

// ------------------------------------------------------------ FTS5 query ----

// Turn free text into a safe FTS5 MATCH expression.
// Every alphanumeric token is double-quoted (so punctuation, NEAR, *, ^ and the
// column-filter syntax can never reach the parser as an operator) and the terms
// are OR-joined for recall. Returns empty when nothing usable remains.
inline std::string fts_match_expression(std::string_view query) {
    std::string out;
    std::string term;
    auto flush = [&]() {
        if (term.empty()) return;
        if (!out.empty()) out += " OR ";
        out += '"';
        out += term;
        out += '"';
        term.clear();
    };
    for (char ch : query) {
        const auto c = static_cast<unsigned char>(ch);
        const bool word = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') ||
                          (c >= 'A' && c <= 'Z') || c >= 0x80;   // keep UTF-8 letters
        if (word) term.push_back(ch);
        else flush();
    }
    flush();
    return out;
}

}  // namespace anbu
