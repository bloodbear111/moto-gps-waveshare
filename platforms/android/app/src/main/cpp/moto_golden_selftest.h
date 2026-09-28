#pragma once

#include <string>
#include <vector>

namespace moto::android {

struct GoldenCheck {
    std::string name;
    bool ok = false;
    std::string detail;
};

// Runs the reviewed v1 wire vectors from
// shared/protocol/fixtures/ble-navigation-v1.golden.txt through the shared
// C++ codec. Nothing here re-implements the wire format.
std::vector<GoldenCheck> RunGoldenSelfTest();

// Parses `<key>=<hex>` lines out of the embedded fixture. Returns false when a
// required key is missing.
bool LookupGoldenVector(const std::string& key, std::string* hex_out);

std::vector<std::uint8_t> ParseHex(const std::string& hex, bool* ok);

}  // namespace moto::android
