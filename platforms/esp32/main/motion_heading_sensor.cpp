#include "motion_heading_sensor.h"
#include "motion_heading_axis.hpp"

#include <algorithm>
#include <cmath>

#include "bsp/esp-bsp.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

// qmi8658.h defines M_PI globally. This adapter does not use that macro and
// removes it after the official component header to keep C++ <cmath> clean.
#include "qmi8658.h"
#ifdef M_PI
#undef M_PI
#endif

namespace {
constexpr char kTag[] = "motion_heading";
constexpr TickType_t kSampleDelay = pdMS_TO_TICKS(8);  // 125 Hz target.
constexpr int kCalibrationSamples = 120;
constexpr std::uint64_t kCalibrationDeadlineMs = 4'000;
constexpr float kMinimumGravity = 6.8F;
constexpr float kMaximumGravity = 12.8F;
// Publish acceleration at about 40 Hz. The consumer drives LVGL, whose refresh
// period is 25 ms, so 125 Hz would only add contention without adding frames.
constexpr int kAccelPublishDivisor = 3;

// How the QMI8658 axes map onto the display's right/up axes.
//
// With the board in its normal portrait handlebar mount, facing the rider, the
// sensor +x axis points to screen right and +y points to screen up. These two
// signs are the only place to change if a differently rotated mount makes the
// ball track the wrong way; everything downstream works in display space.
constexpr float kDisplayXSign = 1.0F;
constexpr float kDisplayYSign = 1.0F;

struct GravityEstimate {
  float x = 0.0F;
  float y = 0.0F;
  float z = 1.0F;
  bool initialized = false;
};

std::uint64_t monotonic_ms() {
  return static_cast<std::uint64_t>(esp_timer_get_time()) / 1'000U;
}

float magnitude(float x, float y, float z) {
  return std::sqrt(x * x + y * y + z * z);
}

AccelSample to_display_acceleration(const GravityEstimate& gravity,
                                    const qmi8658_data_t& sample,
                                    std::uint64_t sample_ms) {
  AccelSample out;
  out.linear_x = kDisplayXSign * (sample.accelX - gravity.x);
  out.linear_y = kDisplayYSign * (sample.accelY - gravity.y);
  out.total_x = kDisplayXSign * sample.accelX;
  out.total_y = kDisplayYSign * sample.accelY;
  out.total_z = sample.accelZ;
  out.sample_ms = sample_ms;
  return out;
}

bool update_gravity(GravityEstimate& gravity, const qmi8658_data_t& sample,
                    float blend) {
  const float length = magnitude(sample.accelX, sample.accelY, sample.accelZ);
  if (!std::isfinite(length) || length < kMinimumGravity ||
      length > kMaximumGravity) {
    return gravity.initialized;
  }
  if (!gravity.initialized) {
    gravity.x = sample.accelX;
    gravity.y = sample.accelY;
    gravity.z = sample.accelZ;
    gravity.initialized = true;
  } else {
    gravity.x += (sample.accelX - gravity.x) * blend;
    gravity.y += (sample.accelY - gravity.y) * blend;
    gravity.z += (sample.accelZ - gravity.z) * blend;
  }
  return true;
}

// Project angular velocity onto the board's gravity axis so a moderately
// tilted or forward-facing mount still responds to a turn around vertical.
float clockwise_heading_rate(const GravityEstimate& gravity,
                             const qmi8658_data_t& sample) {
  if (!gravity.initialized) return 0.0F;
  return clockwise_gravity_heading_rate(
      gravity.x, gravity.y, gravity.z,
      sample.gyroX, sample.gyroY, sample.gyroZ);
}
}  // namespace

esp_err_t MotionHeadingSensor::start(SampleCallback callback, void* context,
                                    AccelCallback accel_callback,
                                    void* accel_context) {
  if (callback == nullptr) return ESP_ERR_INVALID_ARG;
  if (started_) return ESP_ERR_INVALID_STATE;
  callback_ = callback;
  callback_context_ = context;
  accel_callback_ = accel_callback;
  accel_context_ = accel_context;
  // Sensor callbacks reach the presenter and LVGL adapter. Keep enough margin
  // for those nested frames even though the large map snapshot now remains in
  // PhoneNavBridge member storage.
  if (xTaskCreate(task_entry, "moto_qmi_heading", 8'192, this, 3, nullptr) !=
      pdPASS) {
    callback_ = nullptr;
    callback_context_ = nullptr;
    accel_callback_ = nullptr;
    accel_context_ = nullptr;
    return ESP_ERR_NO_MEM;
  }
  started_ = true;
  return ESP_OK;
}

void MotionHeadingSensor::task_entry(void* context) {
  static_cast<MotionHeadingSensor*>(context)->run();
  vTaskDelete(nullptr);
}

void MotionHeadingSensor::run() {
  qmi8658_dev_t device{};
  const esp_err_t init = qmi8658_init(
      &device, bsp_i2c_get_handle(), QMI8658_ADDRESS_HIGH);
  if (init != ESP_OK) {
    ESP_LOGW(kTag, "QMI8658 unavailable; using phone course only: %s",
             esp_err_to_name(init));
    return;
  }
  ESP_ERROR_CHECK_WITHOUT_ABORT(
      qmi8658_set_accel_range(&device, QMI8658_ACCEL_RANGE_4G));
  ESP_ERROR_CHECK_WITHOUT_ABORT(
      qmi8658_set_accel_odr(&device, QMI8658_ACCEL_ODR_125HZ));
  ESP_ERROR_CHECK_WITHOUT_ABORT(
      qmi8658_set_gyro_range(&device, QMI8658_GYRO_RANGE_512DPS));
  ESP_ERROR_CHECK_WITHOUT_ABORT(
      qmi8658_set_gyro_odr(&device, QMI8658_GYRO_ODR_125HZ));
  qmi8658_set_accel_unit_mps2(&device, true);
  qmi8658_set_gyro_unit_dps(&device, true);

  GravityEstimate gravity;
  float bias_sum = 0.0F;
  int stable_samples = 0;
  int accel_divisor = 0;
  const std::uint64_t calibration_start = monotonic_ms();
  qmi8658_data_t data{};
  TickType_t next_sample_tick = xTaskGetTickCount();
  while (stable_samples < kCalibrationSamples &&
         monotonic_ms() - calibration_start < kCalibrationDeadlineMs) {
    bool ready = false;
    if (qmi8658_is_data_ready(&device, &ready) == ESP_OK && ready &&
        qmi8658_read_sensor_data(&device, &data) == ESP_OK) {
      update_gravity(gravity, data, 0.08F);
      // Publish during calibration as well: otherwise the G meter would sit
      // empty for up to four seconds after power-on.
      if (accel_callback_ != nullptr &&
          (++accel_divisor % kAccelPublishDivisor) == 0) {
        accel_callback_(to_display_acceleration(gravity, data, monotonic_ms()),
                        accel_context_);
      }
      const float projected = clockwise_heading_rate(gravity, data);
      const float angular = magnitude(data.gyroX, data.gyroY, data.gyroZ);
      if (std::isfinite(projected) && angular < 4.0F) {
        bias_sum += projected;
        ++stable_samples;
      } else {
        bias_sum = 0.0F;
        stable_samples = 0;
      }
    }
    xTaskDelayUntil(&next_sample_tick, kSampleDelay);
  }

  float bias_dps = stable_samples > 0 ? bias_sum / stable_samples : 0.0F;
  ESP_LOGI(kTag,
           "QMI8658 yaw assist ready (bias %.3f dps, %d stable samples)",
           static_cast<double>(bias_dps), stable_samples);

  int consecutive_failures = 0;
  next_sample_tick = xTaskGetTickCount();
  while (true) {
    bool ready = false;
    esp_err_t result = qmi8658_is_data_ready(&device, &ready);
    if (result == ESP_OK && ready) {
      result = qmi8658_read_sensor_data(&device, &data);
      if (result == ESP_OK) {
        consecutive_failures = 0;
        update_gravity(gravity, data, 0.025F);
        if (accel_callback_ != nullptr &&
            (++accel_divisor % kAccelPublishDivisor) == 0) {
          accel_callback_(
              to_display_acceleration(gravity, data, monotonic_ms()),
              accel_context_);
        }
        const float raw_rate = clockwise_heading_rate(gravity, data);
        const float corrected_rate = raw_rate - bias_dps;
        const float angular = magnitude(data.gyroX, data.gyroY, data.gyroZ);
        const float accel = magnitude(data.accelX, data.accelY, data.accelZ);
        // Learn residual bias only during convincingly quiet intervals.
        if (std::abs(corrected_rate) < 1.1F && angular < 2.0F &&
            accel > 8.2F && accel < 11.4F) {
          bias_dps += (raw_rate - bias_dps) * 0.0008F;
        }
        callback_(corrected_rate, monotonic_ms(), callback_context_);
      }
    }
    if (result != ESP_OK && ++consecutive_failures == 25) {
      ESP_LOGW(kTag, "QMI8658 read errors continue: %s",
               esp_err_to_name(result));
    }
    xTaskDelayUntil(&next_sample_tick, kSampleDelay);
  }
}
