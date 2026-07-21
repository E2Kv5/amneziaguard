package com.amneziaguard.core.netstack

/** Per-flow decision made by the engine's policy callback. */
enum class RelayPolicy {
    /** Relay the flow through the SOCKS5 (tunnelled). */
    RELAY,

    /** Drop the flow (BLOCK / no internet). */
    DROP,
}
