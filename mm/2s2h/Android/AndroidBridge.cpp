// JNI surface for the Android app: the on-screen controller, the engine's menu
// state, and ROM extraction. Extraction is independent of libultraship because
// the launcher calls it before SDL exists, so the directories are passed in.
#ifdef __ANDROID__
#include <jni.h>
#include <android/log.h>
#include <SDL2/SDL.h>
#include <SDL2/SDL_gamecontroller.h>
#include <atomic>
#include <exception>
#include <filesystem>
#include <string>
#include "Extractor/Extract.h"
#include "ship/Context.h"
#include "ship/window/Window.h"
#include "ship/window/gui/Gui.h"

namespace {
constexpr const char* kLogTag = "2Ship";

std::string ToStdString(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars != nullptr ? chars : "";
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(value, chars);
    }
    return result;
}

// The overlay is exposed as an ordinary SDL game controller, so it goes through
// libultraship's input mapping and can be remapped in-game like a physical pad.
SDL_Joystick* gVirtualJoystick = nullptr;
int gVirtualJoystickId = -1;
} // namespace

extern "C" {

JNIEXPORT void JNICALL Java_com_twoship_android_MainActivity_attachController(JNIEnv*, jobject) {
    if (gVirtualJoystick != nullptr) {
        return;
    }
    gVirtualJoystickId = SDL_JoystickAttachVirtual(SDL_JOYSTICK_TYPE_GAMECONTROLLER, 6, 18, 0);
    if (gVirtualJoystickId == -1) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Could not attach the on-screen controller: %s",
                            SDL_GetError());
        return;
    }
    gVirtualJoystick = SDL_JoystickOpen(gVirtualJoystickId);
    if (gVirtualJoystick == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Could not open the on-screen controller: %s", SDL_GetError());
        SDL_JoystickDetachVirtual(gVirtualJoystickId);
        gVirtualJoystickId = -1;
    }
}

JNIEXPORT void JNICALL Java_com_twoship_android_MainActivity_detachController(JNIEnv*, jobject) {
    if (gVirtualJoystick == nullptr) {
        return;
    }
    SDL_JoystickClose(gVirtualJoystick);
    SDL_JoystickDetachVirtual(gVirtualJoystickId);
    gVirtualJoystick = nullptr;
    gVirtualJoystickId = -1;
}

JNIEXPORT void JNICALL Java_com_twoship_android_MainActivity_setButton(JNIEnv*, jobject, jint button, jboolean value) {
    if (gVirtualJoystick == nullptr) {
        return;
    }
    // A negative id addresses an axis, so Z and R can be driven by plain buttons.
    if (button < 0) {
        SDL_JoystickSetVirtualAxis(gVirtualJoystick, -button, value ? SDL_MAX_SINT16 : -SDL_MAX_SINT16);
    } else {
        SDL_JoystickSetVirtualButton(gVirtualJoystick, button, value);
    }
}

JNIEXPORT void JNICALL Java_com_twoship_android_MainActivity_setAxis(JNIEnv*, jobject, jint axis, jshort value) {
    if (gVirtualJoystick == nullptr) {
        return;
    }
    SDL_JoystickSetVirtualAxis(gVirtualJoystick, axis, value);
}

// Polled from the UI thread while the game thread renders. A torn read only
// flips which overlay buttons show, and corrects itself on the next poll.
JNIEXPORT jboolean JNICALL Java_com_twoship_android_MainActivity_isMenuOpen(JNIEnv*, jobject) {
    auto* context = Ship::Context::GetRawInstance();
    if (context == nullptr) {
        return JNI_FALSE;
    }
    auto window = context->GetWindow();
    if (window == nullptr) {
        return JNI_FALSE;
    }
    auto gui = window->GetGui();
    if (gui == nullptr) {
        return JNI_FALSE;
    }
    return gui->GetMenuOrMenubarVisible() ? JNI_TRUE : JNI_FALSE;
}

// Runs ZAPD over the ROM at romPath. sourceDir holds assets/ (unpacked from the
// APK by the launcher) and mm.o2r ends up in destDir. Returns null on success,
// or a message describing what went wrong.
JNIEXPORT jstring JNICALL Java_com_twoship_android_GameAssets_nativeGenerateGameArchive(JNIEnv* env, jobject,
                                                                                       jstring jRomPath,
                                                                                       jstring jSourceDir,
                                                                                       jstring jDestDir) {
    const std::string romPath = ToStdString(env, jRomPath);
    const std::string sourceDir = ToStdString(env, jSourceDir);
    const std::string destDir = ToStdString(env, jDestDir);

    const auto fail = [env](const std::string& message) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Asset generation failed: %s", message.c_str());
        return env->NewStringUTF(message.c_str());
    };

    Extractor extractor;
    if (!extractor.RunFileStandalone(romPath)) {
        return fail("Could not read a supported Majora's Mask ROM at " + romPath + ".");
    }

    __android_log_print(ANDROID_LOG_INFO, kLogTag, "Extracting %s into %s", romPath.c_str(), destDir.c_str());
    std::atomic<size_t> assetCount{ 0 };
    std::atomic<size_t> totalAssets{ 0 };
    try {
        extractor.CallZapd(sourceDir, destDir, &assetCount, &totalAssets);
    } catch (const std::exception& error) {
        return fail(std::string("ZAPD could not extract the ROM: ") + error.what());
    } catch (...) {
        return fail("ZAPD could not extract the ROM.");
    }

    // CallZapd leaves mm.o2r in the directory it ran in.
    std::error_code error;
    const std::filesystem::path produced = std::filesystem::path(sourceDir) / "mm.o2r";
    const std::filesystem::path archive = std::filesystem::path(destDir) / "mm.o2r";
    if (produced != archive && std::filesystem::exists(produced, error)) {
        std::filesystem::copy_file(produced, archive, std::filesystem::copy_options::overwrite_existing, error);
        if (error) {
            return fail("Could not move mm.o2r into place: " + error.message());
        }
    }
    if (!std::filesystem::exists(archive, error) || std::filesystem::file_size(archive, error) == 0) {
        return fail("ZAPD finished without producing mm.o2r. Check that the ROM is a supported "
                    "Majora's Mask release.");
    }

    __android_log_print(ANDROID_LOG_INFO, kLogTag, "Wrote %s", archive.c_str());
    return nullptr;
}

} // extern "C"
#endif // __ANDROID__
