// Host-only self-check for the pure helpers in anbu_text.h.
//   g++ -std=c++17 -I app/src/main/cpp tools/test_text.cpp -o /tmp/anbu_test_text && /tmp/anbu_test_text
#include <cassert>
#include <cstdio>
#include <string>

#include "anbu_text.h"

using namespace anbu;

static void test_utf8_prefix() {
    // "é" = C3 A9. A token stream can split exactly between those two bytes.
    const std::string partial = "caf\xC3";
    assert(utf8_complete_prefix(partial) == 3);            // "caf" only
    assert(utf8_complete_prefix("caf\xC3\xA9") == 5);      // "café"
    assert(utf8_complete_prefix("\xF0\x9F\x98") == 0);     // 3 of 4 emoji bytes
    assert(utf8_complete_prefix("\xF0\x9F\x98\x80") == 4);
    assert(utf8_complete_prefix("") == 0);
    assert(utf8_complete_prefix("ascii") == 5);
}

static void test_utf8_to_utf16() {
    assert(utf8_to_utf16("abc") == u"abc");
    assert(utf8_to_utf16("caf\xC3\xA9") == u"café");
    // U+1F600 -> surrogate pair D83D DE00
    const auto emoji = utf8_to_utf16("\xF0\x9F\x98\x80");
    assert(emoji.size() == 2);
    assert(emoji[0] == 0xD83D && emoji[1] == 0xDE00);
    // truncated tail -> single replacement char, no crash
    assert(utf8_to_utf16("a\xE2\x82") == u"a�");
    // overlong encoding of '/' (C0 AF) must be rejected and consumed whole
    assert(utf8_to_utf16("\xC0\xAF") == u"�");
    assert(utf8_to_utf16("\xC0\xAF").size() == 1);
    // lone surrogate encoded as CESU-8 (ED A0 80) must be rejected
    assert(utf8_to_utf16("\xED\xA0\x80") == u"�");
}

static void test_utf16_to_utf8() {
    assert(utf16_to_utf8(u"abc") == "abc");
    assert(utf16_to_utf8(u"café") == "caf\xC3\xA9");
    // surrogate pair D83D DE00 (U+1F600) -> F0 9F 98 80
    const char16_t emoji[] = {0xD83D, 0xDE00};
    assert(utf16_to_utf8(std::u16string_view(emoji, 2)) == "\xF0\x9F\x98\x80");
    // unpaired surrogate -> U+FFFD
    const char16_t lone[] = {0xD800};
    assert(utf16_to_utf8(std::u16string_view(lone, 1)) == "\xEF\xBF\xBD");
    // round-trip every helper path
    assert(utf16_to_utf8(utf8_to_utf16("\xF0\x9F\x98\x80 café")) == "\xF0\x9F\x98\x80 caf\xC3\xA9");
}

static void test_fts_expression() {
    assert(fts_match_expression("quantum computing") == "\"quantum\" OR \"computing\"");
    assert(fts_match_expression("") == "");
    assert(fts_match_expression("   ...   ") == "");
    // FTS5 operators in user input must be neutralised, not forwarded.
    assert(fts_match_expression("NEAR(a b)") == "\"NEAR\" OR \"a\" OR \"b\"");
    assert(fts_match_expression("title:foo*") == "\"title\" OR \"foo\"");
    assert(fts_match_expression("\"quoted\"") == "\"quoted\"");
    // non-ASCII letters survive as term bytes
    assert(fts_match_expression("riset") == "\"riset\"");
}

int main() {
    test_utf8_prefix();
    test_utf8_to_utf16();
    test_utf16_to_utf8();
    test_fts_expression();
    std::puts("anbu_text: OK");
    return 0;
}
