LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := gesture_switch
LOCAL_SRC_FILES := main.cpp
LOCAL_STATIC_LIBRARIES := libcxx
LOCAL_LDLIBS := -llog
# Do not re-export libcxx symbols (operator new/delete etc.) and drop unused ones.
LOCAL_LDFLAGS := -Wl,--exclude-libs,ALL -Wl,--gc-sections
include $(BUILD_SHARED_LIBRARY)

# Must stay last: including another Android.mk changes LOCAL_PATH.
include $(LOCAL_PATH)/libcxx/Android.mk

# If you do not want to use libc++, drop the libcxx lines above and
# link to system stdc++ so that you can at least call the new operator:

# include $(CLEAR_VARS)
# LOCAL_MODULE := gesture_switch
# LOCAL_SRC_FILES := main.cpp
# LOCAL_LDLIBS := -llog -lstdc++
# include $(BUILD_SHARED_LIBRARY)
