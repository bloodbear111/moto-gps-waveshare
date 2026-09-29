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

// Runs the round-display firmware's G-meter ball maths (the pure header
// platforms/esp32/main/accel_gmeter.hpp) on the phone. The firmware itself
// cannot be built in this environment, so this is how that logic gets executed
// on real hardware.
std::vector<GoldenCheck> RunGmeterSelfTest();

// Wire vectors followed by the G-meter behaviour checks.
std::vector<GoldenCheck> RunAllSelfTests();

// Parses `<key>=<hex>` lines out of the embedded fixture. Returns false when a
// required key is missing.
bool LookupGoldenVector(const std::string& key, std::string* hex_out);

std::vector<std::uint8_t> ParseHex(const std::string& hex, bool* ok);

}  // namespace moto::android
