// Board port for the Waveshare ESP32-S3-Touch-LCD-1.85B.
//
// This is NOT the AMOLED-1.75C the rest of the project targets, and Waveshare
// publishes no BSP for it, so the panel, touch and GPIO setup are written here
// against Espressif's own components:
//
//   panel  ST77916 over 4-bit QSPI  (espressif/esp_lcd_st77916)
//   touch  CST816S over I2C         (espressif/esp_lcd_touch_cst816s)
//   LVGL   espressif/esp_lvgl_adapter, the same adapter the 1.75C port uses
//
// The 1.85B shares the 1.85's panel and touch parts but NOT its wiring, which
// is the trap this port exists to avoid: on the 1.85 the touch sits on its own
// I2C bus at GPIO1/3 and both resets hang off a TCA9554 expander. On the 1.85B
// there is no expander at all, the resets are ordinary GPIOs, and every
// on-board I2C chip (touch, IMU, audio, fuel gauge, RTC) shares GPIO10/11.
// Pins below come from the official 1.85B hardware table.

#include "board_port.h"

#include <cstddef>
#include <cstdint>

#include "driver/gpio.h"
#include "driver/i2c_master.h"
#include "driver/spi_master.h"
#include "esp_check.h"
#include "esp_err.h"
#include "esp_lcd_panel_io.h"
#include "esp_lcd_panel_ops.h"
#include "esp_lcd_panel_vendor.h"
#include "esp_lcd_st77916.h"
#include "esp_lcd_touch.h"
#include "esp_lcd_touch_cst816s.h"
#include "esp_log.h"
#include "esp_lv_adapter.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "sdkconfig.h"

namespace {
constexpr char kTag[] = "board_1_85b";

// --- Panel: 1.85 inch round LCD, 360x360, ST77916 on 4-bit QSPI -------------
// The four data lines are not consecutive GPIOs on this board.
constexpr gpio_num_t kLcdSclk = GPIO_NUM_40;
constexpr gpio_num_t kLcdData0 = GPIO_NUM_46;
constexpr gpio_num_t kLcdData1 = GPIO_NUM_45;
constexpr gpio_num_t kLcdData2 = GPIO_NUM_42;
constexpr gpio_num_t kLcdData3 = GPIO_NUM_41;
constexpr gpio_num_t kLcdCs = GPIO_NUM_21;
constexpr gpio_num_t kLcdReset = GPIO_NUM_3;
constexpr gpio_num_t kLcdBacklight = GPIO_NUM_5;
constexpr spi_host_device_t kLcdHost = SPI2_HOST;
constexpr int kLcdPixelClockHz = 40 * 1000 * 1000;

// --- Touch: CST816S, address 0x15, on the shared I2C bus -------------------
constexpr gpio_num_t kTouchReset = GPIO_NUM_1;
constexpr gpio_num_t kTouchInt = GPIO_NUM_4;

// --- Shared I2C bus: touch, IMU, audio, fuel gauge and RTC all live here ----
constexpr gpio_num_t kI2cScl = GPIO_NUM_10;
constexpr gpio_num_t kI2cSda = GPIO_NUM_11;

constexpr int kDrawBufferHeight = 40;
// The complete instrument UI holds several pages of text and lines at once.
// LVGL's built-in pool starts in internal RAM, which is far too small for that,
// so reserve a bounded PSRAM pool the same way the 1.75C port does. Without it
// the allocator thrashes during the first full-screen draw and the LVGL task
// never yields, which the task watchdog reports as a hang.
constexpr std::size_t kLvglExtraPoolBytes = 2U * 1024U * 1024U;
static_assert(kLvglExtraPoolBytes <= LV_MEM_POOL_EXPAND_SIZE,
              "LVGL PSRAM pool must fit the configured pool expansion");
static_assert(LV_USE_STDLIB_MALLOC == LV_STDLIB_BUILTIN,
              "Review the LVGL pool setup after changing allocators");

i2c_master_bus_handle_t i2c_bus = nullptr;
void* lvgl_extra_pool_storage = nullptr;
esp_lcd_panel_handle_t panel = nullptr;
esp_lcd_panel_io_handle_t panel_io = nullptr;
esp_lcd_touch_handle_t touch = nullptr;
lv_display_t* display = nullptr;
bool display_revealed = false;

esp_err_t initialize_i2c_bus() {
  i2c_master_bus_config_t config = {};
  config.clk_source = I2C_CLK_SRC_DEFAULT;
  config.i2c_port = I2C_NUM_0;
  config.sda_io_num = kI2cSda;
  config.scl_io_num = kI2cScl;
  config.glitch_ignore_cnt = 7;
  config.flags.enable_internal_pullup = 1;
  return i2c_new_master_bus(&config, &i2c_bus);
}

// Logs every address that acknowledges. On an undocumented board this turns
// "the panel is black" into a concrete list of which chips answered.
void log_i2c_scan() {
  if (i2c_bus == nullptr) return;
  int found = 0;
  for (std::uint8_t address = 0x08; address <= 0x77; ++address) {
    if (i2c_master_probe(i2c_bus, address, 100) == ESP_OK) {
      ESP_LOGI(kTag, "I2C device at 0x%02X", address);
      ++found;
    }
  }
  if (found == 0) {
    ESP_LOGW(kTag, "no I2C device responded on the shared bus");
  }
}

esp_err_t initialize_backlight() {
  gpio_config_t config = {};
  config.pin_bit_mask = 1ULL << static_cast<int>(kLcdBacklight);
  config.mode = GPIO_MODE_OUTPUT;
  config.pull_up_en = GPIO_PULLUP_DISABLE;
  config.pull_down_en = GPIO_PULLDOWN_DISABLE;
  config.intr_type = GPIO_INTR_DISABLE;
  ESP_RETURN_ON_ERROR(gpio_config(&config), kTag, "backlight GPIO setup failed");
  // Kept dark until the caller has drawn its first intentional frame.
  return gpio_set_level(kLcdBacklight, 0);
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
  // No SPICOMMON_BUSFLAG_QUAD here: the AMOLED-1.75C port leaves the flags at
  // their defaults for the same 4-bit QSPI wiring, and requesting the quad IO
  // capability changes which pins the SPI driver is willing to route.
  ESP_RETURN_ON_ERROR(
      spi_bus_initialize(kLcdHost, &bus_config, SPI_DMA_CH_AUTO), kTag,
      "QSPI bus init failed");

  esp_lcd_panel_io_spi_config_t io_config = {};
  io_config.cs_gpio_num = kLcdCs;
  io_config.dc_gpio_num = -1;
  io_config.spi_mode = 0;
  io_config.pclk_hz = kLcdPixelClockHz;
  // One outstanding transaction, as on the 1.75C port: with a queue the old
  // frame can still be in flight while LVGL starts writing the next one.
  io_config.trans_queue_depth = 1;
  io_config.lcd_cmd_bits = 32;
  io_config.lcd_param_bits = 8;
  io_config.flags.quad_mode = 1;
  // ESP32-S3 GPSPI can DMA an aligned PSRAM colour buffer directly. Without
  // this, esp_lcd allocates and copies through an internal bounce buffer for
  // every flush.
  io_config.flags.psram_dma_direct = true;
  ESP_RETURN_ON_ERROR(
      esp_lcd_new_panel_io_spi(static_cast<esp_lcd_spi_bus_handle_t>(kLcdHost),
                               &io_config, &panel_io),
      kTag, "panel IO create failed");

  st77916_vendor_config_t vendor_config = {};
  vendor_config.flags.use_qspi_interface = 1;

  esp_lcd_panel_dev_config_t panel_config = {};
  panel_config.reset_gpio_num = kLcdReset;
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
  ESP_RETURN_ON_ERROR(esp_lcd_new_panel_io_i2c(i2c_bus, &io_config, &touch_io),
                      kTag, "touch IO create failed");

  esp_lcd_touch_config_t touch_config = {};
  touch_config.x_max = MOTO_DISPLAY_WIDTH;
  touch_config.y_max = MOTO_DISPLAY_HEIGHT;
  touch_config.rst_gpio_num = kTouchReset;
  touch_config.int_gpio_num = kTouchInt;
  // Matches the orientation the panel is mounted in.
  touch_config.flags.swap_xy = 0;
  touch_config.flags.mirror_x = 1;
  touch_config.flags.mirror_y = 1;
  ESP_RETURN_ON_ERROR(
      esp_lcd_touch_new_i2c_cst816s(touch_io, &touch_config, &touch), kTag,
      "CST816S init failed");
  return ESP_OK;
}
}  // namespace

extern "C" i2c_master_bus_handle_t board_port_sensor_i2c_bus(void) {
  return i2c_bus;
}

extern "C" esp_err_t board_port_init(void) {
  ESP_LOGI(kTag, "bringing up ESP32-S3-Touch-LCD-1.85B (%dx%d)",
           MOTO_DISPLAY_WIDTH, MOTO_DISPLAY_HEIGHT);

  ESP_RETURN_ON_ERROR(initialize_i2c_bus(), kTag, "I2C bus init failed");
  log_i2c_scan();
  ESP_RETURN_ON_ERROR(initialize_backlight(), kTag, "backlight init failed");
  ESP_RETURN_ON_ERROR(initialize_panel(), kTag, "panel init failed");
  ESP_RETURN_ON_ERROR(initialize_touch(), kTag, "touch init failed");

  // LVGL allocates its built-in pool from internal RAM, which cannot hold the
  // full instrument UI. Add a bounded PSRAM pool before the adapter initialises
  // so the first full-screen draw has somewhere to work.
  lvgl_extra_pool_storage = heap_caps_malloc(
      kLvglExtraPoolBytes, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
  if (lvgl_extra_pool_storage == nullptr ||
      lv_mem_add_pool(lvgl_extra_pool_storage, kLvglExtraPoolBytes) == nullptr) {
    heap_caps_free(lvgl_extra_pool_storage);
    lvgl_extra_pool_storage = nullptr;
    ESP_LOGE(kTag, "could not reserve the LVGL PSRAM pool");
    return ESP_ERR_NO_MEM;
  }
  ESP_LOGI(kTag, "LVGL PSRAM pool: %u KiB",
           static_cast<unsigned>(kLvglExtraPoolBytes / 1024U));

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

// This board has no PMIC-backed power latch and its PWR/battery button is not
// on a documented SoC GPIO, so both of these stay conservative: the caller's
// deep-sleep fallback is what actually powers the unit down.
extern "C" bool board_port_power_button_pressed(void) { return false; }

extern "C" esp_err_t board_port_power_off(void) { return ESP_ERR_NOT_SUPPORTED; }
