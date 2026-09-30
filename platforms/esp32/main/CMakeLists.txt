set(MOTO_REPOSITORY_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../..")
set(MOTO_SHARED_UI_SOURCE
    "${MOTO_REPOSITORY_ROOT}/shared/nav_ui/src/moto_nav_ui.cpp")
set(MOTO_SHARED_UI_FONT
    "${MOTO_REPOSITORY_ROOT}/shared/nav_ui/assets/moto_font_nav_16.c")
set(MOTO_SHARED_UI_INCLUDE
    "${MOTO_REPOSITORY_ROOT}/shared/nav_ui/include")
set(MOTO_SHARED_NAV_PRESENTER_SOURCE
    "${MOTO_REPOSITORY_ROOT}/shared/nav_presenter/src/moto_nav_presenter.cpp")
set(MOTO_SHARED_BLE_PROTOCOL_SOURCE
    "${MOTO_REPOSITORY_ROOT}/shared/ble_protocol/src/ble_protocol.cpp")

# One board port per supported board. Everything above the board layer (shared
# UI, presenter, navigation core, BLE transport) is identical, so switching
# boards only swaps this source file and the panel/touch components it needs.
if(CONFIG_MOTO_BOARD_TOUCH_LCD_1_85B)
    # 360x360 ST77916 QSPI panel with a CST816S touch controller. The shared UI
    # is laid out in a 360-unit design space, so on this panel it renders 1:1.
    set(MOTO_BOARD_PORT_SRC "board_port_waveshare_1_85b.cpp")
    set(MOTO_BOARD_REQUIRES
        esp_lcd_st77916
        esp_lcd_touch_cst816s
        esp_lcd_touch
        esp_lvgl_adapter
        esp_driver_spi
    )
    set(MOTO_BOARD_CANVAS_DEFS
        MOTO_UI_CANVAS_WIDTH=360
        MOTO_UI_CANVAS_HEIGHT=360
    )
else()
    set(MOTO_BOARD_PORT_SRC "board_port_waveshare_1_75c.cpp")
    set(MOTO_BOARD_REQUIRES
        esp_lcd_co5300
        esp32_s3_touch_amoled_1_75c
    )
    set(MOTO_BOARD_CANVAS_DEFS "")
endif()

# The shared UI source is compiled directly into this ESP-IDF component. Do not
# copy it into platforms/esp32: Web and firmware must execute the same code.
idf_component_register(
    SRCS
        "app_main.cpp"
        "ble_nav_transport_nimble.cpp"
        "${MOTO_BOARD_PORT_SRC}"
        "motion_heading_sensor.cpp"
        "phone_nav_bridge.cpp"
        "${MOTO_SHARED_BLE_PROTOCOL_SOURCE}"
        "${MOTO_SHARED_NAV_PRESENTER_SOURCE}"
        "${MOTO_SHARED_UI_FONT}"
        "${MOTO_SHARED_UI_SOURCE}"
    INCLUDE_DIRS
        "."
        "${MOTO_REPOSITORY_ROOT}/shared"
        "${MOTO_REPOSITORY_ROOT}/shared/ble_protocol/include"
        "${MOTO_REPOSITORY_ROOT}/shared/nav_presenter/include"
        "${MOTO_SHARED_UI_INCLUDE}"
    REQUIRES
        esp_timer
        esp_driver_gpio
        esp_driver_i2c
        esp_hw_support
        esp_lcd
        freertos
        log
        lvgl
        nvs_flash
        bt
        ${MOTO_BOARD_REQUIRES}
)

target_compile_features(${COMPONENT_LIB} PUBLIC cxx_std_17)
target_compile_definitions(${COMPONENT_LIB} PRIVATE ${MOTO_BOARD_CANVAS_DEFS})
target_compile_options(${COMPONENT_LIB} PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    $<$<COMPILE_LANGUAGE:CXX>:-fno-exceptions>
    $<$<COMPILE_LANGUAGE:CXX>:-fno-rtti>
)
