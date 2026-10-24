cmake_minimum_required(VERSION 3.22.1)
project(hwprobe C)

# I²C (scan by reads, register reads through I2C_RDWR) and serial ports (raw reads at a given speed) for
# org.librehu.helper.hw.HwNative, run as root through app_process (HwProbe).
add_library(hwprobe SHARED hwprobe.c)
target_compile_options(hwprobe PRIVATE -Wall -Wextra -Wno-unused-parameter)
