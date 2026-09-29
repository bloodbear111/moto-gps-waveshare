#include "accel_gmeter.hpp"

#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <iostream>

namespace {

int failures = 0;

#define CHECK(condition)                                                   \
  do {                                                                     \
    if (!(condition)) {                                                    \
      std::cerr << __FILE__ << ':' << __LINE__                             \
                << " CHECK failed: " #condition << '\n';                  \
      ++failures;                                                          \
    }                                                                      \
  } while (false)

using moto::gmeter::Input;
using moto::gmeter::Meter;

constexpr float kG = moto::gmeter::kStandardGravity;

// A level, parked bike: gravity is all on +z, no dynamic component.
Input at_rest() {
  Input input;
  input.total_z = kG;
  return input;
}

// Drive the meter at a fixed 40 Hz cadence, matching the renderer.
void feed(Meter& meter, const Input& input, int samples,
          std::uint64_t start_ms = 0) {
  for (int index = 0; index < samples; ++index) {
    meter.update(input, start_ms + static_cast<std::uint64_t>(index) * 25);
  }
}

void test_rest_reports_one_g_and_a_centred_ball() {
  Meter meter;
  const auto reading = meter.update(at_rest(), 0);
  CHECK(std::abs(reading.total_g - 1.0F) < 0.001F);
  CHECK(reading.dynamic_g < 0.001F);
  CHECK(reading.centered);
  CHECK(reading.ball_x == 0.0F);
  CHECK(reading.ball_y == 0.0F);
}

void test_per_axis_readout_is_specific_force_in_g() {
  Meter meter;
  Input input;
  input.total_x = 0.5F * kG;
  input.total_y = -0.25F * kG;
  input.total_z = kG;
  const auto reading = meter.update(input, 0);
  CHECK(std::abs(reading.axis_x_g - 0.5F) < 0.001F);
  CHECK(std::abs(reading.axis_y_g + 0.25F) < 0.001F);
  CHECK(std::abs(reading.axis_z_g - 1.0F) < 0.001F);
}

void test_ball_follows_the_direction_of_acceleration() {
  Meter meter;
  Input input = at_rest();
  input.linear_x = 0.5F * kG;
  feed(meter, input, 40);
  const auto reading = meter.update(input, 1'000);
  CHECK(reading.ball_x > 0.3F);
  CHECK(std::abs(reading.ball_y) < 0.01F);
  CHECK(!reading.centered);

  Meter left;
  Input negative = at_rest();
  negative.linear_x = -0.5F * kG;
  feed(left, negative, 40);
  CHECK(left.update(negative, 1'000).ball_x < -0.3F);
}

void test_ball_returns_to_centre_when_acceleration_stops() {
  Meter meter;
  Input accelerating = at_rest();
  accelerating.linear_x = 0.8F * kG;
  feed(meter, accelerating, 40);
  CHECK(meter.update(accelerating, 1'000).ball_x > 0.5F);

  const auto rest = at_rest();
  feed(meter, rest, 60, 1'025);
  const auto settled = meter.update(rest, 2'600);
  CHECK(settled.centered);
  CHECK(settled.ball_x == 0.0F);
  CHECK(settled.ball_y == 0.0F);
}

void test_small_vibration_is_damped_instead_of_drifting_off_centre() {
  Meter meter;
  // Just under the deadband: the ball must never leave the middle.
  Input jitter = at_rest();
  jitter.linear_x = 0.02F * kG;
  jitter.linear_y = -0.02F * kG;
  feed(meter, jitter, 80);
  const auto reading = meter.update(jitter, 2'000);
  CHECK(reading.centered);
  CHECK(reading.ball_x == 0.0F);
}

void test_extreme_acceleration_is_clamped_to_the_ring() {
  Meter meter;
  Input hard = at_rest();
  hard.linear_x = 4.0F * kG;
  hard.linear_y = 3.0F * kG;
  feed(meter, hard, 120);
  const auto reading = meter.update(hard, 3'000);
  const float radius = std::sqrt(reading.ball_x * reading.ball_x +
                                 reading.ball_y * reading.ball_y);
  CHECK(radius <= 1.0001F);
  CHECK(radius > 0.95F);
  // Direction is preserved even when the magnitude saturates.
  CHECK(reading.ball_x > 0.0F);
  CHECK(reading.ball_y > 0.0F);
  CHECK(reading.ball_x / reading.ball_y > 1.2F);
  CHECK(reading.ball_x / reading.ball_y < 1.5F);
}

void test_non_finite_sample_does_not_move_the_ball() {
  Meter meter;
  Input accelerating = at_rest();
  accelerating.linear_x = 0.6F * kG;
  feed(meter, accelerating, 40);
  const auto before = meter.update(accelerating, 1'000);

  Input broken = at_rest();
  broken.linear_x = std::nanf("");
  const auto after = meter.update(broken, 1'025);
  CHECK(after.ball_x == before.ball_x);
  CHECK(after.ball_y == before.ball_y);
  CHECK(after.centered == before.centered);
}

void test_severity_thresholds_are_monotonic() {
  using moto::gmeter::Severity;
  using moto::gmeter::severity;
  CHECK(severity(0.0F) == Severity::Calm);
  CHECK(severity(0.34F) == Severity::Calm);
  CHECK(severity(0.36F) == Severity::Moderate);
  CHECK(severity(0.74F) == Severity::Moderate);
  CHECK(severity(0.76F) == Severity::High);
  CHECK(severity(1.19F) == Severity::High);
  CHECK(severity(1.21F) == Severity::Extreme);
  CHECK(severity(std::nanf("")) == Severity::Calm);
}

void test_configurable_full_scale_changes_only_the_deflection() {
  moto::gmeter::Config config;
  config.full_scale_g = 2.0F;
  Meter meter(config);
  Input half_g = at_rest();
  half_g.linear_x = 0.5F * kG;
  feed(meter, half_g, 80);
  const auto reading = meter.update(half_g, 2'000);
  // Half of a 2 g dial is a quarter of the radius, while the readout stays the
  // real acceleration.
  CHECK(reading.ball_x > 0.2F);
  CHECK(reading.ball_x < 0.3F);
  CHECK(std::abs(reading.dynamic_g - 0.5F) < 0.01F);
}

void test_reset_clears_the_ball() {
  Meter meter;
  Input accelerating = at_rest();
  accelerating.linear_y = 0.9F * kG;
  feed(meter, accelerating, 40);
  CHECK(!meter.update(accelerating, 1'000).centered);
  meter.reset();
  const auto after_reset = meter.update(at_rest(), 1'025);
  CHECK(after_reset.centered);
}

void test_smoothing_is_monotonic_toward_the_target() {
  Meter meter;
  Input step = at_rest();
  step.linear_y = 0.5F * kG;
  float previous = 0.0F;
  for (int index = 1; index <= 20; ++index) {
    const auto reading = meter.update(
        step, static_cast<std::uint64_t>(index) * 25);
    // Overshoot would make the ball wobble around the true direction.
    CHECK(reading.ball_y >= previous - 1e-6F);
    CHECK(reading.ball_y <= 0.5001F);
    previous = reading.ball_y;
  }
}

}  // namespace

int main() {
  test_rest_reports_one_g_and_a_centred_ball();
  test_per_axis_readout_is_specific_force_in_g();
  test_ball_follows_the_direction_of_acceleration();
  test_ball_returns_to_centre_when_acceleration_stops();
  test_small_vibration_is_damped_instead_of_drifting_off_centre();
  test_extreme_acceleration_is_clamped_to_the_ring();
  test_non_finite_sample_does_not_move_the_ball();
  test_severity_thresholds_are_monotonic();
  test_configurable_full_scale_changes_only_the_deflection();
  test_reset_clears_the_ball();
  test_smoothing_is_monotonic_toward_the_target();

  if (failures != 0) {
    std::cerr << failures << " G meter checks failed\n";
    return EXIT_FAILURE;
  }
  std::cout << "All G meter checks passed\n";
  return EXIT_SUCCESS;
}
