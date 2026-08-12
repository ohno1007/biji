// 链路自检：确认 gradle → cmake → ndk → .so 能通，且 UTF-16 取字符串可用。
// 真模块进来后这个文件可以删。
#include <jni.h>

extern "C" JNIEXPORT jint JNICALL
Java_com_biji_notes_nativebridge_NativeProbe_utf16Length(JNIEnv* env, jclass, jstring s) {
    if (s == nullptr) return -1;
    return env->GetStringLength(s);
}
