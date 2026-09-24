// Minimal JSON emitter. The only consumer is the Kotlin side, which parses with
// org.json (already on the platform) — so we only ever need to *write* JSON here.
#pragma once

#include <cstdio>
#include <string>
#include <string_view>

namespace anbu {

inline std::string json_escape(std::string_view s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (char ch : s) {
        const auto c = static_cast<unsigned char>(ch);
        switch (ch) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out.push_back(ch);   // UTF-8 passes through
                }
        }
    }
    return out;
}

inline std::string json_string(std::string_view s) {
    return "\"" + json_escape(s) + "\"";
}

}  // namespace anbu
