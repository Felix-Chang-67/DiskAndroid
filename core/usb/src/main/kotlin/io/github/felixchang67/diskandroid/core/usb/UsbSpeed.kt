package io.github.felixchang67.diskandroid.core.usb

/** USB 链路速度。数值与 Linux `enum usb_device_speed` 一致（USBDEVFS_GET_SPEED 的返回值）。 */
enum class UsbSpeed(val label: String) {
    UNKNOWN("未知"),
    LOW("Low-Speed 1.5 Mbit/s（USB 1.x）"),
    FULL("Full-Speed 12 Mbit/s（USB 1.x）"),
    HIGH("High-Speed 480 Mbit/s（USB 2.0）"),
    WIRELESS("Wireless USB"),
    SUPER("SuperSpeed 5 Gbit/s（USB 3.x Gen1）"),
    SUPER_PLUS("SuperSpeed+ 10 Gbit/s 及以上（USB 3.x Gen2）"),
    ;

    val isSuperSpeed: Boolean get() = this == SUPER || this == SUPER_PLUS

    companion object {
        fun fromKernel(value: Int): UsbSpeed = when (value) {
            1 -> LOW
            2 -> FULL
            3 -> HIGH
            4 -> WIRELESS
            5 -> SUPER
            6 -> SUPER_PLUS
            else -> UNKNOWN
        }
    }
}
