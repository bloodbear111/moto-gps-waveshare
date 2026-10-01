#include "moto_golden_selftest.h"

#include "moto_golden_fixture.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <string>
#include <string_view>
#include <vector>

#include "moto/ble_protocol/ble_protocol.hpp"
#include "accel_gmeter.hpp"

namespace moto::android {
namespace {

using moto::ble::Bytes;
using moto::ble::ByteView;
using moto::ble::Message;

std::string ToHex(ByteView bytes) {
    static const char* kDigits = "0123456789abcdef";
    std::string out;
    out.reserve(bytes.size * 2);
    for (std::size_t i = 0; i < bytes.size; ++i) {
        out.push_back(kDigits[(bytes.data[i] >> 4) & 0x0FU]);
        out.push_back(kDigits[bytes.data[i] & 0x0FU]);
    }
    return out;
}

std::string Describe(ByteView actual, ByteView expected) {
    return "expected=" + ToHex(expected) + " actual=" + ToHex(actual);
}

bool BytesEqual(ByteView a, ByteView b) {
    return a.size == b.size &&
           std::equal(a.data, a.data + a.size, b.data);
}

// Round-trips a golden payload through the shared decoder and encoder. A codec
// that silently reorders fields, changes units or changes the enum numbering
// fails here even though it might still produce a "parseable" message.
GoldenCheck RoundTrip(const std::string& payload_key,
                      moto::ble::MessageType type) {
    GoldenCheck check;
    check.name = "roundtrip:" + payload_key;

    std::string hex;
    if (!LookupGoldenVector(payload_key, &hex)) {
        check.detail = "fixture key missing";
        return check;
    }
    bool parsed = false;
    const Bytes payload = ParseHex(hex, &parsed);
    if (!parsed) {
        check.detail = "fixture hex is malformed";
        return check;
    }

    const auto decoded = moto::ble::decode_message(type, ByteView(payload));
    if (!decoded.ok()) {
        check.detail = std::string("decode_message failed: ") +
                       moto::ble::to_string(decoded.error) + " at " +
                       std::to_string(decoded.offset);
        return check;
    }
    if (moto::ble::message_type(decoded.value) != type) {
        check.detail = "decoded message type mismatch";
        return check;
    }
    const auto reencoded = moto::ble::encode_message(decoded.value);
    if (!reencoded.ok()) {
        check.detail = std::string("encode_message failed: ") +
                       moto::ble::to_string(reencoded.error);
        return check;
    }
    if (!BytesEqual(ByteView(reencoded.value), ByteView(payload))) {
        check.detail = "re-encoded bytes differ from fixture: " +
                       Describe(ByteView(reencoded.value), ByteView(payload));
        return check;
    }

    check.ok = true;
    check.detail = std::to_string(payload.size()) + " bytes round-tripped";
    return check;
}

}  // namespace

std::vector<std::uint8_t> ParseHex(const std::string& hex, bool* ok) {
    Bytes out;
    out.reserve(hex.size() / 2);
    bool valid = (hex.size() % 2) == 0;
    auto nibble = [](char c, bool* good) {
        if (c >= '0' && c <= '9') return static_cast<std::uint8_t>(c - '0');
        if (c >= 'a' && c <= 'f') return static_cast<std::uint8_t>(c - 'a' + 10);
        if (c >= 'A' && c <= 'F') return static_cast<std::uint8_t>(c - 'A' + 10);
        *good = false;
        return static_cast<std::uint8_t>(0);
    };
    for (std::size_t i = 0; valid && i + 1 < hex.size() + 1 && i + 1 <= hex.size();
         i += 2) {
        bool good = true;
        const std::uint8_t hi = nibble(hex[i], &good);
        const std::uint8_t lo = nibble(hex[i + 1], &good);
        if (!good) {
            valid = false;
            break;
        }
        out.push_back(static_cast<std::uint8_t>((hi << 4) | lo));
    }
    if (ok != nullptr) *ok = valid;
    return out;
}

bool LookupGoldenVector(const std::string& key, std::string* hex_out) {
    std::string_view text(golden::kFixtureText);
    std::size_t pos = 0;
    const std::string prefix = key + "=";
    while (pos < text.size()) {
        const std::size_t end = text.find('\n', pos);
        std::string_view line = text.substr(
            pos, end == std::string_view::npos ? std::string_view::npos : end - pos);
        while (!line.empty() &&
               (line.back() == '\r' || line.back() == ' ')) {
            line.remove_suffix(1);
        }
        if (!line.empty() && line.front() != '#') {
            if (line.size() > prefix.size() &&
                line.compare(0, prefix.size(), prefix) == 0) {
                if (hex_out != nullptr) {
                    *hex_out = std::string(line.substr(prefix.size()));
                }
                return true;
            }
        }
        if (end == std::string_view::npos) break;
        pos = end + 1;
    }
    return false;
}

std::vector<GoldenCheck> RunGoldenSelfTest() {
    std::vector<GoldenCheck> checks;
    auto add = [&checks](const std::string& name, bool ok,
                         const std::string& detail) {
        GoldenCheck check;
        check.name = name;
        check.ok = ok;
        check.detail = detail;
        checks.push_back(std::move(check));
    };

    // 1. Documented CRC parameters (poly 0x1021, init 0xFFFF, no reflection).
    {
        const char* probe = "123456789";
        const std::uint16_t crc = moto::ble::crc16_ccitt_false(
            ByteView(reinterpret_cast<const std::uint8_t*>(probe), 9));
        char buffer[32];
        std::snprintf(buffer, sizeof(buffer), "0x%04X", crc);
        add("crc16:123456789", crc == 0x29B1U,
            std::string("expected 0x29B1 actual ") + buffer);
    }

    // 2. A snapshot that claims a route view must carry a route token. The
    //    encoder answers such a message with an error and therefore no frames,
    //    which the phone cannot tell apart from "nothing to send" - it kept
    //    sending snapshots until the route arrived and then went silent, leaving
    //    the round display on "planning route" while the phone had a full route.
    {
        moto::ble::NavigationSnapshot snapshot;
        snapshot.flags = moto::ble::NavigationHasRouteView;
        snapshot.route_generation = 1;
        snapshot.route_token = 0;
        const bool refused_without_token =
            !moto::ble::encode_message(moto::ble::Message{snapshot}).ok();

        snapshot.route_token =
            moto::ble::route_token(std::string_view("moto-route-1"));
        const bool accepted_with_token =
            moto::ble::encode_message(moto::ble::Message{snapshot}).ok();

        add("snapshot:route-token-required",
            refused_without_token && accepted_with_token,
            refused_without_token
                ? (accepted_with_token
                       ? "tokenless route view refused, token accepted"
                       : "tokenless refused but a token was still rejected")
                : "tokenless route view was accepted");
    }

    // 3. GATT identifiers are part of the contract and must not drift.
    {
        const bool ok =
            std::string(moto::ble::kServiceUuid) ==
                "7e57a000-b50c-4b6a-9c57-40a54e8e1000" &&
            std::string(moto::ble::kPhoneToDeviceUuid) ==
                "7e57a001-b50c-4b6a-9c57-40a54e8e1000" &&
            std::string(moto::ble::kDeviceToPhoneUuid) ==
                "7e57a002-b50c-4b6a-9c57-40a54e8e1000" &&
            std::string(moto::ble::kCccdUuid) ==
                "00002902-0000-1000-8000-00805f9b34fb";
        add("gatt:uuids", ok, ok ? "service/rx/tx/cccd match v1" : "UUID drift detected");
    }

    // 3. The golden connection frame decodes with the documented header.
    {
        std::string hex;
        bool parsed = false;
        if (!LookupGoldenVector("connection.frame", &hex)) {
            add("frame:connection", false, "fixture key missing");
        } else {
            const Bytes frame = ParseHex(hex, &parsed);
            const auto decoded = moto::ble::decode_frame(ByteView(frame));
            if (!parsed || !decoded.ok()) {
                add("frame:connection", false,
                    std::string("decode_frame failed: ") +
                        (parsed ? moto::ble::to_string(decoded.error) : "bad hex"));
            } else {
                const moto::ble::Frame& value = decoded.value;
                const bool ok =
                    value.type == moto::ble::MessageType::ConnectionStatus &&
                    value.sequence == 1 &&
                    value.fragment_offset == 0 &&
                    (value.flags & (moto::ble::FrameStart | moto::ble::FrameEnd)) ==
                        (moto::ble::FrameStart | moto::ble::FrameEnd) &&
                    value.message_length == 17 && value.payload.size() == 17;
                char detail[160];
                std::snprintf(detail, sizeof(detail),
                              "type=0x%02X seq=%u offset=%u flags=0x%02X length=%u",
                              static_cast<unsigned>(value.type), value.sequence,
                              value.fragment_offset, value.flags,
                              value.message_length);
                add("frame:connection", ok, detail);
            }
        }
    }

    // 4. Connection payload fields decode to the documented negotiation values.
    {
        std::string hex;
        bool parsed = false;
        if (!LookupGoldenVector("connection.payload", &hex)) {
            add("payload:connection-fields", false, "fixture key missing");
        } else {
            const Bytes payload = ParseHex(hex, &parsed);
            const auto decoded = moto::ble::decode_message(
                moto::ble::MessageType::ConnectionStatus, ByteView(payload));
            const auto* status =
                decoded.ok()
                    ? std::get_if<moto::ble::ConnectionStatus>(&decoded.value)
                    : nullptr;
            if (status == nullptr) {
                add("payload:connection-fields", false,
                    std::string("decode failed: ") +
                        (parsed ? moto::ble::to_string(decoded.error) : "bad hex"));
            } else {
                const bool ok =
                    status->role == moto::ble::EndpointRole::Phone &&
                    status->state == moto::ble::ConnectionState::Ready &&
                    status->minimum_version == 1 && status->maximum_version == 1 &&
                    status->capabilities == 0x7FU &&
                    status->session_id == 0x12345678U &&
                    status->max_frame_size == 185 &&
                    status->heartbeat_interval_ms == 1000;
                char detail[192];
                std::snprintf(detail, sizeof(detail),
                              "role=%u state=%u caps=0x%08X session=0x%08X frame=%u hb=%u",
                              static_cast<unsigned>(status->role),
                              static_cast<unsigned>(status->state),
                              status->capabilities, status->session_id,
                              status->max_frame_size,
                              status->heartbeat_interval_ms);
                add("payload:connection-fields", ok, detail);

                // 5. Encoding the same values must reproduce the fixture bytes
                //    and its single-frame projection at 185 bytes.
                if (ok) {
                    const auto encoded = moto::ble::encode_message(
                        Message{*status});
                    const bool payload_ok =
                        encoded.ok() &&
                        BytesEqual(ByteView(encoded.value), ByteView(payload));

                    const auto frames = moto::ble::fragment_message(
                        moto::ble::MessageType::ConnectionStatus, 1,
                        ByteView(payload), 185, 0);
                    bool frame_ok = frames.ok() && frames.value.size() == 1;
                    if (frame_ok) {
                        std::string golden_frame_hex;
                        bool frame_parsed = false;
                        if (LookupGoldenVector("connection.frame",
                                               &golden_frame_hex)) {
                            const Bytes golden_frame =
                                ParseHex(golden_frame_hex, &frame_parsed);
                            frame_ok = frame_parsed &&
                                       BytesEqual(ByteView(frames.value[0]),
                                                  ByteView(golden_frame));
                        } else {
                            frame_ok = false;
                        }
                    }
                    add("encode:connection-frame", payload_ok && frame_ok,
                        payload_ok ? (frame_ok ? "payload and 185-byte frame match"
                                               : "frame projection mismatch")
                                   : "payload encoding mismatch");
                }
            }
        }
    }

    // 6. Every business payload must survive decode -> encode unchanged.
    checks.push_back(
        RoundTrip("navigation.payload", moto::ble::MessageType::NavigationSnapshot));
    checks.push_back(
        RoundTrip("geometry.payload", moto::ble::MessageType::RouteGeometry));
    checks.push_back(
        RoundTrip("command.payload", moto::ble::MessageType::DeviceCommand));

    // 7. The 20-byte fragment split of the MusicNext command is stable, and the
    //    frames reassemble back to the same logical message.
    {
        GoldenCheck check;
        check.name = "fragments:command20";
        std::string frames_hex;
        std::string payload_hex;
        if (!LookupGoldenVector("command.frames20", &frames_hex) ||
            !LookupGoldenVector("command.payload", &payload_hex)) {
            check.detail = "fixture keys missing";
        } else {
            std::vector<Bytes> golden_frames;
            bool parsed = true;
            std::size_t start = 0;
            while (start <= frames_hex.size()) {
                const std::size_t sep = frames_hex.find(';', start);
                const std::string part = frames_hex.substr(
                    start, sep == std::string::npos ? std::string::npos
                                                    : sep - start);
                bool part_ok = false;
                golden_frames.push_back(ParseHex(part, &part_ok));
                parsed = parsed && part_ok;
                if (sep == std::string::npos) break;
                start = sep + 1;
            }

            bool payload_ok = false;
            const Bytes payload = ParseHex(payload_hex, &payload_ok);
            const auto produced = moto::ble::fragment_message(
                moto::ble::MessageType::DeviceCommand, 0x1234, ByteView(payload),
                20, moto::ble::AckRequested | moto::ble::Urgent);

            bool encode_ok = parsed && payload_ok && produced.ok() &&
                             produced.value.size() == golden_frames.size();
            if (encode_ok) {
                for (std::size_t i = 0; i < golden_frames.size(); ++i) {
                    if (!BytesEqual(ByteView(produced.value[i]),
                                    ByteView(golden_frames[i]))) {
                        encode_ok = false;
                        break;
                    }
                }
            }

            // Reassembly of the golden frames must reproduce the payload and
            // must not accept interleaved or out-of-order continuation.
            moto::ble::Reassembler reassembler;
            bool reassemble_ok = true;
            std::uint64_t now = 1'000;
            for (std::size_t i = 0; i < golden_frames.size() && reassemble_ok; ++i) {
                const auto result =
                    reassembler.push(ByteView(golden_frames[i]), now);
                now += 10;
                if (i + 1 < golden_frames.size()) {
                    reassemble_ok = result.state ==
                                    moto::ble::ReassemblyState::InProgress;
                } else {
                    reassemble_ok = result.complete() &&
                                    result.message.type ==
                                        moto::ble::MessageType::DeviceCommand &&
                                    BytesEqual(ByteView(result.message.payload),
                                               ByteView(payload));
                }
            }

            check.ok = encode_ok && reassemble_ok;
            if (check.ok) {
                char detail[128];
                std::snprintf(detail, sizeof(detail),
                              "%zu frames at 20-byte values, reassembled %zu bytes",
                              golden_frames.size(), payload.size());
                check.detail = detail;
            } else {
                check.detail = encode_ok ? "reassembly mismatch"
                                         : "20-byte fragment encoding mismatch";
            }
        }
        checks.push_back(std::move(check));
    }

    // 8. A continuation without its START must be rejected.
    {
        GoldenCheck check;
        check.name = "reassembly:missing-start";
        std::string frames_hex;
        if (!LookupGoldenVector("command.frames20", &frames_hex)) {
            check.detail = "fixture key missing";
        } else {
            const std::size_t sep = frames_hex.find(';');
            bool parsed = false;
            const Bytes second =
                ParseHex(frames_hex.substr(sep + 1), &parsed);
            moto::ble::Reassembler reassembler;
            const auto result = reassembler.push(ByteView(second), 1'000);
            const bool ok = parsed &&
                            result.state == moto::ble::ReassemblyState::Error &&
                            result.error == moto::ble::Error::MissingStart;
            check.ok = ok;
            check.detail = ok
                               ? "continuation rejected with MissingStart"
                               : std::string("unexpected state: ") +
                                     moto::ble::to_string(result.error);
        }
        checks.push_back(std::move(check));
    }

    return checks;
}

// Verifies the firmware's G-meter ball maths on real hardware.
//
// The round-display firmware cannot be compiled in the Android build
// environment, so this runs the *same* header (platforms/esp32/main/
// accel_gmeter.hpp) through the phone's self-test. It checks behaviour, not
// just that the header links: a rest sample must centre the ball, a lateral
// push must move it sideways, removing the push must re-centre it, and an
// extreme value must saturate on the rim instead of leaving the dial.
std::vector<GoldenCheck> RunGmeterSelfTest() {
    std::vector<GoldenCheck> checks;
    auto add = [&checks](const std::string& name, bool ok,
                         const std::string& detail) {
        GoldenCheck check;
        check.name = name;
        check.ok = ok;
        check.detail = detail;
        checks.push_back(std::move(check));
    };

    constexpr float kG = moto::gmeter::kStandardGravity;

    auto at_rest = []() {
        moto::gmeter::Input input;
        input.total_z = moto::gmeter::kStandardGravity;
        return input;
    };
    auto feed = [](moto::gmeter::Meter& meter,
                   const moto::gmeter::Input& input, int samples,
                   std::uint64_t start_ms) {
        for (int index = 0; index < samples; ++index) {
            meter.update(input,
                         start_ms + static_cast<std::uint64_t>(index) * 25U);
        }
    };

    {
        moto::gmeter::Meter meter;
        const auto reading = meter.update(at_rest(), 0);
        char detail[96];
        std::snprintf(detail, sizeof(detail),
                      "total=%.3fg ball=(%.3f,%.3f)", reading.total_g,
                      reading.ball_x, reading.ball_y);
        add("gmeter:rest-centres-ball",
            std::abs(reading.total_g - 1.0F) < 0.001F && reading.centered,
            detail);
    }

    {
        moto::gmeter::Meter meter;
        moto::gmeter::Input push = at_rest();
        push.linear_x = 0.5F * kG;
        feed(meter, push, 40, 0);
        const auto reading = meter.update(push, 1'000);
        char detail[96];
        std::snprintf(detail, sizeof(detail),
                      "ball=(%.3f,%.3f) dynamic=%.3fg", reading.ball_x,
                      reading.ball_y, reading.dynamic_g);
        add("gmeter:push-moves-ball-right",
            reading.ball_x > 0.3F && std::abs(reading.ball_y) < 0.01F, detail);
    }

    {
        moto::gmeter::Meter meter;
        moto::gmeter::Input push = at_rest();
        push.linear_x = 0.8F * kG;
        feed(meter, push, 40, 0);
        const moto::gmeter::Input rest = at_rest();
        feed(meter, rest, 60, 1'025);
        const auto reading = meter.update(rest, 2'600);
        char detail[96];
        std::snprintf(detail, sizeof(detail), "ball=(%.4f,%.4f)",
                      reading.ball_x, reading.ball_y);
        add("gmeter:release-recentres-ball", reading.centered, detail);
    }

    {
        moto::gmeter::Meter meter;
        moto::gmeter::Input hard = at_rest();
        hard.linear_x = 4.0F * kG;
        hard.linear_y = 3.0F * kG;
        feed(meter, hard, 120, 0);
        const auto reading = meter.update(hard, 3'000);
        const float radius = std::sqrt(reading.ball_x * reading.ball_x +
                                       reading.ball_y * reading.ball_y);
        char detail[96];
        std::snprintf(detail, sizeof(detail), "radius=%.4f of 1.0", radius);
        add("gmeter:hard-stop-clamps-to-rim",
            radius > 0.95F && radius <= 1.0001F, detail);
    }

    {
        moto::gmeter::Meter meter;
        moto::gmeter::Input jitter = at_rest();
        jitter.linear_x = 0.02F * kG;
        jitter.linear_y = -0.02F * kG;
        feed(meter, jitter, 80, 0);
        const auto reading = meter.update(jitter, 2'000);
        add("gmeter:vibration-deadband-centres",
            reading.centered && reading.ball_x == 0.0F,
            "0.028 g of vibration must not offset the ball");
    }

    {
        using moto::gmeter::Severity;
        using moto::gmeter::severity;
        const bool ok = severity(0.0F) == Severity::Calm &&
                        severity(0.5F) == Severity::Moderate &&
                        severity(0.9F) == Severity::High &&
                        severity(1.5F) == Severity::Extreme;
        add("gmeter:severity-thresholds", ok,
            "0.00 calm, 0.50 moderate, 0.90 high, 1.50 extreme");
    }

    return checks;
}

std::vector<GoldenCheck> RunAllSelfTests() {
    std::vector<GoldenCheck> checks = RunGoldenSelfTest();
    std::vector<GoldenCheck> gmeter = RunGmeterSelfTest();
    checks.insert(checks.end(), gmeter.begin(), gmeter.end());
    return checks;
}

}  // namespace moto::android
