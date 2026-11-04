LOCAL_PATH := $(call my-dir)

# Build the real HEV JNI shared library from source. The old package contained
# standalone executables renamed to .so; those cannot be loaded by JNI.
include $(LOCAL_PATH)/hev-socks5-tunnel/Android.mk

LOCAL_PATH := $(call my-dir)
CORE_DIR := $(LOCAL_PATH)/PingNG
CORE_SOURCES := $(wildcard $(CORE_DIR)/*.c)
CORE_SOURCES := $(filter-out $(CORE_DIR)/win_service.c,$(CORE_SOURCES))

include $(CLEAR_VARS)
LOCAL_MODULE := PingNG
LOCAL_SRC_FILES := $(patsubst $(LOCAL_PATH)/%,%,$(CORE_SOURCES)) native-lib.c utils.c
LOCAL_C_INCLUDES := $(CORE_DIR)
LOCAL_CFLAGS := -std=c99 -O2 -D_XOPEN_SOURCE=500 -DANDROID_APP
LOCAL_LDLIBS := -landroid -llog
include $(BUILD_SHARED_LIBRARY)
