// Board port for the Waveshare ESP32-S3-Touch-LCD-1.85B.
//
// This board is NOT the AMOLED-1.75C the rest of the project targets, and it
// has no published BSP, so the panel, touch and GPIO setup are written here
// against Espressif's own components:
//
//   panel  ST77916 over 4-bit QSPI  (espressif/esp_lcd_st77916)
//   touch  CST816S over I2C         (espressif/esp_lcd_touch_cst816s)
//   LVGL   espressif/esp_lvgl_adapter, the same adapter the 1.75C port uses
//
// Pin map comes from the Waveshare ESP32-S3-Touch-LCD-1.85 hardware table
// (LCD_SDA0..3, LCD_SCK/CS/TE, TP_SDA/SCL/INT). Two of the resets are not on
// the SoC at all: TP_RST and LCD_RST live on the TCA9554 I2C expander, so
// nothing on either bus answers until that expander has been configured.

#include "board_port.h"

#include <cstddef>
#include <cstdint>

#include "driver/gpio.h"
#include "driver/i2c_master.h"
#include "driver/spi_master.h"
#include "esp_check.h"
#include "esp_err.h"
#include "esp_heap_caps.h"
#include "esp_lcd_panel_io.h"
#include "esp_lcd_panel_ops.h"
#include "esp_lcd_panel_vendor.h"
#include "esp_lcd_st77916.h"
#include "esp_lcd_touch.h"
#include "esp_lcd_touch_cst816s.h"
#include "esp_log.h"
#include "esp_lv_adapter.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "sdkconfig.h"

namespace {
constexpr char kTag[] = "board_1_85b";

// --- Panel -----------------------------------------------------------------
// 1.85 inch round LCD, 360x360, ST77916 on a 4-bit QSPI bus. The four data
// lines are NOT consecutive GPIOs on this board.
constexpr gpio_num_t kLcdSclk = GPIO_NUM_40;
constexpr gpio_num_t kLcdData0 = GPIO_NUM_46;
constexpr gpio_num_t kLcdData1 = GPIO_NUM_45;
constexpr gpio_num_t kLcdData2 = GPIO_NUM_42;
constexpr gpio_num_t kLcdData3 = GPIO_NUM_41;
constexpr gpio_num_t kLcdCs = GPIO_NUM_21;
constexpr gpio_num_t kLcdTe = GPIO_NUM_18;
constexpr gpio_num_t kLcdBacklight = GPIO_NUM_5;
constexpr spi_host_device_t kLcdHost = SPI2_HOST;
constexpr int kLcdPixelClockHz = 40 * 1000 * 1000;

// --- Touch -----------------------------------------------------------------
// CST816S sits on its own I2C bus, separate from the IMU/RTC/expander bus.
constexpr gpio_num_t kTouchSda = GPIO_NUM_1;
constexpr gpio_num_t kTouchScl = GPIO_NUM_3;
constexpr gpio_num_t kTouchInt = GPIO_NUM_4;

// --- IMU / RTC / IO expander bus -------------------------------------------
constexpr gpio_num_t kSensorSda = GPIO_NUM_11;
constexpr gpio_num_t kSensorScl = GPIO_NUM_10;

// --- TCA9554 ---------------------------------------------------------------
// The expander drives the two active-low resets, so it must be configured
// before the panel or the touch controller can answer. Waveshare numbers these
// EXIO1..EXIO8; the mapping below assumes EXIO1 is bit 0, which is the only
// assumption in this port that a bench check should confirm first if the
// display or touch stays dark.
constexpr int kExpanderTouchResetBit = 0;  // EXIO1 -> TP_RST
constexpr int kExpanderLcdResetBit = 1;    // EXIO2 -> LCD_RST
constexpr int kExpanderSdCsBit = 2;        // EXIO3 -> SD card CS
constexpr uint8_t kExpanderInputRegister = 0x00;
constexpr uint8_t kExpanderOutputRegister = 0x01;
constexpr uint8_t kExpanderConfigRegister = 0x03;
constexpr uint8_t kExpanderAddressFirst = 0x20;
constexpr uint8_t kExpanderAddressLast = 0x27;
// The alias address is 0x38/0x39 when the expander's A2 strap is tied high.
constexpr uint8_t kExpanderAliasAddressFirst = 0x38;
constexpr uint8_t kExpanderAliasAddressLast = 0x39;
constexpr int kI2cTimeoutMs = 100;

constexpr int kDrawBufferHeight = 40;
constexpr int kResetPulseMs = 20;

i2c_master_bus_handle_t touch_bus = nullptr;
i2c_master_bus_handle_t sensor_bus = nullptr;
i2c_master_dev_handle_t expander = nullptr;
esp_lcd_panel_handle_t panel = nullptr;
esp_lcd_panel_io_handle_t panel_io = nullptr;
esp_lcd_touch_handle_t touch = nullptr;
lv_display_t* display = nullptr;
bool display_revealed = false;

std::uint8_t expander_output = 0xFF;

void delay_ms(std::uint32_t milliseconds) {
  vTaskDelay(pdMS_TO_TICKS(milliseconds));
}

esp_err_t i2c_bus_create(i2c_port_t port, gpio_num_t sda, gpio_num_t scl,
                         i2c_master_bus_handle_t* out) {
  i2c_master_bus_config_t config = {};
  config.clk_source = I2C_CLK_SRC_DEFAULT;
  config.i2c_port = port;
  config.sda_io_num = sda;
  config.scl_io_num = scl;
  config.glitch_ignore_cnt = 7;
  config.flags.enable_internal_pullup = 1;
  return i2c_new_master_bus(&config, out);
}

esp_err_t expander_write_register(std::uint8_t reg, std::uint8_t value) {
  if (expander == nullptr) return ESP_ERR_INVALID_STATE;
  const std::uint8_t payload[2] = {reg, value};
  return i2c_master_transmit(expander, payload, sizeof(payload),
                             kI2cTimeoutMs);
}

// Scans the sensor bus, then the touch bus, for the TCA9554. The factory
// firmware keeps it on the IMU/RTC bus, but probing both costs nothing and
// removes a guess from the bring-up.
// Logs every address that acknowledges on a bus. Bring-up on an undocumented
// board otherwise turns into guesswork about which pin pair is which bus.
void log_i2c_scan(i2c_master_bus_handle_t bus, const char* label) {
  if (bus == nullptr) return;
  int found = 0;
  for (std::uint8_t address = 0x08; address <= 0x77; ++address) {
    if (i2c_master_probe(bus, address, kI2cTimeoutMs) == ESP_OK) {
      ESP_LOGI(kTag, "%s bus: device at 0x%02X", label, address);
      ++found;
    }
  }
  if (found == 0) {
    ESP_LOGW(kTag, "%s bus: no I2C device responded", label);
  }
}

// A real TCA9554 echoes the configuration byte it was just given. Requiring
// that read-back keeps a scan from writing into some unrelated chip that merely
// shares the address.
bool expander_identifies(std::uint8_t address) {
  i2c_device_config_t device_config = {};
  device_config.dev_addr_length = I2C_ADDR_BIT_LEN_7;
  device_config.device_address = address;
  device_config.scl_speed_hz = 400'000;

  i2c_master_dev_handle_t candidate = nullptr;
  if (i2c_master_bus_add_device(sensor_bus != nullptr ? sensor_bus : touch_bus,
                                &device_config, &candidate) != ESP_OK) {
    return false;
  }

  const std::uint8_t probe_value = 0xE0;  // 5 outputs, rest inputs
  const std::uint8_t payload[2] = {kExpanderConfigRegister, probe_value};
  bool matches = false;
  if (i2c_master_transmit(candidate, payload, sizeof(payload), kI2cTimeoutMs) ==
      ESP_OK) {
    const std::uint8_t read_request = kExpanderConfigRegister;
    std::uint8_t read_back = 0;
    if (i2c_master_transmit_receive(candidate, &read_request, 1, &read_back, 1,
                                    kI2cTimeoutMs) == ESP_OK) {
      matches = read_back == probe_value;
    }
  }
  i2c_master_bus_rm_device(candidate);
  return matches;
}

esp_err_t expander_probe() {
  for (int pass = 0; pass < 2; ++pass) {
    i2c_master_bus_handle_t bus = (pass == 0) ? sensor_bus : touch_bus;
    if (bus == nullptr) continue;
    for (std::uint8_t address = 0x08; address <= 0x77; ++address) {
      if (i2c_master_probe(bus, address, kI2cTimeoutMs) != ESP_OK) continue;
      const bool in_primary_range =
          address >= kExpanderAddressFirst && address <= kExpanderAddressLast;
      const bool in_alias_range = address >= kExpanderAliasAddressFirst &&
                                  address <= kExpanderAliasAddressLast;
      if (!in_primary_range && !in_alias_range) continue;
      if (!expander_identifies(address)) continue;

      i2c_device_config_t device_config = {};
      device_config.dev_addr_length = I2C_ADDR_BIT_LEN_7;
      device_config.device_address = address;
      device_config.scl_speed_hz = 400'000;
      if (i2c_master_bus_add_device(bus, &device_config, &expander) != ESP_OK) {
        return ESP_FAIL;
      }
      // P0..P4 are outputs (resets and SD CS); the rest stay inputs. Reading
      // back the configuration register proves the device really is a TCA9554
      // rather than an unrelated chip that happens to share the address.
      const std::uint8_t outputs = 0x1F;
      const std::uint8_t config_value = static_cast<std::uint8_t>(~outputs);
      esp_err_t result = expander_write_register(kExpanderConfigRegister,
                                                 config_value);
      if (result != ESP_OK) {
        i2c_master_bus_rm_device(expander);
        expander = nullptr;
        continue;
      }
      result = expander_write_register(kExpanderOutputRegister, expander_output);
      if (result != ESP_OK) {
        i2c_master_bus_rm_device(expander);
        expander = nullptr;
        continue;
      }
      ESP_LOGI(kTag, "TCA9554 expander at 0x%02X on %s bus", address,
               pass == 0 ? "sensor" : "touch");
      return ESP_OK;
    }
  }
  ESP_LOGE(kTag, "no TCA9554 expander found on either I2C bus");
  return ESP_ERR_NOT_FOUND;
}

esp_err_t expander_set_bit(int bit, bool high) {
  if (high) {
    expander_output |= static_cast<std::uint8_t>(1U << bit);
  } else {
    expander_output &= static_cast<std::uint8_t>(~(1U << bit));
  }
  return expander_write_register(kExpanderOutputRegister, expander_output);
}

// Both resets are active low and both live on the expander, so they are pulsed
// together once the expander answers.
esp_err_t release_panel_and_touch_resets() {
  ESP_RETURN_ON_ERROR(expander_set_bit(kExpanderSdCsBit, true), kTag,
                      "SD CS release failed");
  ESP_RETURN_ON_ERROR(expander_set_bit(kExpanderLcdResetBit, false), kTag,
                      "LCD reset assert failed");
  ESP_RETURN_ON_ERROR(expander_set_bit(kExpanderTouchResetBit, false), kTag,
                      "touch reset assert failed");
  delay_ms(kResetPulseMs);
  ESP_RETURN_ON_ERROR(expander_set_bit(kExpanderLcdResetBit, true), kTag,
                      "LCD reset release failed");
  ESP_RETURN_ON_ERROR(expander_set_bit(kExpanderTouchResetBit, true), kTag,
                      "touch reset release failed");
  delay_ms(kResetPulseMs);
  return ESP_OK;
}

esp_err_t initialize_backlight() {
  gpio_config_t config = {};
  config.pin_bit_mask = 1ULL << static_cast<int>(kLcdBacklight);
  config.mode = GPIO_MODE_OUTPUT;
  config.pull_up_en = GPIO_PULLUP_DISABLE;
  config.pull_down_en = GPIO_PULLDOWN_DISABLE;
  config.intr_type = GPIO_INTR_DISABLE;
  ESP_RETURN_ON_ERROR(gpio_config(&config), kTag, "backlight GPIO setup failed");
  // Kept off until the caller has drawn the first intentional frame.
  ESP_RETURN_ON_ERROR(gpio_set_level(kLcdBacklight, 0), kTag,
                      "backlight off failed");
  return ESP_OK;
}

esp_err_t initialize_panel() {
  spi_bus_config_t bus_config = {};
  bus_config.sclk_io_num = kLcdSclk;
  bus_config.data0_io_num = kLcdData0;
  bus_config.data1_io_num = kLcdData1;
  bus_config.data2_io_num = kLcdData2;
  bus_config.data3_io_num = kLcdData3;
  bus_config.max_transfer_sz =
      MOTO_DISPLAY_WIDTH * kDrawBufferHeight * sizeof(std::uint16_t);
  bus_config.flags = SPICOMMON_BUSFLAG_MASTER | SPICOMMON_BUSFLAG_QUAD;
  ESP_RETURN_ON_ERROR(spi_bus_initialize(kLcdHost, &bus_config,
                                         SPI_DMA_CH_AUTO),
                      kTag, "QSPI bus init failed");

  esp_lcd_panel_io_spi_config_t io_config = {};
  io_config.cs_gpio_num = kLcdCs;
  io_config.dc_gpio_num = -1;
  io_config.spi_mode = 0;
  io_config.pclk_hz = kLcdPixelClockHz;
  io_config.trans_queue_depth = 10;
  io_config.lcd_cmd_bits = 32;
  io_config.lcd_param_bits = 8;
  io_config.flags.quad_mode = 1;
  ESP_RETURN_ON_ERROR(
      esp_lcd_new_panel_io_spi(static_cast<esp_lcd_spi_bus_handle_t>(kLcdHost),
                               &io_config, &panel_io),
      kTag, "panel IO create failed");

  st77916_vendor_config_t vendor_config = {};
  vendor_config.flags.use_qspi_interface = 1;

  esp_lcd_panel_dev_config_t panel_config = {};
  // Reset is on the expander, not on a SoC GPIO.
  panel_config.reset_gpio_num = -1;
  panel_config.rgb_ele_order = LCD_RGB_ELEMENT_ORDER_RGB;
  panel_config.bits_per_pixel = 16;
  panel_config.vendor_config = &vendor_config;
  ESP_RETURN_ON_ERROR(esp_lcd_new_panel_st77916(panel_io, &panel_config, &panel),
                      kTag, "ST77916 panel create failed");
  ESP_RETURN_ON_ERROR(esp_lcd_panel_reset(panel), kTag, "panel reset failed");
  ESP_RETURN_ON_ERROR(esp_lcd_panel_init(panel), kTag, "panel init failed");
  ESP_RETURN_ON_ERROR(esp_lcd_panel_invert_color(panel, true), kTag,
                      "panel invert failed");
  ESP_RETURN_ON_ERROR(esp_lcd_panel_disp_on_off(panel, false), kTag,
                      "panel blank failed");
  return ESP_OK;
}

esp_err_t initialize_touch() {
  esp_lcd_panel_io_i2c_config_t io_config = {};
  io_config.dev_addr = ESP_LCD_TOUCH_IO_I2C_CST816S_ADDRESS;
  io_config.control_phase_bytes = 1;
  io_config.dc_bit_offset = 0;
  io_config.lcd_cmd_bits = 8;
  io_config.flags.disable_control_phase = 1;
  io_config.scl_speed_hz = 400'000;

  esp_lcd_panel_io_handle_t touch_io = nullptr;
  ESP_RETURN_ON_ERROR(
      esp_lcd_new_panel_io_i2c(touch_bus, &io_config, &touch_io), kTag,
      "touch IO create failed");

  esp_lcd_touch_config_t touch_config = {};
  touch_config.x_max = MOTO_DISPLAY_WIDTH;
  touch_config.y_max = MOTO_DISPLAY_HEIGHT;
  // Reset is on the TCA9554 expander, not on a SoC GPIO.
  touch_config.rst_gpio_num = GPIO_NUM_NC;
  touch_config.int_gpio_num = kTouchInt;
  // The panel is mounted the same way round as the AMOLED build, so the same
  // mirroring is applied here.
  touch_config.flags.swap_xy = 0;
  touch_config.flags.mirror_x = 1;
  touch_config.flags.mirror_y = 1;
  ESP_RETURN_ON_ERROR(esp_lcd_touch_new_i2c_cst816s(touch_io, &touch_config,
                                                    &touch),
                      kTag, "CST816S init failed");
  return ESP_OK;
}
}  // namespace

extern "C" esp_err_t board_port_init(void) {
  ESP_LOGI(kTag, "bringing up ESP32-S3-Touch-LCD-1.85B (%dx%d)",
           MOTO_DISPLAY_WIDTH, MOTO_DISPLAY_HEIGHT);

  // Two separate physical buses: the IMU, RTC and IO expander share one, and
  // the touch controller has its own.
  ESP_RETURN_ON_ERROR(
      i2c_bus_create(I2C_NUM_0, kSensorSda, kSensorScl, &sensor_bus), kTag,
      "sensor I2C bus init failed");
  ESP_RETURN_ON_ERROR(
      i2c_bus_create(I2C_NUM_1, kTouchSda, kTouchScl, &touch_bus), kTag,
      "touch I2C bus init failed");
  log_i2c_scan(sensor_bus, "sensor");
  log_i2c_scan(touch_bus, "touch");

  // Without the expander the panel and touch stay in reset, so this failing is
  // fatal rather than cosmetic.
  ESP_RETURN_ON_ERROR(expander_probe(), kTag, "IO expander unavailable");
  ESP_RETURN_ON_ERROR(release_panel_and_touch_resets(), kTag,
                      "reset release failed");
  ESP_RETURN_ON_ERROR(initialize_backlight(), kTag, "backlight init failed");
  ESP_RETURN_ON_ERROR(initialize_panel(), kTag, "panel init failed");
  ESP_RETURN_ON_ERROR(initialize_touch(), kTag, "touch init failed");

  const esp_lv_adapter_config_t adapter_config = ESP_LV_ADAPTER_DEFAULT_CONFIG();
  ESP_RETURN_ON_ERROR(esp_lv_adapter_init(&adapter_config), kTag,
                      "LVGL adapter init failed");

  esp_lv_adapter_display_config_t display_config =
      ESP_LV_ADAPTER_DISPLAY_SPI_WITH_PSRAM_DEFAULT_CONFIG(
          panel, panel_io, MOTO_DISPLAY_WIDTH, MOTO_DISPLAY_HEIGHT,
          ESP_LV_ADAPTER_ROTATE_0);
  display_config.profile.buffer_height = kDrawBufferHeight;
  display_config.profile.use_psram = true;
  display_config.profile.require_double_buffer = true;
  display = esp_lv_adapter_register_display(&display_config);
  if (display == nullptr) {
    ESP_LOGE(kTag, "LVGL could not register the ST77916 display");
    return ESP_FAIL;
  }

  const esp_lv_adapter_touch_config_t touch_lvgl_config =
      ESP_LV_ADAPTER_TOUCH_DEFAULT_CONFIG(display, touch);
  if (esp_lv_adapter_register_touch(&touch_lvgl_config) == nullptr) {
    ESP_LOGE(kTag, "LVGL could not register the CST816S touch device");
    display = nullptr;
    return ESP_FAIL;
  }

  if (lv_display_get_horizontal_resolution(display) != MOTO_DISPLAY_WIDTH ||
      lv_display_get_vertical_resolution(display) != MOTO_DISPLAY_HEIGHT ||
      lv_display_get_color_format(display) != LV_COLOR_FORMAT_RGB565) {
    ESP_LOGE(kTag, "panel reported %ldx%ld format=%d, expected %dx%d RGB565",
             static_cast<long>(lv_display_get_horizontal_resolution(display)),
             static_cast<long>(lv_display_get_vertical_resolution(display)),
             static_cast<int>(lv_display_get_color_format(display)),
             MOTO_DISPLAY_WIDTH, MOTO_DISPLAY_HEIGHT);
    display = nullptr;
    return ESP_ERR_INVALID_SIZE;
  }

  // Keep the panel dark until the boot animation has drawn its first frame.
  lv_obj_t* const startup_screen = lv_display_get_screen_active(display);
  lv_obj_set_style_bg_color(startup_screen, lv_color_black(), 0);
  lv_obj_set_style_bg_opa(startup_screen, LV_OPA_COVER, 0);
  lv_obj_remove_flag(startup_screen, LV_OBJ_FLAG_SCROLLABLE);
  ESP_RETURN_ON_ERROR(esp_lv_adapter_start(), kTag,
                      "LVGL worker could not start");
  ESP_LOGI(kTag, "ST77916 + CST816S ready at %dx%d RGB565", MOTO_DISPLAY_WIDTH,
           MOTO_DISPLAY_HEIGHT);
  return ESP_OK;
}

extern "C" lv_display_t* board_port_get_display(void) { return display; }

extern "C" esp_err_t board_port_reveal_display(void) {
  if (display == nullptr) return ESP_ERR_INVALID_STATE;
  if (!display_revealed) {
    ESP_RETURN_ON_ERROR(esp_lcd_panel_disp_on_off(panel, true), kTag,
                        "panel enable failed");
    ESP_RETURN_ON_ERROR(gpio_set_level(kLcdBacklight, 1), kTag,
                        "backlight on failed");
    display_revealed = true;
  }
  return ESP_OK;
}

extern "C" bool board_port_lock(std::uint32_t timeout_ms) {
  const int32_t timeout =
      timeout_ms == UINT32_MAX ? -1 : static_cast<int32_t>(timeout_ms);
  return esp_lv_adapter_lock(timeout) == ESP_OK;
}

extern "C" void board_port_unlock(void) { esp_lv_adapter_unlock(); }

// This board has no PMIC-backed power latch and the PWR/battery button is not
// routed to a documented SoC GPIO, so both of these stay conservative: the
// caller's deep-sleep fallback is what actually powers the unit down.
extern "C" bool board_port_power_button_pressed(void) { return false; }

extern "C" esp_err_t board_port_power_off(void) { return ESP_ERR_NOT_SUPPORTED; }
