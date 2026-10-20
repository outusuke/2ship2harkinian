package com.twoship.android

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Everything the game needs on disk, and how it gets there.
 *
 * libultraship resolves every runtime path on Android through
 * `SDL_AndroidGetExternalStoragePath()`, i.e. `getExternalFilesDir(null)`, which
 * MainActivity points at the same directory as [gameDir]:
 *
 *     Android/media/com.twoship.android/
 *       baserom.z64          the user's ROM, copied in by the launcher
 *       assets/              ZAPD's xml, file lists and configs, unpacked from the APK
 *       2ship.o2r            engine assets, built on CI and shipped in the APK
 *       mm.o2r               generated on-device from the ROM
 *       mods/                user mods
 *
 * The unpacking happens here rather than inside the engine because ZAPD needs
 * assets/ on disk before the game process starts. The zip itself is assembled
 * by android/app/build.gradle.kts.
 */
object GameAssets {
    // ZAPD only accepts a .n64, .z64 or .v64 file name; the contents are checked natively.
    const val ROM_NAME = "baserom.z64"

    private const val TAG = "GameAssets"
    private const val GAME_ARCHIVE = "mm.o2r"
    private const val CONTROLLER_DB = "gamecontrollerdb.txt"
    private const val GAMEDATA_ZIP = "gamedata.zip"
    private const val GAMEDATA_VERSION = "gamedata.version"
    private const val STAMP = ".gamedata.version"

    // Deprecated since API 30, but still the way to reach Android/media, which Syncthing can sync.
    @Suppress("DEPRECATION")
    fun gameDir(context: Context): File =
        (context.externalMediaDirs.firstOrNull { it != null } ?: context.filesDir).also { it.mkdirs() }

    fun romFile(context: Context) = File(gameDir(context), ROM_NAME)

    fun gameArchive(context: Context) = File(gameDir(context), GAME_ARCHIVE)

    fun modsDir(context: Context) = File(gameDir(context), "mods")


    fun isExtracted(context: Context) = gameArchive(context).length() > 0

    /**
     * Unpacks the APK-bundled extraction inputs into [gameDir].
     *
     * Re-runs whenever the packaged zip changes, so an app update ships new
     * recipes and a new 2ship.o2r without the user clearing data. Returns
     * an error message, or null when the directory is ready.
     */
    fun stageBundledAssets(context: Context): String? {
        val target = gameDir(context)
        val stamp = File(target, STAMP)

        return try {
            val expected = context.assets.open(GAMEDATA_VERSION).use { it.readBytes().decodeToString() }
            if (stamp.takeIf { it.isFile }?.readText() == expected) {
                return null
            }

            context.assets.open(GAMEDATA_ZIP).use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val destination = File(target, entry.name).canonicalFile

                        // An entry named ../../something would otherwise write
                        // outside the game directory.
                        if (!destination.path.startsWith(target.canonicalPath + File.separator)) {
                            Log.w(TAG, "Skipping suspicious zip entry ${entry.name}")
                            zip.closeEntry()
                            continue
                        }

                        if (entry.isDirectory) {
                            destination.mkdirs()
                        } else {
                            destination.parentFile?.mkdirs()
                            FileOutputStream(destination).use { output -> zip.copyTo(output, COPY_BUFFER_BYTES) }
                        }
                        zip.closeEntry()
                    }
                }
            }

            // Users are meant to edit this one, so only seed it once.
            val controllerDb = File(target, CONTROLLER_DB)
            if (!controllerDb.isFile) {
                copyAsset(context, CONTROLLER_DB, controllerDb)
            }

            val mods = modsDir(context)
            mods.mkdirs()
            if (mods.list().isNullOrEmpty()) {
                // Only a hint for the player, and nothing reads it back, so it
                // must not fail an extraction that has otherwise succeeded.
                try {
                    copyAsset(context, "mods/place_mods_here.txt", File(mods, "place_mods_here.txt"))
                } catch (error: IOException) {
                    Log.w(TAG, "Could not write the mods placeholder", error)
                }
            }

            // New recipes mean the previous archive is stale.
            gameArchive(context).delete()

            stamp.writeText(expected)
            null
        } catch (error: IOException) {
            Log.e(TAG, "Could not unpack the bundled assets", error)
            "Could not unpack the bundled game files: ${error.message}"
        }
    }

    /**
     * Runs ZAPD over [romFile] to produce mm.o2r. Blocking and slow — expect a
     * minute or more on older hardware. Returns an error message, or null on
     * success.
     */
    fun generateGameArchive(context: Context): String? {
        val dir = gameDir(context)
        return nativeGenerateGameArchive(romFile(context).absolutePath, dir.absolutePath, dir.absolutePath)
    }

    private const val COPY_BUFFER_BYTES = 1 shl 17

    private fun copyAsset(context: Context, assetPath: String, target: File) {
        target.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    private external fun nativeGenerateGameArchive(rom: String, sourceDir: String, destDir: String): String?

    init {
        // libmain.so carries both the game and the ZAPD extractor.
        System.loadLibrary("SDL2")
        System.loadLibrary("main")
    }
}
