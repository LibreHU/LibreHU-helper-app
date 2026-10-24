// LibreHU Helper: read-only probes of I²C buses and serial ports, for org.librehu.helper.hw.HwNative.
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <linux/i2c-dev.h>
#include <linux/i2c.h>
#include <poll.h>
#include <stdint.h>
#include <string.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#define SCAN_FIRST 0x03
#define SCAN_LAST 0x77
#define MAX_SERIAL (64 * 1024)

static int open_retry(const char *path, int flags) {
    int fd;
    do {
        fd = open(path, flags | O_CLOEXEC);
    } while (fd < 0 && errno == EINTR);
    return fd;
}

static long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

/*
 * Scan of one bus like `i2cdetect -r`: a one-byte read at each address, nothing written. result[0] is -errno when
 * the bus cannot be opened; otherwise result[a] = 0 no answer, 1 answer, 2 address used by a kernel driver (UU).
 */
JNIEXPORT jintArray JNICALL
Java_org_librehu_helper_hw_HwNative_i2cScan(JNIEnv *env, jclass cls, jstring jpath) {
    jintArray out = (*env)->NewIntArray(env, 128);
    if (out == NULL) return NULL;
    jint res[128];
    memset(res, 0, sizeof(res));
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    int fd = open_retry(path, O_RDWR);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (fd < 0) {
        res[0] = -errno;
        (*env)->SetIntArrayRegion(env, out, 0, 128, res);
        return out;
    }
    for (int a = SCAN_FIRST; a <= SCAN_LAST; a++) {
        if (ioctl(fd, I2C_SLAVE, a) < 0) {
            res[a] = errno == EBUSY ? 2 : 0;
            continue;
        }
        uint8_t b;
        res[a] = read(fd, &b, 1) == 1 ? 1 : 0;
    }
    close(fd);
    (*env)->SetIntArrayRegion(env, out, 0, 128, res);
    return out;
}

static int rdwr(int fd, int addr, uint16_t flags, uint8_t *buf, uint16_t len) {
    struct i2c_msg msg = {.addr = (uint16_t) addr, .flags = flags, .len = len, .buf = buf};
    struct i2c_rdwr_ioctl_data data = {.msgs = &msg, .nmsgs = 1};
    return ioctl(fd, I2C_RDWR, &data);
}

/*
 * Register read: write [reg] then, in a second transaction, read [len] bytes (the Apple MFi coprocessor's way, as in
 * LibreHU-xcertplay). I2C_RDWR works even when a kernel driver holds the address. A sleeping coprocessor NACKs: each
 * step is tried up to [tries] times, 2 ms apart. Null on failure.
 */
JNIEXPORT jbyteArray JNICALL
Java_org_librehu_helper_hw_HwNative_i2cRead(JNIEnv *env, jclass cls, jstring jpath, jint addr, jint reg, jint len,
                                             jint tries) {
    if (len <= 0 || len > 4096 || addr < 0 || addr > 0x7f) return NULL;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    int fd = open_retry(path, O_RDWR);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (fd < 0) return NULL;
    uint8_t r = (uint8_t) reg;
    uint8_t buf[4096];
    int ok = 0;
    for (int t = 0; t < tries && !ok; t++) {
        if (rdwr(fd, addr, 0, &r, 1) < 0) {
            usleep(2000);
            continue;
        }
        for (int u = 0; u < tries; u++) {
            if (rdwr(fd, addr, I2C_M_RD, buf, (uint16_t) len) >= 0) {
                ok = 1;
                break;
            }
            usleep(2000);
        }
    }
    close(fd);
    if (!ok) return NULL;
    jbyteArray out = (*env)->NewByteArray(env, len);
    if (out != NULL) (*env)->SetByteArrayRegion(env, out, 0, len, (const jbyte *) buf);
    return out;
}

static speed_t speed_of(int baud) {
    switch (baud) {
        case 1200: return B1200;
        case 2400: return B2400;
        case 4800: return B4800;
        case 9600: return B9600;
        case 19200: return B19200;
        case 38400: return B38400;
        case 57600: return B57600;
        case 115200: return B115200;
        case 230400: return B230400;
        case 460800: return B460800;
        case 500000: return B500000;
        case 921600: return B921600;
        default: return 0;
    }
}

static int baud_of(speed_t s) {
    switch (s) {
        case B1200: return 1200;
        case B2400: return 2400;
        case B4800: return 4800;
        case B9600: return 9600;
        case B19200: return 19200;
        case B38400: return 38400;
        case B57600: return 57600;
        case B115200: return 115200;
        case B230400: return 230400;
        case B460800: return 460800;
        case B500000: return 500000;
        case B921600: return 921600;
        default: return 0;
    }
}

/* Current input speed of a serial port, 0 when unknown, -errno when it cannot be opened. */
JNIEXPORT jint JNICALL
Java_org_librehu_helper_hw_HwNative_serialBaud(JNIEnv *env, jclass cls, jstring jpath) {
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    int fd = open_retry(path, O_RDONLY | O_NOCTTY | O_NONBLOCK);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (fd < 0) return -errno;
    struct termios tio;
    int baud = tcgetattr(fd, &tio) == 0 ? baud_of(cfgetispeed(&tio)) : 0;
    close(fd);
    return baud;
}

/*
 * Reads a serial port for [ms] milliseconds. [baud] 0 keeps the port's settings (the port may be in use by the
 * maker's service: nothing changed); otherwise raw 8N1 at [baud], the previous settings put back after. Nothing is
 * written. Null when the port cannot be opened.
 */
JNIEXPORT jbyteArray JNICALL
Java_org_librehu_helper_hw_HwNative_serialRead(JNIEnv *env, jclass cls, jstring jpath, jint baud, jint ms) {
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    int fd = open_retry(path, O_RDONLY | O_NOCTTY | O_NONBLOCK);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (fd < 0) return NULL;
    struct termios old;
    int changed = 0;
    if (baud > 0 && tcgetattr(fd, &old) == 0) {
        speed_t sp = speed_of(baud);
        if (sp != 0) {
            struct termios tio = old;
            cfmakeraw(&tio);
            tio.c_cflag |= CLOCAL | CREAD;
            tio.c_cflag &= ~(CSTOPB | PARENB | CRTSCTS);
            cfsetispeed(&tio, sp);
            cfsetospeed(&tio, sp);
            changed = tcsetattr(fd, TCSANOW, &tio) == 0;
            tcflush(fd, TCIFLUSH);
        }
    }
    static uint8_t buf[MAX_SERIAL];
    int n = 0;
    long end = now_ms() + ms;
    while (n < MAX_SERIAL) {
        long left = end - now_ms();
        if (left <= 0) break;
        struct pollfd p = {.fd = fd, .events = POLLIN};
        int r = poll(&p, 1, (int) left);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) break;
        ssize_t got = read(fd, buf + n, MAX_SERIAL - n);
        if (got > 0) n += (int) got;
        else if (got < 0 && errno != EAGAIN && errno != EINTR) break;
    }
    if (changed) tcsetattr(fd, TCSANOW, &old);
    close(fd);
    jbyteArray out = (*env)->NewByteArray(env, n);
    if (out != NULL && n > 0) (*env)->SetByteArrayRegion(env, out, 0, n, (const jbyte *) buf);
    return out;
}
