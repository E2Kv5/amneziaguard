package com.amneziaguard.core.tunnel

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.amnezia.awg.backend.Backend
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.NoopTunnelActionHandler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lazily creates the AmneziaWG userspace [GoBackend]. The backend loads native
 * libraries on construction, so it is built on first use and reused for the
 * app's lifetime.
 *
 * The [NoopTunnelActionHandler] disables PreUp/PostUp shell scripts; the root
 * script handler is swapped in only when the advanced firewall mode is enabled.
 */
@Singleton
class BackendProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val backend: Backend by lazy {
        GoBackend(context, NoopTunnelActionHandler())
    }
}
