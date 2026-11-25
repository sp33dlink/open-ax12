// JNI bridge between org.openax12.sdk.SdkNative and the prebuilt rmAxSdk1 library.
//
// Rules this file follows (see docs/sdk/SDK_User_Manual.en.md and CLAUDE.md):
//  * Kotlin is the only caller and calls from ONE dedicated thread. Every index and array length
//    is validated in Kotlin (SdkRadio) before it gets here; this file only re-checks what it
//    needs for its own memory safety.
//  * The SDK message callback runs on the SDK output thread. It copies the payload into Java
//    primitives / strings, hands it to SdkNative.onNativeMessage and returns. It never calls
//    back into the SDK.
//  * No C++ exception leaves a JNI function, no JNI function is called with a Java exception
//    pending, and every local reference created in a loop or on the callback thread is released.
//  * The firmware-update and app-update functions of the SDK are not bound, on purpose
//    (design rule 4). Only the read-only device information calls are.
//
// src/test/.../JniContractTest.kt parses this file: keep the JNI_FN(...) definitions, the
// *_FIELDS(X) lists, the DEFINE_BINDING(...) lines and the "constexpr int k..." layout constants
// in their regular one-per-line form.
#include <jni.h>

#include <android/log.h>

#include <dlfcn.h>
#include <link.h>
#include <pthread.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <list>
#include <string>
#include <string_view>
#include <vector>

#include "rmAxSdkApi.h"

#define LOG_TAG "OpenAx12Jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace QtAndroidPrivate {
// Exported by libQt6Core. Caches the JavaVM (QJniEnvironment needs it when the SDK creates its
// QCoreApplication) and then tries to register the Qt Android binding classes. Those classes are
// not in this app, so it fails half-way and returns false; the JavaVM is cached before that
// point, which is all the SDK needs. The result is therefore ignored.
extern bool initJNI(JavaVM* vm, JNIEnv* env);
}  // namespace QtAndroidPrivate

namespace {

// ================================================================================================
// Layout of the primitive buffers shared with Kotlin (NativeLayout in NativeApi.kt).
// ================================================================================================

constexpr int kLiveMaxSources = 64;
constexpr int kLiveMaxChannels = 32;
constexpr int kLiveSourceStride = 4;   // srcIdx, rawVal, rawLogicVal, outputVal
constexpr int kLiveChannelStride = 3;  // chIdx, chOut, mixesNum
constexpr int kLiveIntSourceCount = 0;
constexpr int kLiveIntChannelCount = 1;
constexpr int kLiveIntSourceTotal = 2;
constexpr int kLiveIntChannelTotal = 3;
constexpr int kLiveIntTrimLv = 4;
constexpr int kLiveIntTrimLh = 5;
constexpr int kLiveIntTrimRv = 6;
constexpr int kLiveIntTrimRh = 7;
constexpr int kLiveIntRfLq = 8;
constexpr int kLiveIntRfDbm = 9;
constexpr int kLiveIntRfLostCount = 10;
constexpr int kLiveIntTopKeyState = 11;
constexpr int kLiveIntAdcSetState = 12;
constexpr int kLiveIntSourcesOffset = 16;
constexpr int kLiveIntChannelsOffset = 272;  // kLiveIntSourcesOffset + kLiveMaxSources * kLiveSourceStride
constexpr int kLiveIntSize = 368;            // kLiveIntChannelsOffset + kLiveMaxChannels * kLiveChannelStride
constexpr int kLiveFloatTxVoltage = 0;
constexpr int kLiveFloatRxVoltage = 1;
constexpr int kLiveFloatChannelsOffset = 2;
constexpr int kLiveFloatSize = 34;           // kLiveFloatChannelsOffset + kLiveMaxChannels

constexpr int kCountsSourceCount = 0;
constexpr int kCountsChannelCount = 1;
constexpr int kCountsCurveCount = 2;
constexpr int kCountsChannelsOffset = 4;
constexpr int kCountsChannelStride = 2;      // chIdx, mixesNum
constexpr int kCountsSize = 68;              // kCountsChannelsOffset + kLiveMaxChannels * kCountsChannelStride

// Results of initialize(), mirrored by SdkInitCode in Kotlin.
constexpr int kInitSuccess = 0;
constexpr int kInitAlreadyInitialized = 1;
constexpr int kInitThreadStartFailed = 2;
constexpr int kInitCommunicationInitFailed = 3;
constexpr int kInitCallbackRejected = -1;
constexpr int kInitBadArgument = -2;
constexpr int kInitNativeException = -3;
constexpr int kInitUnknownResult = -4;

// Upper bounds for data copied out of SDK containers. They only protect against a corrupted
// container; real sizes are far below.
constexpr int kMaxListItems = 4096;
constexpr int kMaxPreFlightStates = 256;
constexpr int kMaxCrsfFrameBytes = 512;
constexpr int kMaxElrsOptions = 512;
constexpr int kCurvePoints = 12;
constexpr int kFailsafeChannels = 16;
constexpr int kPreFlightSources = 36;

static_assert(kLiveIntChannelsOffset == kLiveIntSourcesOffset + kLiveMaxSources * kLiveSourceStride);
static_assert(kLiveIntSize == kLiveIntChannelsOffset + kLiveMaxChannels * kLiveChannelStride);
static_assert(kLiveFloatSize == kLiveFloatChannelsOffset + kLiveMaxChannels);
static_assert(kCountsSize == kCountsChannelsOffset + kLiveMaxChannels * kCountsChannelStride);

// ================================================================================================
// Serial port workarounds (taken from the vendor demo, docs/sdk/reference/rmAxSdkJni.cpp)
// ================================================================================================

// QSerialPort creates a lock file "LCK..<port>" below QDir::tempPath() before it opens a port and
// gives up if that fails. The default temp dir is not writable for an Android app, so TMPDIR is
// pointed into the app's own data directory. Must run before rmaxSdk::initialize(), which
// creates the QCoreApplication and opens the port.
void redirectQtTempDir(const std::string& appDataPath) {
    const std::string tmpDir = appDataPath + "/qt_tmp";
    if (mkdir(tmpDir.c_str(), 0700) != 0 && errno != EEXIST) {
        LOGE("tmpdir: mkdir(%s) failed: %s", tmpDir.c_str(), std::strerror(errno));
        return;
    }
    if (setenv("TMPDIR", tmpDir.c_str(), 1) != 0) {
        LOGE("tmpdir: setenv failed: %s", std::strerror(errno));
        return;
    }
    LOGI("tmpdir: TMPDIR -> %s", tmpDir.c_str());
}

int findQtSerialPortBase(struct dl_phdr_info* info, std::size_t /*size*/, void* data) {
    if (info->dlpi_name != nullptr && std::strstr(info->dlpi_name, "libQt6SerialPort") != nullptr) {
        *static_cast<std::uintptr_t*>(data) = static_cast<std::uintptr_t>(info->dlpi_addr);
        return 1;  // stop iterating
    }
    return 0;
}

// Second line of defence of the demo: QSerialPort::open() has two gates that both jump to the
// "Failed to create a lock file" branch. Both are replaced by NOPs at run time:
//   +0x15628  cbz x8, ...      the lock directory is empty
//   +0x15648  tbz w0, #0, ...  QLockFile::tryLock() returned false
// The offsets are only valid for the libQt6SerialPort_arm64-v8a.so shipped in jniLibs
// (sdk/SDK_CHECKSUMS.sha256). All expected instruction words are verified first; on any mismatch
// nothing is patched.
//
// The page is made RWX and then RX again, exactly like the demo. Do not "improve" this into
// RW -> RX: turning a modified private file mapping executable again needs the SELinux
// permission execmod, which apps do not have, while RWX on an already executable mapping only
// needs execmem.
void patchQtSerialPortLockCheck() {
    std::uintptr_t base = 0;
    dl_iterate_phdr(findQtSerialPortBase, &base);
    if (base == 0) {
        // Normally already loaded as a dependency of librmAxSdk1.so; load it like the demo does.
        // The handle is deliberately never closed, the SDK keeps using the library.
        if (dlopen("libQt6SerialPort_arm64-v8a.so", RTLD_NOW | RTLD_GLOBAL) == nullptr) {
            LOGE("patch: dlopen(libQt6SerialPort) failed: %s", dlerror());
            return;
        }
        dl_iterate_phdr(findQtSerialPortBase, &base);
    }
    if (base == 0) {
        LOGE("patch: NOT applied, libQt6SerialPort load address not found");
        return;
    }

    struct PatchSite {
        std::size_t offset;
        std::uint32_t expect;
        const char* desc;
    };
    static constexpr PatchSite kSites[] = {
        {0x15628, 0xb4000b88u, "cbz (empty lock dir)"},
        {0x15648, 0x360005a0u, "tbz (tryLock failed)"},
    };
    constexpr std::uint32_t kNop = 0xd503201fu;

    // Verify everything before touching anything, so the library is never left half patched.
    bool alreadyPatched = true;
    for (const auto& site : kSites) {
        const std::uint32_t actual = *reinterpret_cast<const volatile std::uint32_t*>(base + site.offset);
        if (actual != kNop) alreadyPatched = false;
        if (actual != site.expect && actual != kNop) {
            LOGE("patch: NOT applied, instruction mismatch at +0x%zx: found 0x%08x, expected 0x%08x "
                 "(different libQt6SerialPort build?)",
                 site.offset, actual, site.expect);
            return;
        }
    }
    if (alreadyPatched) {
        LOGI("patch: lock check already patched");
        return;
    }

    const long pageSizeRaw = sysconf(_SC_PAGESIZE);
    const std::uintptr_t pageSize = pageSizeRaw > 0 ? static_cast<std::uintptr_t>(pageSizeRaw) : 4096u;
    for (const auto& site : kSites) {
        auto* insn = reinterpret_cast<volatile std::uint32_t*>(base + site.offset);
        if (*insn == kNop) continue;
        const std::uintptr_t page = reinterpret_cast<std::uintptr_t>(insn) & ~(pageSize - 1);
        if (mprotect(reinterpret_cast<void*>(page), pageSize, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
            LOGE("patch: INCOMPLETE, mprotect at +0x%zx failed: %s", site.offset, std::strerror(errno));
            return;
        }
        *insn = kNop;
        if (mprotect(reinterpret_cast<void*>(page), pageSize, PROT_READ | PROT_EXEC) != 0) {
            LOGW("patch: could not restore page protection at +0x%zx: %s", site.offset, std::strerror(errno));
        }
        auto* begin = reinterpret_cast<char*>(const_cast<std::uint32_t*>(insn));
        __builtin___clear_cache(begin, begin + sizeof(std::uint32_t));
        LOGI("patch: replaced +0x%zx %s with NOP", site.offset, site.desc);
    }
}

// ================================================================================================
// Globals. Written once in JNI_OnLoad, read-only afterwards; the global references live as long
// as the process (the library is never unloaded).
// ================================================================================================

JavaVM* g_vm = nullptr;
jclass g_nativeClass = nullptr;        // org.openax12.sdk.SdkNative
jmethodID g_onNativeMessage = nullptr; // static void onNativeMessage(int, int[], double[], byte[], String[])
jclass g_stringClass = nullptr;        // java.lang.String
pthread_key_t g_attachKey;
bool g_attachKeyValid = false;
std::atomic<int> g_unknownMessageLogBudget{8};

// ================================================================================================
// Strings. SDK strings are bytes of unknown quality (device texts, file names); JNI's
// NewStringUTF needs valid *modified* UTF-8 and aborts on anything else, so the conversion is
// done here: lenient UTF-8 -> UTF-16 (invalid bytes become U+FFFD) and strict UTF-16 -> UTF-8.
// ================================================================================================

void appendCodePoint(std::vector<jchar>& out, std::uint32_t cp) {
    if (cp < 0x10000u) {
        out.push_back(static_cast<jchar>(cp));
    } else {
        cp -= 0x10000u;
        out.push_back(static_cast<jchar>(0xD800u + (cp >> 10)));
        out.push_back(static_cast<jchar>(0xDC00u + (cp & 0x3FFu)));
    }
}

std::vector<jchar> utf8ToUtf16(std::string_view in) {
    constexpr std::uint32_t kReplacement = 0xFFFDu;
    std::vector<jchar> out;
    out.reserve(in.size());
    std::size_t i = 0;
    const std::size_t n = in.size();
    while (i < n) {
        const auto b0 = static_cast<std::uint8_t>(in[i]);
        if (b0 < 0x80u) {
            out.push_back(static_cast<jchar>(b0));
            ++i;
            continue;
        }
        int extra = 0;
        std::uint32_t cp = 0;
        std::uint32_t minimum = 0;
        if ((b0 & 0xE0u) == 0xC0u) {
            extra = 1;
            cp = b0 & 0x1Fu;
            minimum = 0x80u;
        } else if ((b0 & 0xF0u) == 0xE0u) {
            extra = 2;
            cp = b0 & 0x0Fu;
            minimum = 0x800u;
        } else if ((b0 & 0xF8u) == 0xF0u) {
            extra = 3;
            cp = b0 & 0x07u;
            minimum = 0x10000u;
        } else {
            out.push_back(static_cast<jchar>(kReplacement));
            ++i;
            continue;
        }
        if (i + static_cast<std::size_t>(extra) >= n) {
            // Truncated sequence at the end of the string.
            out.push_back(static_cast<jchar>(kReplacement));
            break;
        }
        bool valid = true;
        for (int k = 1; k <= extra; ++k) {
            const auto b = static_cast<std::uint8_t>(in[i + static_cast<std::size_t>(k)]);
            if ((b & 0xC0u) != 0x80u) {
                valid = false;
                break;
            }
            cp = (cp << 6) | (b & 0x3Fu);
        }
        if (!valid || cp < minimum || cp > 0x10FFFFu || (cp >= 0xD800u && cp <= 0xDFFFu)) {
            out.push_back(static_cast<jchar>(kReplacement));
            ++i;  // resynchronise on the next byte
            continue;
        }
        appendCodePoint(out, cp);
        i += static_cast<std::size_t>(extra) + 1;
    }
    return out;
}

// Returns a new local reference, or nullptr with a Java exception pending (out of memory).
jstring toJString(JNIEnv* env, std::string_view text) {
    const std::vector<jchar> utf16 = utf8ToUtf16(text);
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

void appendUtf8(std::string& out, std::uint32_t cp) {
    if (cp < 0x80u) {
        out.push_back(static_cast<char>(cp));
    } else if (cp < 0x800u) {
        out.push_back(static_cast<char>(0xC0u | (cp >> 6)));
        out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
    } else if (cp < 0x10000u) {
        out.push_back(static_cast<char>(0xE0u | (cp >> 12)));
        out.push_back(static_cast<char>(0x80u | ((cp >> 6) & 0x3Fu)));
        out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
    } else {
        out.push_back(static_cast<char>(0xF0u | (cp >> 18)));
        out.push_back(static_cast<char>(0x80u | ((cp >> 12) & 0x3Fu)));
        out.push_back(static_cast<char>(0x80u | ((cp >> 6) & 0x3Fu)));
        out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
    }
}

// Converts a Java string to standard UTF-8. Returns false for a null reference.
bool fromJString(JNIEnv* env, jstring text, std::string& out) {
    out.clear();
    if (text == nullptr) return false;
    const jsize length = env->GetStringLength(text);
    if (length <= 0) return true;
    std::vector<jchar> utf16(static_cast<std::size_t>(length));
    env->GetStringRegion(text, 0, length, utf16.data());
    if (env->ExceptionCheck()) return false;
    out.reserve(static_cast<std::size_t>(length));
    for (std::size_t i = 0; i < utf16.size(); ++i) {
        std::uint32_t cp = utf16[i];
        if (cp >= 0xD800u && cp <= 0xDBFFu && i + 1 < utf16.size() && utf16[i + 1] >= 0xDC00u &&
            utf16[i + 1] <= 0xDFFFu) {
            cp = 0x10000u + ((cp - 0xD800u) << 10) + (utf16[i + 1] - 0xDC00u);
            ++i;
        } else if (cp >= 0xD800u && cp <= 0xDFFFu) {
            cp = 0xFFFDu;  // unpaired surrogate
        }
        appendUtf8(out, cp);
    }
    return true;
}

// ================================================================================================
// Field access helpers used by the struct bindings
// ================================================================================================

void clearPendingException(JNIEnv* env, const char* where) {
    if (env->ExceptionCheck()) {
        LOGE("%s: clearing a pending Java exception", where);
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

bool lookupClass(JNIEnv* env, const char* name, jclass* outClass, jmethodID* outCtor) {
    jclass local = env->FindClass(name);
    if (local == nullptr) {
        LOGE("JNI_OnLoad: class %s not found", name);
        clearPendingException(env, name);
        return false;
    }
    *outClass = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (*outClass == nullptr) {
        LOGE("JNI_OnLoad: no global reference for %s", name);
        clearPendingException(env, name);
        return false;
    }
    if (outCtor != nullptr) {
        *outCtor = env->GetMethodID(*outClass, "<init>", "()V");
        if (*outCtor == nullptr) {
            LOGE("JNI_OnLoad: %s has no no-argument constructor", name);
            clearPendingException(env, name);
            return false;
        }
    }
    return true;
}

bool lookupField(JNIEnv* env, jclass cls, const char* className, const char* name, const char* signature,
                 jfieldID* out) {
    *out = env->GetFieldID(cls, name, signature);
    if (*out == nullptr) {
        LOGE("JNI_OnLoad: field %s.%s (%s) not found", className, name, signature);
        clearPendingException(env, name);
        return false;
    }
    return true;
}

bool setStringField(JNIEnv* env, jobject object, jfieldID field, const std::string& value) {
    jstring text = toJString(env, value);
    if (text == nullptr) return false;
    env->SetObjectField(object, field, text);
    env->DeleteLocalRef(text);
    return true;
}

bool getStringField(JNIEnv* env, jobject object, jfieldID field, std::string& value) {
    auto text = static_cast<jstring>(env->GetObjectField(object, field));
    if (text == nullptr) return false;
    const bool ok = fromJString(env, text, value);
    env->DeleteLocalRef(text);
    return ok;
}

template <typename Element>
bool setIntArrayField(JNIEnv* env, jobject object, jfieldID field, const Element* values, int count) {
    jintArray array = env->NewIntArray(count);
    if (array == nullptr) return false;
    std::vector<jint> buffer(static_cast<std::size_t>(count));
    for (int i = 0; i < count; ++i) buffer[static_cast<std::size_t>(i)] = static_cast<jint>(values[i]);
    env->SetIntArrayRegion(array, 0, count, buffer.data());
    env->SetObjectField(object, field, array);
    env->DeleteLocalRef(array);
    return true;
}

template <typename Element>
bool getIntArrayField(JNIEnv* env, jobject object, jfieldID field, Element* values, int count) {
    auto array = static_cast<jintArray>(env->GetObjectField(object, field));
    if (array == nullptr) return false;
    bool ok = env->GetArrayLength(array) == count;
    if (ok) {
        std::vector<jint> buffer(static_cast<std::size_t>(count));
        env->GetIntArrayRegion(array, 0, count, buffer.data());
        ok = !env->ExceptionCheck();
        if (ok) {
            for (int i = 0; i < count; ++i) values[i] = static_cast<Element>(buffer[static_cast<std::size_t>(i)]);
        }
    }
    env->DeleteLocalRef(array);
    return ok;
}

// ================================================================================================
// Struct bindings. One list per SDK struct names every field exactly once:
//   X(I, f)     integral member        <-> Kotlin Int   (uint8_t / int8_t are widened)
//   X(J, f)     integral member        <-> Kotlin Long  (uint32_t zero-extended, 64-bit verbatim)
//   X(F, f)     float                  <-> Kotlin Float
//   X(D, f)     double                 <-> Kotlin Double
//   X(S, f)     std::string            <-> Kotlin String
//   X(A, f, n)  integral array of n    <-> Kotlin IntArray of exactly n elements
// The Kotlin class (RawStructs.kt) uses the same field names. The generated binding caches the
// class, its constructor and all field ids once, and converts in both directions.
// ================================================================================================

#define SIG_I "I"
#define SIG_J "J"
#define SIG_F "F"
#define SIG_D "D"
#define SIG_S "Ljava/lang/String;"
#define SIG_A "[I"

#define TO_J_I(f) env->SetIntField(o, f_##f, static_cast<jint>(s.f));
#define TO_J_J(f) env->SetLongField(o, f_##f, static_cast<jlong>(s.f));
#define TO_J_F(f) env->SetFloatField(o, f_##f, static_cast<jfloat>(s.f));
#define TO_J_D(f) env->SetDoubleField(o, f_##f, static_cast<jdouble>(s.f));
#define TO_J_S(f) ok = setStringField(env, o, f_##f, s.f);
#define TO_J_A(f, n)                                                                              \
    static_assert(sizeof(s.f) / sizeof(s.f[0]) == (n), "array length of " #f);                   \
    ok = setIntArrayField(env, o, f_##f, s.f, (n));

#define FROM_J_I(f) s.f = static_cast<decltype(s.f)>(env->GetIntField(o, f_##f));
#define FROM_J_J(f) s.f = static_cast<decltype(s.f)>(env->GetLongField(o, f_##f));
#define FROM_J_F(f) s.f = static_cast<decltype(s.f)>(env->GetFloatField(o, f_##f));
#define FROM_J_D(f) s.f = static_cast<decltype(s.f)>(env->GetDoubleField(o, f_##f));
#define FROM_J_S(f) ok = getStringField(env, o, f_##f, s.f);
#define FROM_J_A(f, n)                                                                            \
    static_assert(sizeof(s.f) / sizeof(s.f[0]) == (n), "array length of " #f);                   \
    ok = getIntArrayField(env, o, f_##f, s.f, (n));

#define FIELD_DECL(kind, f, ...) jfieldID f_##f = nullptr;
#define FIELD_INIT(kind, f, ...) ok = lookupField(env, cls, className, #f, SIG_##kind, &f_##f) && ok;
#define FIELD_TO_J(kind, f, ...)                                                                  \
    if (ok) {                                                                                     \
        TO_J_##kind(f __VA_OPT__(, ) __VA_ARGS__)                                                 \
    }
#define FIELD_FROM_J(kind, f, ...)                                                                \
    if (ok) {                                                                                     \
        FROM_J_##kind(f __VA_OPT__(, ) __VA_ARGS__)                                               \
    }

#define DEFINE_BINDING(Name, SdkType, JavaClass, FIELDS)                                          \
    struct Name {                                                                                 \
        static constexpr const char* className = JavaClass;                                      \
        jclass cls = nullptr;                                                                     \
        jmethodID ctor = nullptr;                                                                 \
        FIELDS(FIELD_DECL)                                                                        \
        /* Looks up everything once; logs every missing member. */                               \
        bool init(JNIEnv* env) {                                                                  \
            if (!lookupClass(env, className, &cls, &ctor)) return false;                         \
            bool ok = true;                                                                       \
            FIELDS(FIELD_INIT)                                                                    \
            return ok;                                                                            \
        }                                                                                         \
        /* New local reference, or nullptr (then a Java exception may be pending). */            \
        jobject toJava(JNIEnv* env, const SdkType& s) const {                                     \
            jobject o = env->NewObject(cls, ctor);                                                \
            if (o == nullptr) return nullptr;                                                     \
            bool ok = !env->ExceptionCheck();                                                     \
            FIELDS(FIELD_TO_J)                                                                    \
            if (!ok) {                                                                            \
                env->DeleteLocalRef(o);                                                           \
                return nullptr;                                                                   \
            }                                                                                     \
            return o;                                                                             \
        }                                                                                         \
        /* Fills s from o; false for null, a null member or a wrong array length. */             \
        bool fromJava(JNIEnv* env, jobject o, SdkType& s) const {                                 \
            bool ok = o != nullptr;                                                               \
            FIELDS(FIELD_FROM_J)                                                                  \
            return ok;                                                                            \
        }                                                                                         \
    }

#define RAW_SRC_FIELDS(X)                                                                         \
    X(S, srcName) X(I, srcIdx) X(I, srcType) X(I, isEnable)                                      \
    X(I, rawVal) X(I, rawLogicVal) X(I, outputVal) X(I, weight)

#define RAW_CH_OUT_FIELDS(X)                                                                      \
    X(I, chIdx) X(I, middle) X(I, min) X(I, max) X(I, curveIdx) X(I, mixesNum)                   \
    X(I, weight) X(I, chOut) X(F, logicVal)                                                       \
    X(I, lockChSwIdx) X(I, lockChSwVal) X(I, lockChSwType) X(I, lockChVal)                       \
    X(I, dr1SwIdx) X(I, dr1SwAct) X(I, dr1Weight)                                                 \
    X(I, dr2SwIdx) X(I, dr2SwAct) X(I, dr2Weight)                                                 \
    X(I, dr3SwIdx) X(I, dr3SwAct) X(I, dr3Weight)

#define RAW_MIX_FIELDS(X)                                                                         \
    X(S, srcName) X(I, srcType) X(I, srcIdx) X(I, offset) X(I, curveIdx)                         \
    X(I, swCmpType) X(I, switchIdx) X(I, swCmpVal) X(I, mixesMode)                               \
    X(I, chIdx) X(I, mixIdx) X(I, slowMsUp) X(I, slowMsDown)                                     \
    X(I, weightUp) X(I, weightDown) X(I, mixesCount)

#define RAW_DR_FIELDS(X)                                                                          \
    X(I, chIdx) X(I, idx) X(I, drCount) X(I, swCmpType) X(I, swSrcIdx) X(I, swCmpVal) X(I, drWeight)

#define RAW_CURVE_FIELDS(X)                                                                       \
    X(I, idx) X(I, cType) X(I, ptNum) X(A, xVal, kCurvePoints) X(A, yVal, kCurvePoints)

#define RAW_TIMER_FIELDS(X)                                                                       \
    X(I, mode) X(I, swCmpType) X(I, switchIdx) X(I, switchAct) X(I, startTime) X(I, direction)   \
    X(I, countdown) X(I, beepTimeSec) X(I, rstSwCmpType) X(I, resetSwIdx) X(I, resetSwAct)       \
    X(I, cmpValue) X(I, cmpType) X(I, loopMode) X(I, outputMode) X(I, outputMin) X(I, outputMax) \
    X(I, threshold) X(I, timeIdx) X(I, trDirection) X(I, trStartTime) X(I, timeCount)            \
    X(I, notifyCode)

#define RAW_SYS_CFG_FIELDS(X)                                                                     \
    X(I, fsMode) X(A, fsChVal, kFailsafeChannels) X(I, stickMode)                                \
    X(A, preFlightCheckChannel, kPreFlightSources)                                                \
    X(I, rfModuleMode) X(I, alramVtg) X(I, joytickBeep) X(I, modelMatchId) X(I, crsfBaudRate)

#define RAW_MODEL_FIELDS(X)                                                                       \
    X(S, modelName) X(S, modelImg) X(S, filePath) X(J, modelIdx) X(J, modelMatch)

#define RAW_FW_INFO_FIELDS(X)                                                                     \
    X(J, SysId) X(J, DevId) X(J, FlyTime) X(J, BuildTime) X(J, VER_FW) X(J, VER_HW)              \
    X(S, DevText) X(S, FwVerText) X(S, BulidTimeText) X(S, cacheFwFileVerTxt)

#define RAW_TELEMETRY_FIELDS(X)                                                                   \
    X(J, hashValue) X(I, popupWindow) X(I, triggerType) X(I, intervalSec) X(D, triggerValue)     \
    X(I, valueUnit) X(S, notifyName) X(S, notifyText) X(S, audioFile) X(S, valTxt) X(S, nameTxt) \
    X(D, currValue) X(I, idx) X(I, showUI)

DEFINE_BINDING(SrcBinding, rmaxSdk::RcSrcCfgUI, "org/openax12/sdk/RawSrc", RAW_SRC_FIELDS);
DEFINE_BINDING(ChOutBinding, rmaxSdk::RcChOutCfgUI, "org/openax12/sdk/RawChOut", RAW_CH_OUT_FIELDS);
DEFINE_BINDING(MixBinding, rmaxSdk::RcMixCfgDataUI, "org/openax12/sdk/RawMix", RAW_MIX_FIELDS);
DEFINE_BINDING(DrBinding, rmaxSdk::RcChCfgDrUI, "org/openax12/sdk/RawDr", RAW_DR_FIELDS);
DEFINE_BINDING(CurveBinding, rmaxSdk::RcCurveCfgDataUI, "org/openax12/sdk/RawCurve", RAW_CURVE_FIELDS);
DEFINE_BINDING(TimerBinding, rmaxSdk::RcTimeCfgDataUI, "org/openax12/sdk/RawTimer", RAW_TIMER_FIELDS);
DEFINE_BINDING(SysCfgBinding, rmaxSdk::RcSysCfgDataUI, "org/openax12/sdk/RawSysCfg", RAW_SYS_CFG_FIELDS);
DEFINE_BINDING(ModelBinding, rmaxSdk::RcModelDataUI, "org/openax12/sdk/RawModel", RAW_MODEL_FIELDS);
DEFINE_BINDING(FwInfoBinding, rmaxSdk::FirmwareInfUI, "org/openax12/sdk/RawFwInfo", RAW_FW_INFO_FIELDS);
DEFINE_BINDING(TelemetryBinding, rmaxSdk::TelemetryNotifyUI, "org/openax12/sdk/RawTelemetry", RAW_TELEMETRY_FIELDS);

SrcBinding g_src;
ChOutBinding g_chOut;
MixBinding g_mix;
DrBinding g_dr;
CurveBinding g_curve;
TimerBinding g_timer;
SysCfgBinding g_sysCfg;
ModelBinding g_model;
FwInfoBinding g_fwInfo;
TelemetryBinding g_telemetry;

// Converts an SDK container to a Java array of the bound class. Returns nullptr on failure (a
// Java exception may then be pending).
template <typename Binding, typename Container>
jobjectArray toJavaArray(JNIEnv* env, const Binding& binding, const Container& items) {
    const std::size_t count = std::min<std::size_t>(items.size(), static_cast<std::size_t>(kMaxListItems));
    jobjectArray array = env->NewObjectArray(static_cast<jsize>(count), binding.cls, nullptr);
    if (array == nullptr) return nullptr;
    std::size_t index = 0;
    for (const auto& item : items) {
        if (index >= count) break;
        jobject element = binding.toJava(env, item);
        if (element == nullptr) {
            env->DeleteLocalRef(array);
            return nullptr;
        }
        env->SetObjectArrayElement(array, static_cast<jsize>(index), element);
        env->DeleteLocalRef(element);
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(array);
            return nullptr;
        }
        ++index;
    }
    return array;
}

// ================================================================================================
// SDK message callback (SDK output thread)
// ================================================================================================

void detachCurrentThread(void* attached) {
    if (attached != nullptr && g_vm != nullptr) {
        g_vm->DetachCurrentThread();
    }
}

// JNIEnv of the calling thread. A native thread is attached once and stays attached until it
// exits (the pthread key destructor detaches it), instead of attach / detach per message.
JNIEnv* currentEnv() {
    if (g_vm == nullptr) return nullptr;
    JNIEnv* env = nullptr;
    const jint state = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (state == JNI_OK) return env;
    if (state != JNI_EDETACHED || !g_attachKeyValid) return nullptr;
    JavaVMAttachArgs args;
    args.version = JNI_VERSION_1_6;
    args.name = "rmax-sdk-output";
    args.group = nullptr;
    if (g_vm->AttachCurrentThread(&env, &args) != JNI_OK || env == nullptr) {
        LOGE("callback: AttachCurrentThread failed");
        return nullptr;
    }
    // Any non-null value arms the destructor for this thread.
    pthread_setspecific(g_attachKey, env);
    return env;
}

jintArray newIntArray(JNIEnv* env, const jint* values, jsize count) {
    jintArray array = env->NewIntArray(count);
    if (array == nullptr) return nullptr;
    if (count > 0) env->SetIntArrayRegion(array, 0, count, values);
    return array;
}

// Hands one message to Kotlin. All references are locals of the current local frame.
void deliver(JNIEnv* env, std::uint32_t msgId, jintArray ints, jdoubleArray doubles, jbyteArray bytes,
             jobjectArray strings) {
    env->CallStaticVoidMethod(g_nativeClass, g_onNativeMessage, static_cast<jint>(msgId), ints, doubles, bytes,
                              strings);
}

void deliverSignal(JNIEnv* env, std::uint32_t msgId) {
    deliver(env, msgId, nullptr, nullptr, nullptr, nullptr);
}

void deliverInts(JNIEnv* env, std::uint32_t msgId, const jint* values, jsize count) {
    jintArray ints = newIntArray(env, values, count);
    if (ints == nullptr) return;
    deliver(env, msgId, ints, nullptr, nullptr, nullptr);
}

// Builds a String[] from UTF-8 texts; nullptr on failure.
jobjectArray newStringArray(JNIEnv* env, const std::vector<std::string_view>& texts) {
    jobjectArray array = env->NewObjectArray(static_cast<jsize>(texts.size()), g_stringClass, nullptr);
    if (array == nullptr) return nullptr;
    for (std::size_t i = 0; i < texts.size(); ++i) {
        jstring text = toJString(env, texts[i]);
        if (text == nullptr) return nullptr;
        env->SetObjectArrayElement(array, static_cast<jsize>(i), text);
        env->DeleteLocalRef(text);
        if (env->ExceptionCheck()) return nullptr;
    }
    return array;
}

// Converts one SDK message. The payload type is fixed by the message id (manual 8.2); casting to
// any other type is undefined behaviour, so every id of RcMessageId has its own case and ids
// whose payload is not needed never touch it.
void dispatchMessage(JNIEnv* env, const rmaxSdk::SdkMessage& message) {
    const std::uint32_t id = message.msgId;
    const void* payload = message.payload.get();
    switch (id) {
    // ---- no payload -------------------------------------------------------------------------
    case rmaxSdk::RC_MSGID_LVTG_NOTIFY:
    case rmaxSdk::RC_MSGID_MIXOUT_UPDATE:
    case rmaxSdk::RC_MSGID_CFGTODEV_END:
    case rmaxSdk::RC_MSGID_GETDEVCFG_END:
    case rmaxSdk::RC_MSGID_RCTELEMETER_NOTIFY:
    case rmaxSdk::RC_MSGID_FWVERSION_NOT_MATCH:
    case rmaxSdk::RC_MSGID_FWINF_UPDATED:
    case rmaxSdk::RC_MSGID_TELEMETRY_UPDATED:
    case rmaxSdk::RC_MSGID_TELEMETRY_LOST:
    case rmaxSdk::RC_MSGID_TELEMETRY_RECOVER:
        deliverSignal(env, id);
        break;

    // ---- reserved id with an undefined payload: never read it, nothing to report ---------------
    case rmaxSdk::RC_MSGID_SRCINPUT_UPDATE:
        break;

    // ---- messages of the firmware-update / app-update features, which this app never starts.
    //      Their payloads (std::int32_t, AppUpdateInfoUI) are deliberately not read. -------------
    case rmaxSdk::RC_MSGID_APPINFO_UPDATED:
    case rmaxSdk::RC_MSGID_APPDOWNLOAD_PROGRESS:
    case rmaxSdk::RC_MSGID_APPDOWNLOAD_END:
    case rmaxSdk::RC_MSGID_FWUPDATE_PROGRESS:
    case rmaxSdk::RC_MSGID_FWFILE_DOWNLOAD_END:
        break;

    // ---- std::vector<std::int32_t> ----------------------------------------------------------------
    case rmaxSdk::RC_MSGID_PFC_NOTIFY: {
        if (payload == nullptr) break;
        const auto& states = *static_cast<const std::vector<std::int32_t>*>(payload);
        const std::size_t count = std::min<std::size_t>(states.size(), static_cast<std::size_t>(kMaxPreFlightStates));
        std::vector<jint> values(count);
        for (std::size_t i = 0; i < count; ++i) values[i] = static_cast<jint>(states[i]);
        deliverInts(env, id, values.data(), static_cast<jsize>(count));
        break;
    }

    // ---- RcTimeReportUI -----------------------------------------------------------------------------
    case rmaxSdk::RC_MSGID_TIM_REPORT:
    case rmaxSdk::RC_MSGID_CNT_REPORT: {
        if (payload == nullptr) break;
        const rmaxSdk::RcTimeReportUI report = *static_cast<const rmaxSdk::RcTimeReportUI*>(payload);
        const jint values[4] = {static_cast<jint>(report.timeIdx), static_cast<jint>(report.direction),
                                static_cast<jint>(report.startTime), static_cast<jint>(report.timeCount)};
        deliverInts(env, id, values, 4);
        break;
    }

    // ---- RcTimeNotifyUI -----------------------------------------------------------------------------
    case rmaxSdk::RC_MSGID_TIM_NOTIFY: {
        if (payload == nullptr) break;
        const rmaxSdk::RcTimeNotifyUI notify = *static_cast<const rmaxSdk::RcTimeNotifyUI*>(payload);
        const jint values[4] = {static_cast<jint>(notify.timeIdx), static_cast<jint>(notify.isRunning),
                                static_cast<jint>(notify.notifyCode), static_cast<jint>(notify.timeCount)};
        deliverInts(env, id, values, 4);
        break;
    }

    // ---- std::uint8_t ---------------------------------------------------------------------------------
    case rmaxSdk::RC_MSGID_TRIM_NOTIFY: {
        if (payload == nullptr) {
            deliverSignal(env, id);
            break;
        }
        const jint values[1] = {static_cast<jint>(*static_cast<const std::uint8_t*>(payload))};
        deliverInts(env, id, values, 1);
        break;
    }

    // ---- ElrsSetEndAckUI ------------------------------------------------------------------------------
    case rmaxSdk::RC_MSGID_ELRS_SET_ACK: {
        if (payload == nullptr) break;
        const auto& ack = *static_cast<const rmaxSdk::ElrsSetEndAckUI*>(payload);
        const jint values[3] = {static_cast<jint>(ack.type), static_cast<jint>(ack.status),
                                static_cast<jint>(ack.lastSetIdx)};
        jintArray ints = newIntArray(env, values, 3);
        if (ints == nullptr) break;
        jobjectArray strings = newStringArray(env, {std::string_view(ack.options)});
        if (strings == nullptr) break;
        deliver(env, id, ints, nullptr, nullptr, strings);
        break;
    }

    // ---- ElrsStatusInfoUI -----------------------------------------------------------------------------
    case rmaxSdk::RC_MSGID_ELRS_STATUS: {
        if (payload == nullptr) break;
        const rmaxSdk::ElrsStatusInfoUI status = *static_cast<const rmaxSdk::ElrsStatusInfoUI*>(payload);
        const jint values[3] = {static_cast<jint>(status.badPkt), static_cast<jint>(status.goodPkt),
                                static_cast<jint>(status.modelMismatch)};
        deliverInts(env, id, values, 3);
        break;
    }

    // ---- TelemetryDataUI (points into SDK-owned storage: copy first) ----------------------------------
    case rmaxSdk::RC_MSGID_ELRS_TELEMETRY: {
        if (payload == nullptr) break;
        const rmaxSdk::TelemetryDataUI data = *static_cast<const rmaxSdk::TelemetryDataUI*>(payload);
        const jdouble values[7] = {data.lat, data.lng, data.pitch, data.roll, data.yaw, data.voltage, data.current};
        jdoubleArray doubles = env->NewDoubleArray(7);
        if (doubles == nullptr) break;
        env->SetDoubleArrayRegion(doubles, 0, 7, values);
        deliver(env, id, nullptr, doubles, nullptr, nullptr);
        break;
    }

    // ---- std::string ----------------------------------------------------------------------------------
    case rmaxSdk::RC_MSGID_ELRS_DEVINF_UPDATE: {
        if (payload == nullptr) break;
        const auto& name = *static_cast<const std::string*>(payload);
        jobjectArray strings = newStringArray(env, {std::string_view(name)});
        if (strings == nullptr) break;
        deliver(env, id, nullptr, nullptr, nullptr, strings);
        break;
    }

    // ---- ElrsSetItemUI: ints {idx, val, type, parentIdx, itemEnabled, itemVisible},
    //      strings {name, deviceName, units, options...} ------------------------------------------------
    case rmaxSdk::RC_MSGID_ELRS_MENUITEM_UPDATE: {
        if (payload == nullptr) break;
        const auto& item = *static_cast<const rmaxSdk::ElrsSetItemUI*>(payload);
        const jint values[6] = {static_cast<jint>(item.idx),       static_cast<jint>(item.val),
                                static_cast<jint>(item.type),      static_cast<jint>(item.parentIdx),
                                item.itemEnabled ? 1 : 0,          item.itemVisible ? 1 : 0};
        jintArray ints = newIntArray(env, values, 6);
        if (ints == nullptr) break;
        const std::size_t optionCount =
            std::min<std::size_t>(item.options.size(), static_cast<std::size_t>(kMaxElrsOptions));
        std::vector<std::string_view> texts;
        texts.reserve(3 + optionCount);
        texts.emplace_back(item.name);
        texts.emplace_back(item.deviceName);
        texts.emplace_back(item.units);
        for (std::size_t i = 0; i < optionCount; ++i) texts.emplace_back(item.options[i]);
        jobjectArray strings = newStringArray(env, texts);
        if (strings == nullptr) break;
        deliver(env, id, ints, nullptr, nullptr, strings);
        break;
    }

    // ---- std::vector<std::uint8_t> --------------------------------------------------------------------
    case rmaxSdk::RC_MSGID_CRSF_DATA_RECEIVED: {
        if (payload == nullptr) break;
        const auto& frame = *static_cast<const std::vector<std::uint8_t>*>(payload);
        const std::size_t count = std::min<std::size_t>(frame.size(), static_cast<std::size_t>(kMaxCrsfFrameBytes));
        if (count == 0) break;
        jbyteArray bytes = env->NewByteArray(static_cast<jsize>(count));
        if (bytes == nullptr) break;
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(count), reinterpret_cast<const jbyte*>(frame.data()));
        deliver(env, id, nullptr, nullptr, bytes, nullptr);
        break;
    }

    default:
        // An id this SDK version does not document: its payload type is unknown, never read it.
        if (g_unknownMessageLogBudget.fetch_sub(1, std::memory_order_relaxed) > 0) {
            LOGW("callback: ignoring unknown message id 0x%08x", id);
        }
        break;
    }
}

// Registered with rmaxSdk::setMessageCallback. Runs on the SDK output thread.
void onSdkMessage(const rmaxSdk::SdkMessage& message, void* /*userData*/) {
    JNIEnv* env = currentEnv();
    if (env == nullptr) return;
    // The output thread never returns to Java, so its local references would pile up: every
    // message gets its own local frame.
    if (env->PushLocalFrame(16) != JNI_OK) {
        clearPendingException(env, "callback: PushLocalFrame");
        return;
    }
    try {
        dispatchMessage(env, message);
    } catch (const std::exception& e) {
        LOGE("callback: C++ exception for message 0x%08x: %s", message.msgId, e.what());
    } catch (...) {
        LOGE("callback: unknown C++ exception for message 0x%08x", message.msgId);
    }
    if (env->ExceptionCheck()) {
        // Either an allocation failed or the Kotlin handler threw. Never let it propagate into
        // the SDK thread.
        LOGE("callback: Java exception while handling message 0x%08x", message.msgId);
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    env->PopLocalFrame(nullptr);
}

// Copies a Java int[] / float[] of at least `size` elements from native buffers.
bool storeInts(JNIEnv* env, jintArray target, const jint* values, int size) {
    if (target == nullptr || env->GetArrayLength(target) < size) return false;
    env->SetIntArrayRegion(target, 0, size, values);
    return !env->ExceptionCheck();
}

}  // namespace

// ================================================================================================
// JNI entry points. JNI_FN(return type, Kotlin function name) — instance methods of the Kotlin
// object org.openax12.sdk.SdkNative, hence the jobject receiver.
// ================================================================================================

#define JNI_FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_org_openax12_sdk_SdkNative_##name

#define JNI_TRY try {
#define JNI_CATCH(tag, fallback)                                                                 \
    }                                                                                             \
    catch (const std::exception& e) {                                                             \
        LOGE("%s: C++ exception: %s", tag, e.what());                                            \
        return fallback;                                                                          \
    }                                                                                             \
    catch (...) {                                                                                 \
        LOGE("%s: unknown C++ exception", tag);                                                  \
        return fallback;                                                                          \
    }
#define JNI_CATCH_VOID(tag)                                                                      \
    }                                                                                             \
    catch (const std::exception& e) {                                                             \
        LOGE("%s: C++ exception: %s", tag, e.what());                                            \
    }                                                                                             \
    catch (...) {                                                                                 \
        LOGE("%s: unknown C++ exception", tag);                                                  \
    }

// ---- lifecycle -------------------------------------------------------------------------------

JNI_FN(jstring, version)(JNIEnv* env, jobject) {
    JNI_TRY
    // version() returns a view that is not guaranteed to be NUL-terminated.
    const std::string_view view = rmaxSdk::version();
    return toJString(env, view);
    JNI_CATCH("version", nullptr)
}

JNI_FN(jint, initialize)(JNIEnv* env, jobject, jstring appDataPath) {
    JNI_TRY
    std::string path;
    if (!fromJString(env, appDataPath, path) || path.empty()) return kInitBadArgument;
    // Before initialize(): the SDK creates its QCoreApplication and opens the serial port there.
    redirectQtTempDir(path);
    LOGI("initialize: calling rmaxSdk::initialize");
    const rmaxSdk::InitResult result = rmaxSdk::initialize(path);
    int code = kInitUnknownResult;
    switch (result) {
    case rmaxSdk::InitResult::Success:
        code = kInitSuccess;
        break;
    case rmaxSdk::InitResult::AlreadyInitialized:
        code = kInitAlreadyInitialized;
        break;
    case rmaxSdk::InitResult::ThreadStartFailed:
        code = kInitThreadStartFailed;
        break;
    case rmaxSdk::InitResult::CommunicationInitFailed:
        code = kInitCommunicationInitFailed;
        break;
    }
    LOGI("initialize: rmaxSdk::initialize returned %d", static_cast<int>(result));
    if (code != kInitSuccess && code != kInitAlreadyInitialized) return code;
    if (!rmaxSdk::setMessageCallback(&onSdkMessage, nullptr)) {
        LOGE("initialize: setMessageCallback was rejected");
        return kInitCallbackRejected;
    }
    return code;
    JNI_CATCH("initialize", kInitNativeException)
}

// Shutdown order of manual 7.3: Kotlin stops issuing calls, clears the callback (this function),
// drops its queued messages, and only then calls shutdown(). This file keeps no SdkMessage,
// payload or SDK container alive between calls.
JNI_FN(void, clearCallback)(JNIEnv*, jobject) {
    JNI_TRY
    rmaxSdk::setMessageCallback(nullptr, nullptr);
    JNI_CATCH_VOID("clearCallback")
}

JNI_FN(void, shutdown)(JNIEnv*, jobject) {
    JNI_TRY
    rmaxSdk::setMessageCallback(nullptr, nullptr);
    rmaxSdk::shutdown();
    LOGI("shutdown: done");
    JNI_CATCH_VOID("shutdown")
}

// ---- device configuration tasks ----------------------------------------------------------------

JNI_FN(void, readConfigFromDevice)(JNIEnv*, jobject) {
    JNI_TRY
    rmaxSdk::taskReadConfigFromDevice();
    JNI_CATCH_VOID("readConfigFromDevice")
}

JNI_FN(void, saveConfigToDevice)(JNIEnv*, jobject) {
    JNI_TRY
    rmaxSdk::taskSaveConfigToDevice();
    JNI_CATCH_VOID("saveConfigToDevice")
}

JNI_FN(void, resetDefault)(JNIEnv*, jobject) {
    JNI_TRY
    rmaxSdk::rcResetDefault();
    JNI_CATCH_VOID("resetDefault")
}

// ---- live data: sources, channels, trims and status in one call ------------------------------------

JNI_FN(jboolean, readLive)(JNIEnv* env, jobject, jintArray ints, jfloatArray floats) {
    JNI_TRY
    if (ints == nullptr || floats == nullptr) return JNI_FALSE;
    if (env->GetArrayLength(ints) < kLiveIntSize || env->GetArrayLength(floats) < kLiveFloatSize) return JNI_FALSE;

    const std::vector<rmaxSdk::RcSrcCfgUI> sources = rmaxSdk::rcGetSrcCfgList();
    const std::vector<rmaxSdk::RcChOutCfgUI> channels = rmaxSdk::rcGetChOutList();
    const rmaxSdk::RcTrimmingTopKeyUI trims = rmaxSdk::rcGetTrimming();
    const rmaxSdk::RadioStatusUI status = rmaxSdk::rcGetRadioStatus();

    jint intBuffer[kLiveIntSize] = {};
    jfloat floatBuffer[kLiveFloatSize] = {};

    const int sourceCount = static_cast<int>(std::min<std::size_t>(sources.size(), kLiveMaxSources));
    const int channelCount = static_cast<int>(std::min<std::size_t>(channels.size(), kLiveMaxChannels));
    intBuffer[kLiveIntSourceCount] = sourceCount;
    intBuffer[kLiveIntChannelCount] = channelCount;
    intBuffer[kLiveIntSourceTotal] = static_cast<jint>(std::min<std::size_t>(sources.size(), 0x7fffffffu));
    intBuffer[kLiveIntChannelTotal] = static_cast<jint>(std::min<std::size_t>(channels.size(), 0x7fffffffu));
    intBuffer[kLiveIntTrimLv] = trims.trimLV;
    intBuffer[kLiveIntTrimLh] = trims.trimLH;
    intBuffer[kLiveIntTrimRv] = trims.trimRV;
    intBuffer[kLiveIntTrimRh] = trims.trimRH;
    intBuffer[kLiveIntRfLq] = status.rfLQ;
    intBuffer[kLiveIntRfDbm] = status.rfLDBM;
    intBuffer[kLiveIntRfLostCount] = status.rfLLostCnt;
    intBuffer[kLiveIntTopKeyState] = status.topKeyState;
    intBuffer[kLiveIntAdcSetState] = status.adcSetState;
    floatBuffer[kLiveFloatTxVoltage] = status.rcVtg;
    floatBuffer[kLiveFloatRxVoltage] = status.rxVtg;

    for (int i = 0; i < sourceCount; ++i) {
        const rmaxSdk::RcSrcCfgUI& source = sources[static_cast<std::size_t>(i)];
        jint* slot = intBuffer + kLiveIntSourcesOffset + i * kLiveSourceStride;
        slot[0] = source.srcIdx;
        slot[1] = source.rawVal;
        slot[2] = source.rawLogicVal;
        slot[3] = source.outputVal;
    }
    for (int i = 0; i < channelCount; ++i) {
        const rmaxSdk::RcChOutCfgUI& channel = channels[static_cast<std::size_t>(i)];
        jint* slot = intBuffer + kLiveIntChannelsOffset + i * kLiveChannelStride;
        slot[0] = channel.chIdx;
        slot[1] = channel.chOut;
        slot[2] = channel.mixesNum;
        floatBuffer[kLiveFloatChannelsOffset + i] = channel.logicVal;
    }

    env->SetIntArrayRegion(ints, 0, kLiveIntSize, intBuffer);
    env->SetFloatArrayRegion(floats, 0, kLiveFloatSize, floatBuffer);
    return env->ExceptionCheck() ? JNI_FALSE : JNI_TRUE;
    JNI_CATCH("readLive", JNI_FALSE)
}

// Sizes of the lists every index is validated against.
JNI_FN(jboolean, readCounts)(JNIEnv* env, jobject, jintArray out) {
    JNI_TRY
    if (out == nullptr || env->GetArrayLength(out) < kCountsSize) return JNI_FALSE;
    const std::vector<rmaxSdk::RcSrcCfgUI> sources = rmaxSdk::rcGetSrcCfgList();
    const std::vector<rmaxSdk::RcChOutCfgUI> channels = rmaxSdk::rcGetChOutList();
    const std::vector<rmaxSdk::RcCurveCfgDataUI> curves = rmaxSdk::rcGetCurveList(-1);

    jint buffer[kCountsSize] = {};
    const int channelCount = static_cast<int>(std::min<std::size_t>(channels.size(), kLiveMaxChannels));
    buffer[kCountsSourceCount] = static_cast<jint>(std::min<std::size_t>(sources.size(), kLiveMaxSources));
    buffer[kCountsChannelCount] = channelCount;
    buffer[kCountsCurveCount] = static_cast<jint>(std::min<std::size_t>(curves.size(), 255u));
    for (int i = 0; i < channelCount; ++i) {
        const rmaxSdk::RcChOutCfgUI& channel = channels[static_cast<std::size_t>(i)];
        buffer[kCountsChannelsOffset + i * kCountsChannelStride] = channel.chIdx;
        buffer[kCountsChannelsOffset + i * kCountsChannelStride + 1] = channel.mixesNum;
    }
    return storeInts(env, out, buffer, kCountsSize) ? JNI_TRUE : JNI_FALSE;
    JNI_CATCH("readCounts", JNI_FALSE)
}

// ---- inputs and outputs ----------------------------------------------------------------------------

JNI_FN(jobjectArray, getSources)(JNIEnv* env, jobject) {
    JNI_TRY
    const std::vector<rmaxSdk::RcSrcCfgUI> sources = rmaxSdk::rcGetSrcCfgList();
    return toJavaArray(env, g_src, sources);
    JNI_CATCH("getSources", nullptr)
}

JNI_FN(jobjectArray, getChannels)(JNIEnv* env, jobject) {
    JNI_TRY
    const std::vector<rmaxSdk::RcChOutCfgUI> channels = rmaxSdk::rcGetChOutList();
    return toJavaArray(env, g_chOut, channels);
    JNI_CATCH("getChannels", nullptr)
}

JNI_FN(jboolean, setChannel)(JNIEnv* env, jobject, jobject raw) {
    JNI_TRY
    rmaxSdk::RcChOutCfgUI channel{};
    if (!g_chOut.fromJava(env, raw, channel)) return JNI_FALSE;
    rmaxSdk::rcSetChOutCfgData(channel);
    return JNI_TRUE;
    JNI_CATCH("setChannel", JNI_FALSE)
}

// ---- mixes -------------------------------------------------------------------------------------------

JNI_FN(jobject, getMix)(JNIEnv* env, jobject, jint chIdx, jint mixIdx) {
    JNI_TRY
    const rmaxSdk::RcMixCfgDataUI mix = rmaxSdk::rcGetMixCfgData(chIdx, mixIdx);
    return g_mix.toJava(env, mix);
    JNI_CATCH("getMix", nullptr)
}

JNI_FN(jboolean, setMix)(JNIEnv* env, jobject, jobject raw) {
    JNI_TRY
    rmaxSdk::RcMixCfgDataUI mix{};
    if (!g_mix.fromJava(env, raw, mix)) return JNI_FALSE;
    rmaxSdk::rcSetMixCfgData(mix);
    return JNI_TRUE;
    JNI_CATCH("setMix", JNI_FALSE)
}

JNI_FN(jobject, addMix)(JNIEnv* env, jobject, jint chIdx, jint srcIdx) {
    JNI_TRY
    const rmaxSdk::RcMixCfgDataUI mix = rmaxSdk::rcAddMixCfgData(chIdx, srcIdx);
    return g_mix.toJava(env, mix);
    JNI_CATCH("addMix", nullptr)
}

JNI_FN(jint, delMix)(JNIEnv*, jobject, jint chIdx, jint srcIdx) {
    JNI_TRY
    return rmaxSdk::rcDelMixCfgData(chIdx, srcIdx);
    JNI_CATCH("delMix", -1)
}

// ---- dual rates --------------------------------------------------------------------------------------

JNI_FN(jobject, getDr)(JNIEnv* env, jobject, jint chIdx, jint idx) {
    JNI_TRY
    const rmaxSdk::RcChCfgDrUI dr = rmaxSdk::rcGetDrData(chIdx, idx);
    return g_dr.toJava(env, dr);
    JNI_CATCH("getDr", nullptr)
}

JNI_FN(jint, setDr)(JNIEnv* env, jobject, jobject raw) {
    JNI_TRY
    rmaxSdk::RcChCfgDrUI dr;
    if (!g_dr.fromJava(env, raw, dr)) return -1;
    return rmaxSdk::rcSetDrData(dr);
    JNI_CATCH("setDr", -1)
}

JNI_FN(jint, delDr)(JNIEnv* env, jobject, jobject raw) {
    JNI_TRY
    rmaxSdk::RcChCfgDrUI dr;
    if (!g_dr.fromJava(env, raw, dr)) return -1;
    return rmaxSdk::rcDelDrData(dr);
    JNI_CATCH("delDr", -1)
}

// ---- curves ------------------------------------------------------------------------------------------

JNI_FN(jobjectArray, getCurves)(JNIEnv* env, jobject, jint idx) {
    JNI_TRY
    const std::vector<rmaxSdk::RcCurveCfgDataUI> curves = rmaxSdk::rcGetCurveList(idx);
    return toJavaArray(env, g_curve, curves);
    JNI_CATCH("getCurves", nullptr)
}

JNI_FN(jint, setCurve)(JNIEnv* env, jobject, jobject raw) {
    JNI_TRY
    rmaxSdk::RcCurveCfgDataUI curve{};
    if (!g_curve.fromJava(env, raw, curve)) return -1;
    return rmaxSdk::rcSetCurveCfg(curve);
    JNI_CATCH("setCurve", -1)
}

JNI_FN(jdouble, curveApply)(JNIEnv*, jobject, jint curveIdx, jdouble rawVal) {
    JNI_TRY
    return rmaxSdk::rcCurveApply(curveIdx, rawVal);
    JNI_CATCH("curveApply", rawVal)
}

// ---- timers and counters -----------------------------------------------------------------------------

JNI_FN(jobject, getTimer)(JNIEnv* env, jobject, jint timerIdx, jint isCounter) {
    JNI_TRY
    const rmaxSdk::RcTimeCfgDataUI timer = rmaxSdk::rcGetTimerCfgData(timerIdx, isCounter);
    return g_timer.toJava(env, timer);
    JNI_CATCH("getTimer", nullptr)
}

JNI_FN(jboolean, setTimer)(JNIEnv* env, jobject, jobject raw, jint isCounter) {
    JNI_TRY
    rmaxSdk::RcTimeCfgDataUI timer{};
    if (!g_timer.fromJava(env, raw, timer)) return JNI_FALSE;
    rmaxSdk::rcSetTimerCfgData(timer, isCounter);
    return JNI_TRUE;
    JNI_CATCH("setTimer", JNI_FALSE)
}

JNI_FN(void, resetTimer)(JNIEnv*, jobject, jint timerIdx) {
    JNI_TRY
    rmaxSdk::rcResetTimer(timerIdx);
    JNI_CATCH_VOID("resetTimer")
}

// ---- system configuration and device control ---------------------------------------------------------

JNI_FN(jobject, getSysConfig)(JNIEnv* env, jobject) {
    JNI_TRY
    const rmaxSdk::RcSysCfgDataUI config = rmaxSdk::rcGetRcSysConfig();
    return g_sysCfg.toJava(env, config);
    JNI_CATCH("getSysConfig", nullptr)
}

JNI_FN(jboolean, setSysConfig)(JNIEnv* env, jobject, jobject raw) {
    JNI_TRY
    // The SDK's setter reads one byte beyond the 59 bytes of the header's struct (it copies 43 bytes
    // starting at `stickMode`): hand it a zero-padded buffer so that byte is defined (assumption r6).
    struct alignas(8) PaddedSysCfg {
        rmaxSdk::RcSysCfgDataUI config;
        unsigned char guard[8];
    };
    PaddedSysCfg padded{};
    if (!g_sysCfg.fromJava(env, raw, padded.config)) return JNI_FALSE;
    rmaxSdk::rcSetRcSysConfig(padded.config);
    return JNI_TRUE;
    JNI_CATCH("setSysConfig", JNI_FALSE)
}

JNI_FN(void, skipPreFlightCheck)(JNIEnv*, jobject) {
    JNI_TRY
    rmaxSdk::rcSkipPreFlightCheck();
    JNI_CATCH_VOID("skipPreFlightCheck")
}

JNI_FN(void, setAdcCalibration)(JNIEnv*, jobject, jint isStart) {
    JNI_TRY
    rmaxSdk::rcSetAdcCalibration(isStart);
    JNI_CATCH_VOID("setAdcCalibration")
}

JNI_FN(void, setUsbToVcp)(JNIEnv*, jobject, jint mode) {
    JNI_TRY
    rmaxSdk::rcSetUsbToVcp(static_cast<std::uint8_t>(mode));
    JNI_CATCH_VOID("setUsbToVcp")
}

JNI_FN(void, rebootRfModule)(JNIEnv*, jobject) {
    JNI_TRY
    rmaxSdk::rcRebootRFModule();
    JNI_CATCH_VOID("rebootRfModule")
}

// Read-only device information (allowed; the update functions next to them in the SDK are not bound).
JNI_FN(jobject, getFwInfo)(JNIEnv* env, jobject) {
    JNI_TRY
    const rmaxSdk::FirmwareInfUI info = rmaxSdk::rcGetFwInf();
    return g_fwInfo.toJava(env, info);
    JNI_CATCH("getFwInfo", nullptr)
}

JNI_FN(void, requestDeviceInfo)(JNIEnv*, jobject) {
    JNI_TRY
    rmaxSdk::rcTaskRequestDeviceInf();
    JNI_CATCH_VOID("requestDeviceInfo")
}

// ---- models and templates ----------------------------------------------------------------------------

JNI_FN(jobjectArray, getModels)(JNIEnv* env, jobject) {
    JNI_TRY
    const std::list<rmaxSdk::RcModelDataUI> models = rmaxSdk::rcGetRcModelList();
    return toJavaArray(env, g_model, models);
    JNI_CATCH("getModels", nullptr)
}

JNI_FN(jobjectArray, getTemplates)(JNIEnv* env, jobject) {
    JNI_TRY
    const std::list<rmaxSdk::RcModelDataUI> templates = rmaxSdk::rcGetTemplateList();
    return toJavaArray(env, g_model, templates);
    JNI_CATCH("getTemplates", nullptr)
}

JNI_FN(jint, getCurrentModelIndex)(JNIEnv*, jobject) {
    JNI_TRY
    return rmaxSdk::rcGetCurrentModelCfgIndex();
    JNI_CATCH("getCurrentModelIndex", -1)
}

JNI_FN(jobject, createModel)(JNIEnv* env, jobject) {
    JNI_TRY
    const rmaxSdk::RcModelDataUI model = rmaxSdk::rcCreateModelCfg();
    return g_model.toJava(env, model);
    JNI_CATCH("createModel", nullptr)
}

JNI_FN(jobject, setModel)(JNIEnv* env, jobject, jobject raw) {
    JNI_TRY
    rmaxSdk::RcModelDataUI model{};
    if (!g_model.fromJava(env, raw, model)) return nullptr;
    const rmaxSdk::RcModelDataUI result = rmaxSdk::rcSetModelCfg(std::move(model));
    return g_model.toJava(env, result);
    JNI_CATCH("setModel", nullptr)
}

JNI_FN(jboolean, saveCurrentCfgToModel)(JNIEnv* env, jobject, jobject raw) {
    JNI_TRY
    rmaxSdk::RcModelDataUI model{};
    if (!g_model.fromJava(env, raw, model)) return JNI_FALSE;
    rmaxSdk::rcSaveCurrentCfgToModel(std::move(model));
    return JNI_TRUE;
    JNI_CATCH("saveCurrentCfgToModel", JNI_FALSE)
}

JNI_FN(jboolean, loadModelFile)(JNIEnv* env, jobject, jstring modelFile) {
    JNI_TRY
    std::string path;
    if (!fromJString(env, modelFile, path) || path.empty()) return JNI_FALSE;
    rmaxSdk::rcLoadModelFile(std::move(path));
    return JNI_TRUE;
    JNI_CATCH("loadModelFile", JNI_FALSE)
}

JNI_FN(jboolean, exportModel)(JNIEnv* env, jobject, jstring sourceFile, jstring destinationFile) {
    JNI_TRY
    std::string source;
    std::string destination;
    if (!fromJString(env, sourceFile, source) || source.empty()) return JNI_FALSE;
    if (!fromJString(env, destinationFile, destination) || destination.empty()) return JNI_FALSE;
    rmaxSdk::rcExportModelCfg(std::move(source), std::move(destination));
    return JNI_TRUE;
    JNI_CATCH("exportModel", JNI_FALSE)
}

JNI_FN(jint, importModel)(JNIEnv* env, jobject, jstring importFile, jstring modelFile) {
    JNI_TRY
    std::string source;
    std::string target;
    if (!fromJString(env, importFile, source) || source.empty()) return -1;
    if (!fromJString(env, modelFile, target) || target.empty()) return -1;
    return rmaxSdk::rcImportModelCfg(std::move(source), std::move(target));
    JNI_CATCH("importModel", -1)
}

JNI_FN(jboolean, deleteModel)(JNIEnv* env, jobject, jstring filePath) {
    JNI_TRY
    std::string path;
    if (!fromJString(env, filePath, path) || path.empty()) return JNI_FALSE;
    rmaxSdk::rcDeleteModel(std::move(path));
    return JNI_TRUE;
    JNI_CATCH("deleteModel", JNI_FALSE)
}

JNI_FN(jboolean, modelToTemplate)(JNIEnv* env, jobject, jstring modelFile, jstring templateName) {
    JNI_TRY
    std::string path;
    std::string name;
    if (!fromJString(env, modelFile, path) || path.empty()) return JNI_FALSE;
    if (!fromJString(env, templateName, name) || name.empty()) return JNI_FALSE;
    rmaxSdk::rcModelToTemplate(std::move(path), std::move(name));
    return JNI_TRUE;
    JNI_CATCH("modelToTemplate", JNI_FALSE)
}

// ---- telemetry list and SDK-side notifications -------------------------------------------------------

JNI_FN(jobjectArray, getTelemetryList)(JNIEnv* env, jobject, jint uiOnly) {
    JNI_TRY
    const std::list<rmaxSdk::TelemetryNotifyUI> items = rmaxSdk::rcGetTelemetryList(uiOnly);
    return toJavaArray(env, g_telemetry, items);
    JNI_CATCH("getTelemetryList", nullptr)
}

JNI_FN(jobjectArray, getNotifyCfgList)(JNIEnv* env, jobject) {
    JNI_TRY
    const std::list<rmaxSdk::TelemetryNotifyUI> items = rmaxSdk::rcGetNotifyCfgList();
    return toJavaArray(env, g_telemetry, items);
    JNI_CATCH("getNotifyCfgList", nullptr)
}

JNI_FN(jint, getNotifyCfgCount)(JNIEnv*, jobject) {
    JNI_TRY
    const std::list<rmaxSdk::TelemetryNotifyUI> items = rmaxSdk::rcGetNotifyCfgList();
    return static_cast<jint>(std::min<std::size_t>(items.size(), static_cast<std::size_t>(kMaxListItems)));
    JNI_CATCH("getNotifyCfgCount", 0)
}

JNI_FN(jobject, getNotifyCfgItem)(JNIEnv* env, jobject, jlong hashValue, jint index) {
    JNI_TRY
    const rmaxSdk::TelemetryNotifyUI item = rmaxSdk::rcGetNotifyCfgItem(static_cast<std::uint64_t>(hashValue), index);
    return g_telemetry.toJava(env, item);
    JNI_CATCH("getNotifyCfgItem", nullptr)
}

JNI_FN(jboolean, setNotifyCfgItem)(JNIEnv* env, jobject, jlong hashValue, jobject raw, jint index) {
    JNI_TRY
    rmaxSdk::TelemetryNotifyUI item{};
    if (!g_telemetry.fromJava(env, raw, item)) return JNI_FALSE;
    rmaxSdk::rcSetNotifyCfgItem(static_cast<std::uint64_t>(hashValue), item, index);
    return JNI_TRUE;
    JNI_CATCH("setNotifyCfgItem", JNI_FALSE)
}

JNI_FN(void, delNotifyItem)(JNIEnv*, jobject, jint index) {
    JNI_TRY
    rmaxSdk::rcDelNotifyItem(index);
    JNI_CATCH_VOID("delNotifyItem")
}

// Takes one triggered notification from the SDK queue; null when the queue is empty. The SDK
// returns the queue length before dequeuing (> 0) for a valid item and -1 otherwise (manual 18.3).
JNI_FN(jobject, pollTelemetryNotify)(JNIEnv* env, jobject) {
    JNI_TRY
    rmaxSdk::TelemetryNotifyUI item{};
    if (rmaxSdk::RCgetTelemetryItemNotify(item) <= 0) return nullptr;
    return g_telemetry.toJava(env, item);
    JNI_CATCH("pollTelemetryNotify", nullptr)
}

// ---- ELRS and CRSF -----------------------------------------------------------------------------------

JNI_FN(void, requestElrsMenu)(JNIEnv*, jobject, jint deviceId) {
    JNI_TRY
    rmaxSdk::rcTaskRequestElrsMenuList(deviceId);
    JNI_CATCH_VOID("requestElrsMenu")
}

JNI_FN(void, elrsSendMenuCmd)(JNIEnv*, jobject, jint idx, jint value) {
    JNI_TRY
    rmaxSdk::rcElrsSendMenuCmd(idx, value);
    JNI_CATCH_VOID("elrsSendMenuCmd")
}

JNI_FN(jint, requestElrsStatus)(JNIEnv*, jobject) {
    JNI_TRY
    return rmaxSdk::rcRequestElrsStatus();
    JNI_CATCH("requestElrsStatus", -1)
}

JNI_FN(jboolean, sendCrsf)(JNIEnv* env, jobject, jbyteArray data) {
    JNI_TRY
    if (data == nullptr) return JNI_FALSE;
    const jsize length = env->GetArrayLength(data);
    if (length <= 0 || length > kMaxCrsfFrameBytes) return JNI_FALSE;
    std::vector<std::uint8_t> frame(static_cast<std::size_t>(length));
    env->GetByteArrayRegion(data, 0, length, reinterpret_cast<jbyte*>(frame.data()));
    if (env->ExceptionCheck()) return JNI_FALSE;
    rmaxSdk::rcSendCrsfRawData(std::move(frame));
    return JNI_TRUE;
    JNI_CATCH("sendCrsf", JNI_FALSE)
}

// ================================================================================================
// Library load
// ================================================================================================

// This library MUST define JNI_OnLoad itself. System.loadLibrary looks the symbol up with dlsym,
// which also searches the DT_NEEDED dependencies: without a definition here it finds the one in
// libQt6Core, which returns JNI_ERR because the Qt Android binding classes are not in the APK.
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_vm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        LOGE("JNI_OnLoad: no JNIEnv");
        return JNI_ERR;
    }

    try {
        // (1) Serial port lock-check patch, before anything can open the port.
        patchQtSerialPortLockCheck();

        // (2) Let Qt cache the JavaVM. It returns false because the Qt binding classes are missing
        // and may leave a ClassNotFoundException behind; both are expected and ignored.
        const bool qtJniOk = QtAndroidPrivate::initJNI(vm, env);
        LOGI("JNI_OnLoad: QtAndroidPrivate::initJNI -> %s", qtJniOk ? "ok" : "failed (expected, ignored)");
        if (env->ExceptionCheck()) env->ExceptionClear();
    } catch (...) {
        LOGE("JNI_OnLoad: C++ exception during Qt preparation");
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    if (pthread_key_create(&g_attachKey, detachCurrentThread) != 0) {
        LOGE("JNI_OnLoad: pthread_key_create failed");
        return JNI_ERR;
    }
    g_attachKeyValid = true;

    bool ok = lookupClass(env, "org/openax12/sdk/SdkNative", &g_nativeClass, nullptr);
    if (ok) {
        g_onNativeMessage =
            env->GetStaticMethodID(g_nativeClass, "onNativeMessage", "(I[I[D[B[Ljava/lang/String;)V");
        if (g_onNativeMessage == nullptr) {
            LOGE("JNI_OnLoad: SdkNative.onNativeMessage(int, int[], double[], byte[], String[]) not found");
            clearPendingException(env, "onNativeMessage");
            ok = false;
        }
    }
    ok = lookupClass(env, "java/lang/String", &g_stringClass, nullptr) && ok;
    ok = g_src.init(env) && ok;
    ok = g_chOut.init(env) && ok;
    ok = g_mix.init(env) && ok;
    ok = g_dr.init(env) && ok;
    ok = g_curve.init(env) && ok;
    ok = g_timer.init(env) && ok;
    ok = g_sysCfg.init(env) && ok;
    ok = g_model.init(env) && ok;
    ok = g_fwInfo.init(env) && ok;
    ok = g_telemetry.init(env) && ok;
    if (!ok) {
        LOGE("JNI_OnLoad: binding the Kotlin classes failed, see the messages above");
        clearPendingException(env, "JNI_OnLoad");
        return JNI_ERR;
    }
    LOGI("JNI_OnLoad: ok");
    return JNI_VERSION_1_6;
}
