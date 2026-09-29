#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>

// Rider-facing G meter: the ball shows *dynamic* acceleration (gravity
// removed), so a level, stationary bike centres it, and the readout shows the
// resultant specific force plus each axis in g.
//
// Pure logic on purpose - no LVGL, no ESP-IDF - so the same code is covered by
// a host test that runs in CI without a display, exactly like
// motion_heading_fusion.hpp.
//
// Coordinates are DISPLAY space, not sensor space:
//   +x -> screen right, +y -> screen up, deflection clamped to the unit circle.
// Mapping the board's IMU axes onto display axes depends on how the unit is
// mounted, so that mapping lives with the caller (motion_heading_sensor.cpp)
// instead of being baked in here with an unverified assumption.
namespace moto::gmeter {

constexpr float kStandardGravity = 9.80665F;

struct Config {
  // Deflection that reaches the rim. 1.0 g means a hard stop fills the ring.
  float full_scale_g = 1.0F;
  // Below this dynamic magnitude the ball is told to re-centre. Without it,
  // engine vibration would leave the ball permanently off-centre at rest.
  float deadband_g = 0.035F;
  // Low-pass time constant for the ball. Long enough to ignore frame vibration,
  // short enough that a real brake or turn is visible immediately.
  float smoothing_tau_ms = 110.0F;
  // Snap below this radius so "centred" is exact rather than 0.004 off.
  float settle_radius = 0.012F;
};

struct Input {
  // Dynamic acceleration in m/s^2 with the gravity estimate already removed.
  float linear_x = 0.0F;
  float linear_y = 0.0F;
  // Specific force including gravity, in m/s^2. This is what a G meter
  // conventionally reports: 1.00 g while parked level.
  float total_x = 0.0F;
  float total_y = 0.0F;
  float total_z = 0.0F;
};

struct Reading {
  // Ball position in display space, clamped to the unit circle.
  float ball_x = 0.0F;
  float ball_y = 0.0F;
  // Magnitude of the dynamic part, in g.
  float dynamic_g = 0.0F;
  // Resultant of |total| in g.
  float total_g = 0.0F;
  // Per-axis specific force in g.
  float axis_x_g = 0.0F;
  float axis_y_g = 0.0F;
  float axis_z_g = 0.0F;
  bool centered = true;
};

// How hard the rider is being pushed, used to tint the ball and the number.
// Thresholds are descriptive only; they never change the numeric readout.
enum class Severity : std::uint8_t {
  Calm = 0,
  Moderate,
  High,
  Extreme,
};

inline Severity severity(float dynamic_g) noexcept {
  if (!std::isfinite(dynamic_g) || dynamic_g < 0.35F) {
    return Severity::Calm;
  }
  if (dynamic_g < 0.75F) return Severity::Moderate;
  if (dynamic_g < 1.20F) return Severity::High;
  return Severity::Extreme;
}

class Meter {
 public:
  Meter() = default;
  explicit Meter(Config config) : config_(config) {}

  void reset() noexcept {
    ball_x_ = 0.0F;
    ball_y_ = 0.0F;
    last_sample_ms_ = 0;
    has_sample_ = false;
    previous_reading_ = Reading{};
  }

  [[nodiscard]] const Config& config() const noexcept { return config_; }

  Reading update(const Input& input, std::uint64_t sample_ms) noexcept {
    // A sample whose numbers are unusable must not move the ball at all; the
    // previous reading stays until real data arrives again.
    if (!std::isfinite(input.linear_x) || !std::isfinite(input.linear_y) ||
        !std::isfinite(input.total_x) || !std::isfinite(input.total_y) ||
        !std::isfinite(input.total_z)) {
      return previous_reading_;
    }

    Reading reading;
    reading.axis_x_g = input.total_x / kStandardGravity;
    reading.axis_y_g = input.total_y / kStandardGravity;
    reading.axis_z_g = input.total_z / kStandardGravity;
    reading.total_g = std::sqrt(input.total_x * input.total_x +
                                input.total_y * input.total_y +
                                input.total_z * input.total_z) /
                      kStandardGravity;

    const float dynamic_xy = std::sqrt(input.linear_x * input.linear_x +
                                       input.linear_y * input.linear_y);
    reading.dynamic_g = dynamic_xy / kStandardGravity;

    // Target in unit-circle space. Inside the deadband the target is exactly
    // the centre, which is what makes the ball return to zero at rest.
    float target_x = 0.0F;
    float target_y = 0.0F;
    if (reading.dynamic_g > config_.deadband_g &&
        config_.full_scale_g > 0.0F) {
      const float scale = 1.0F / (config_.full_scale_g * kStandardGravity);
      target_x = input.linear_x * scale;
      target_y = input.linear_y * scale;
      const float magnitude =
          std::sqrt(target_x * target_x + target_y * target_y);
      if (magnitude > 1.0F) {
        target_x /= magnitude;
        target_y /= magnitude;
      }
    }

    float alpha = 1.0F;
    if (has_sample_ && sample_ms > last_sample_ms_) {
      const float elapsed_ms =
          static_cast<float>(sample_ms - last_sample_ms_);
      alpha = config_.smoothing_tau_ms > 0.0F
                  ? 1.0F - std::exp(-elapsed_ms / config_.smoothing_tau_ms)
                  : 1.0F;
      alpha = std::clamp(alpha, 0.0F, 1.0F);
    }
    last_sample_ms_ = sample_ms;
    has_sample_ = true;

    ball_x_ += (target_x - ball_x_) * alpha;
    ball_y_ += (target_y - ball_y_) * alpha;

    // Clamp to the ring's unit circle so the ball can never leave the dial.
    const float radius = std::sqrt(ball_x_ * ball_x_ + ball_y_ * ball_y_);
    if (radius > 1.0F) {
      ball_x_ /= radius;
      ball_y_ /= radius;
    } else if (radius < config_.settle_radius && target_x == 0.0F &&
               target_y == 0.0F) {
      ball_x_ = 0.0F;
      ball_y_ = 0.0F;
    }

    reading.ball_x = ball_x_;
    reading.ball_y = ball_y_;
    reading.centered = ball_x_ == 0.0F && ball_y_ == 0.0F;
    previous_reading_ = reading;
    return reading;
  }

 private:
  Config config_{};
  float ball_x_ = 0.0F;
  float ball_y_ = 0.0F;
  std::uint64_t last_sample_ms_ = 0;
  bool has_sample_ = false;
  Reading previous_reading_{};
};

}  // namespace moto::gmeter
