#pragma once

#include <cstdint>

#include "esp_err.h"

/**
 * One accelerometer sample prepared for the G meter.
 *
 * Values are already in DISPLAY space with the gravity estimate removed from
 * the linear pair, so the consumer never has to know how the board is mounted.
 * Gravity removal matters: a mounted, level bike still measures 1 g upward, and
 * a G meter that used the raw signal would show the ball pinned to one edge
 * whenever the rider is parked.
 */
struct AccelSample {
  float linear_x = 0.0F;  // Display right+, gravity removed, m/s^2.
  float linear_y = 0.0F;  // Display up+, gravity removed, m/s^2.
  float total_x = 0.0F;   // Display right+, includes gravity.
  float total_y = 0.0F;   // Display up+, includes gravity.
  float total_z = 0.0F;   // Out of the display, includes gravity.
  std::uint64_t sample_ms = 0;
};

class MotionHeadingSensor {
 public:
  using SampleCallback = void (*)(float heading_rate_dps,
                                  std::uint64_t sample_ms,
                                  void* context);
  using AccelCallback = void (*)(const AccelSample& sample, void* context);

  MotionHeadingSensor() = default;
  MotionHeadingSensor(const MotionHeadingSensor&) = delete;
  MotionHeadingSensor& operator=(const MotionHeadingSensor&) = delete;

  // Starts one low-priority 125 Hz sensor task. Failure is non-fatal: phone
  // course navigation continues to work without the local latency filler.
  //
  // accel_callback is optional and is published at roughly 40 Hz (not 125 Hz)
  // because its consumer drives LVGL, which already refreshes at 40 Hz.
  esp_err_t start(SampleCallback callback, void* context,
                  AccelCallback accel_callback = nullptr,
                  void* accel_context = nullptr);

 private:
  static void task_entry(void* context);
  void run();

  SampleCallback callback_ = nullptr;
  void* callback_context_ = nullptr;
  AccelCallback accel_callback_ = nullptr;
  void* accel_context_ = nullptr;
  bool started_ = false;
};
