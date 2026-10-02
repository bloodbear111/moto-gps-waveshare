#include "motion_heading_fusion.hpp"
#include "motion_heading_axis.hpp"

#include <cmath>
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

void feed_rate(MotionHeadingFusion& fusion, float rate_dps,
               std::uint64_t start_ms, std::uint64_t end_ms) {
  for (std::uint64_t time = start_ms; time <= end_ms; time += 8) {
    fusion.integrate(rate_dps, time);
  }
}

void test_fast_gyro_turn_and_wrap() {
  MotionHeadingFusion fusion;
  fusion.anchor(350.0F, 8.0F, true);
  feed_rate(fusion, 90.0F, 8, 248);
  CHECK(fusion.heading_deg() > 5.0F);
  CHECK(fusion.heading_deg() < 20.0F);
}

void test_stationary_phone_course_does_not_drag_handlebar_heading() {
  MotionHeadingFusion fusion;
  fusion.anchor(12.0F, 0.0F, true);
  fusion.anchor(210.0F, 0.2F, true);
  CHECK(std::abs(fusion.heading_deg() - 12.0F) < 0.01F);
}

void test_moving_phone_course_corrects_gyro_drift_progressively() {
  MotionHeadingFusion fusion;
  fusion.anchor(10.0F, 8.0F, true);
  fusion.anchor(110.0F, 10.0F, true);
  CHECK(fusion.heading_deg() > 70.0F);
  CHECK(fusion.heading_deg() < 80.0F);
}

void test_first_phone_course_replaces_gyro_local_zero() {
  MotionHeadingFusion fusion;
  feed_rate(fusion, 90.0F, 8, 248);
  fusion.anchor(235.0F, 0.0F, true);
  CHECK(std::abs(fusion.heading_deg() - 235.0F) < 0.01F);
  // The first moving fix must also replace an old stationary heading in one
  // update instead of leaving the selected road behind the fixed rider arrow.
  fusion.anchor(90.0F, 8.0F, true);
  CHECK(std::abs(fusion.heading_deg() - 90.0F) < 0.01F);
  fusion.reset();
  feed_rate(fusion, -90.0F, 8, 248);
  fusion.anchor(180.0F, 12.0F, true);
  CHECK(std::abs(fusion.heading_deg() - 180.0F) < 0.01F);
}

void test_unusable_course_never_anchors() {
  MotionHeadingFusion fusion;
  fusion.anchor(90.0F, 12.0F, false);
  CHECK(!fusion.initialized());
  fusion.anchor(12.0F, 12.0F, true);
  fusion.anchor(180.0F, 12.0F, false);
  CHECK(std::abs(fusion.heading_deg() - 12.0F) < 0.01F);
}

void test_forward_facing_mount_yaw_does_not_reverse_at_zero_z() {
  const float front_tilt = clockwise_gravity_heading_rate(
      0.0F, 9.81F, 0.01F, 0.0F, -90.0F, 0.0F);
  const float back_tilt = clockwise_gravity_heading_rate(
      0.0F, 9.81F, -0.01F, 0.0F, -90.0F, 0.0F);
  CHECK(std::abs(front_tilt - 90.0F) < 0.01F);
  CHECK(std::abs(front_tilt - back_tilt) < 0.01F);
  // Rotating the mounting orientation leaves compass clockwise positive.
  CHECK(std::abs(clockwise_gravity_heading_rate(
      0.0F, 0.0F, 9.81F, 0.0F, 0.0F, -90.0F) - 90.0F) < 0.01F);
  CHECK(std::abs(clockwise_gravity_heading_rate(
      0.0F, 0.0F, -9.81F, 0.0F, 0.0F, 90.0F) - 90.0F) < 0.01F);
}

void test_large_sensor_gap_is_not_integrated() {
  MotionHeadingFusion fusion;
  fusion.anchor(42.0F, 5.0F, true);
  fusion.integrate(180.0F, 10);
  CHECK(!fusion.integrate(180.0F, 1'000));
  CHECK(std::abs(fusion.heading_deg() - 42.0F) < 0.01F);
}

float heading_with_optional_stale_sample(bool include_stale_sample) {
  MotionHeadingFusion fusion;
  fusion.anchor(90.0F, 8.0F, true);
  for (std::uint64_t time = 8; time <= 248; time += 8) {
    fusion.integrate(90.0F, time);
    if (include_stale_sample && time == 120) {
      fusion.integrate(90.0F, 116);
    }
  }
  return fusion.heading_deg();
}

void test_stale_timestamp_does_not_rewind_integration_clock() {
  const float clean = heading_with_optional_stale_sample(false);
  const float with_stale_sample = heading_with_optional_stale_sample(true);
  CHECK(std::abs(clean - with_stale_sample) < 0.01F);
}

void test_parked_bike_still_shows_a_deliberate_rotation() {
  MotionHeadingFusion fusion;
  fusion.anchor(30.0F, 8.0F, true);
  // Parking switches to the wider deadband, not to a freeze: a compass that
  // never moves reads as broken, and the rider does turn the bars while parked.
  fusion.anchor(30.0F, 0.4F, true);
  // Below the parked deadband: vibration and zero-offset must not move it.
  feed_rate(fusion, 1.5F, 8, 2'008);
  CHECK(std::abs(fusion.heading_deg() - 30.0F) < 0.01F);
  // Between 0.6 and 1.5 m/s the bike is still parked (hysteresis band).
  fusion.anchor(30.0F, 1.0F, true);
  feed_rate(fusion, 90.0F, 2'016, 3'016);
  CHECK(fusion.heading_deg() > 40.0F);
  // Pulling away uses the normal deadband and tracks the same rotation.
  fusion.anchor(30.0F, 6.0F, true);
  feed_rate(fusion, 90.0F, 4'024, 4'424);
  CHECK(fusion.heading_deg() > 45.0F);
  CHECK(fusion.heading_deg() < 80.0F);
  // reset() clears everything, including the parked state.
  fusion.reset();
  feed_rate(fusion, 90.0F, 8, 248);
  CHECK(fusion.heading_deg() > 5.0F);
}

}  // namespace

// Without a phone course the heading is relative to where the board booted -
// this board has no magnetometer, so there is no absolute reference - but it
// still tracks rotation. Freezing it until the phone anchored it is what made
// the compass look broken on the device.
void test_relative_heading_before_the_phone_anchors() {
  MotionHeadingFusion fusion;
  CHECK(!fusion.integrate(45.0F, 8));
  CHECK(!fusion.anchored());
  // Relative integration from the arbitrary local zero.
  CHECK(fusion.integrate(45.0F, 16));
  CHECK(fusion.initialized());
  CHECK(fusion.heading_deg() > 0.0F);

  // The phone's course turns it back into an absolute heading.
  fusion.anchor(90.0F, 8.0F, true);
  CHECK(fusion.anchored());
  feed_rate(fusion, 45.0F, 16, 256);
  CHECK(fusion.heading_deg() > 90.0F);
}

int main() {
  test_fast_gyro_turn_and_wrap();
  test_stationary_phone_course_does_not_drag_handlebar_heading();
  test_moving_phone_course_corrects_gyro_drift_progressively();
  test_first_phone_course_replaces_gyro_local_zero();
  test_unusable_course_never_anchors();
  test_forward_facing_mount_yaw_does_not_reverse_at_zero_z();
  test_large_sensor_gap_is_not_integrated();
  test_stale_timestamp_does_not_rewind_integration_clock();
  test_parked_bike_still_shows_a_deliberate_rotation();
  test_relative_heading_before_the_phone_anchors();

  if (failures != 0) {
    std::cerr << failures << " motion heading checks failed\n";
    return EXIT_FAILURE;
  }
  std::cout << "All motion heading checks passed\n";
  return EXIT_SUCCESS;
}
