// 链路自检：确认 gradle → cmake → ndk → .so 能通，且 UTF-16 取字符串可用。
// 真模块进来后这个文件可以删。
#include <jni.h>

// 第二个参数是 jobject 而不是 jclass：NativeProbe 是 Kotlin `object`，里面的
// `external fun` 编译成**实例**方法，ART 传进来的是 NativeProbe.INSTANCE。
// 两者 ABI 上都是指针、这里也用不到它，但写成 jclass 会和 jni_common.h /
// 另外三个模块记录的约定对不上，误导下一个人。
extern "C" JNIEXPORT jint JNICALL
Java_com_biji_notes_nativebridge_NativeProbe_utf16Length(JNIEnv* env, jobject /*thiz*/, jstring s) {
    if (env == nullptr || s == nullptr) return -1;
    return env->GetStringLength(s);
}
