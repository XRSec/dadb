/*
 * Copyright (c) 2021 mobile.dev inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dadb

import java.io.File
import java.util.concurrent.ConcurrentMap

/**
 * Platform-safe alternatives for APIs that are missing or unreliable on older Android runtimes.
 *
 * Keep these helpers free of Android SDK references because the core DADB artifact also runs on
 * desktop JVMs.
 */
internal object PlatformApiCompat {
    private const val EXECUTE_BITS = 0b001_001_001

    /**
     * Produces a safe remote mode without loading java.nio.file, which is unavailable before
     * Android API 26. Preserve executability when it can be observed through java.io.File.
     */
    fun readLocalFileMode(file: File, defaultMode: Int): Int =
        if (file.canExecute()) defaultMode or EXECUTE_BITS else defaultMode

    /**
     * Atomic get-or-create using ConcurrentMap.putIfAbsent, available on every supported Android
     * version. This intentionally avoids Java 8 Map.computeIfAbsent and java.util.function.
     */
    inline fun <K, V : Any> getOrPut(
        map: ConcurrentMap<K, V>,
        key: K,
        create: () -> V,
    ): V {
        map[key]?.let { return it }
        val created = create()
        return map.putIfAbsent(key, created) ?: created
    }
}
