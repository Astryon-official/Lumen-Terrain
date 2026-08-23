#pragma once

#include <jni.h>

namespace LTE
{

/*
 * JNI entry points (declarations). Implementations live in
 * LTENative.cpp. The header exists so the test target and IDEs can
 * see the exported surface without parsing JNI types elsewhere.
 */
void RegisterDeviceJNI(JNIEnv* env);

} // namespace LTE
