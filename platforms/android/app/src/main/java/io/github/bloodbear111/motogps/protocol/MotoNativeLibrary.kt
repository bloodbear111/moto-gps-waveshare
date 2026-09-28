package io.github.bloodbear111.motogps.protocol

/**
 * Loads the single JNI library that bundles the upstream `shared/` C++ core
 * (coordinates, nav core, nav app and the BLE v1 codec).
 */
internal object MotoNativeLibrary {

    const val NAME = "moto_mobile"

    @Volatile
    var loadFailure: Throwable? = null
        private set

    val isLoaded: Boolean
        get() = loadFailure == null

    init {
        try {
            System.loadLibrary(NAME)
        } catch (error: Throwable) {
            loadFailure = error
        }
    }

    /** Throws a descriptive error instead of an opaque `UnsatisfiedLinkError`. */
    fun requireLoaded() {
        loadFailure?.let { failure ->
            throw IllegalStateException(
                "native library '$NAME' is unavailable: ${failure.message}",
                failure,
            )
        }
    }
}
