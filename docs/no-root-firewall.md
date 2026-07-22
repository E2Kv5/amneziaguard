# No-root per-app firewall (userspace datapath)

## Problem

The "no internet" (BLOCK) mode must work **while the AmneziaWG tunnel is up**, without root.
Android allows only one active VPN, and the current design can't do this:

- The tunnel uses the library's `GoBackend`, which hands the tun fd straight to `amneziawg-go`.
  We never see the packets, so we can't drop a blocked app's traffic.
- The separate `GuardVpnService` blackhole can only run when the tunnel is **down** (single VPN slot).

There is no new Android 14/15/16 API for third-party per-app blocking. The only no-root
mechanism is `ConnectivityManager.getConnectionOwnerUid()` (API 29+, Android 10), which maps a
connection 5-tuple to the owning app's UID — but it only helps if **we** own the tun and process
packets ourselves. This is why the app's minSdk is 29: below that the feature cannot exist. This is exactly how RethinkDNS (firestack) does no-root per-app firewall while
tunnelling.

## Why not just use firestack

`firestack` (celzero) has the per-flow firewall hook + a WireGuard proxifier, but does **not**
support AmneziaWG obfuscation (open request: rethink-app#1437). Using it as-is loses the whole
point of AmneziaGuard. `amneziawg-go` has the obfuscation but no per-flow hook. Rethink-parity
means combining the two — hence a userspace datapath of our own.

## Chosen architecture: JVM tun2socks over amneziawg-go's SOCKS5

A single `FilteringVpnService` that we fully own:

```
  app packets ──► TUN (our VpnService)
                   │
                   ▼
            tun2socks engine (JVM)
              • parse IPv4/IPv6 + TCP/UDP
              • per-flow: getConnectionOwnerUid(uid) → AppMode
              • BLOCK  → drop packet / RST
              • VPN    → relay flow to local SOCKS5 (amneziawg-go)
              • BYPASS → relay flow via protect()'d direct socket
                   │
                   ▼
        amneziawg-go in proxy mode = local SOCKS5 server
              (full AWG obfuscation on the WireGuard transport)
```

Key point verified from the library source: `org.amnezia.awg.ProxyGoBackend` exposes **public**
native methods we can call directly:

```java
public static native int  awgStartProxy(String ifName, String config, String uapiPath, int bypass);
public static native int  awgUpdateProxyTunnelPeers(int handle, String settings);
public static native void awgStopProxy();
public static native String awgGetProxyConfig(int handle);
public static native void awgSetSocketProtector(SocketProtector sp);
public static native void awgResetJNIGlobals();
```

`ProxyGoBackend.setupKillSwitch()` shows the recipe: build a `Config` with a
`Socks5Proxy("127.0.0.1:<freePort>", user, pass)` in the `[Interface]`, call
`awgStartProxy(name, quickConfig, uapiPath, bypass = 1)`. amneziawg-go then runs a **local SOCKS5
server** at that address, backed by the obfuscated WireGuard netstack. The stock code bridges the
tun to it with `hev-socks5-tunnel`; **we replace that bridge with our own tun2socks** so we can
apply per-UID filtering. `awgSetSocketProtector` must be given a protector that calls
`VpnService.protect()` on amneziawg-go's outbound UDP sockets to avoid a routing loop.

## Modules

- `:core-netstack` (new) — pure-Kotlin, JVM-testable:
  - `packet/` — `Ipv4Packet`, `Ipv6Packet`, `TcpSegment`, `UdpDatagram`, `Checksums`, `FlowKey`.
  - `socks/Socks5Client` — SOCKS5 CONNECT (TCP) and UDP ASSOCIATE.
  - `UidResolver` — interface; Android impl wraps `ConnectivityManager.getConnectionOwnerUid`.
  - `Tun2Socks` — TCP state machine + UDP relay engine (the crux; iterated on-device).
- `:core-tunnel` — `AmneziaProxyController` wrapping the `ProxyGoBackend` native calls.
- `:background` — `FilteringVpnService` (our single VpnService) replacing `GoBackend`'s service +
  `GuardVpnService`; the orchestrator drives it.

## Milestones

1. **Foundation (JVM-testable, no device):** packet parse/serialize + checksums, `Socks5Client`,
   `FlowKey`, `UidResolver` interface. Unit tests.
2. **amneziawg-go SOCKS5 spike (device):** `AmneziaProxyController.start()` → confirm a local
   SOCKS5 is reachable and the internet works through it with obfuscation. This de-risks the whole
   approach; do it before building the full engine.
3. **tun2socks engine (device iteration):** TCP + UDP relay, wire per-UID filtering.
4. **Integration:** `FilteringVpnService`, orchestrator/kill-switch/DNS-guard on the new datapath,
   retire `GuardVpnService`. Keep the root iptables tier as a fast path when available.

## Device-validation checklist (needs a real AWG server config + device/emulator)

- [x] Spike: amneziawg-go SOCKS5 comes up and reaches the internet (exit IP = server).
- [x] Same under our own VpnService with `bypass=1` + `protect()` — no routing loop.
- [x] tun2socks TCP relay carries a real TLS session end-to-end (exit IP = server).
- [x] DNS: plain UDP:53 queries answered through the tunnel.
- [x] BLOCK app has no network while the tunnel is up, without root (manually verified).
- [ ] UDP: QUIC/games carried via SOCKS5 UDP ASSOCIATE.
- [ ] Allowed app: normal browsing through the tunnel (exit IP = server).
- [ ] BLOCK app while tunnel UP: no network at all (verify with a test app).
- [ ] BYPASS app: uses the real network (exit IP = local ISP).
- [ ] Wi-Fi ↔ mobile roaming keeps flows alive.
- [ ] Battery/throughput sanity vs. the stock GoBackend path.

## Lessons the device taught us (don't re-learn these)

- `read()` returning **0** on the tun fd means "no packet right now", **not** EOF. Treating it as
  end-of-stream silently kills the datapath after the first idle moment.
- A socket opened immediately after `establish()` can still leave over the underlying network: the
  per-UID routing rules land slightly later, producing a half-open flow (source = Wi-Fi address)
  the engine can never serve. Give routing a moment, or bind explicitly to the VPN network.
- A RST with `seq = 0` is outside the peer's window, so RFC 5961 stacks ignore it. Per RFC 793,
  when the offending segment carries an ACK the RST must take its sequence from that ACK.
- `bypass=1` installs amneziawg-go's socket-protection hook and treats a **0** return from
  `SocketProtector.bypass()` as EACCES; with no VpnService use `bypass=0` instead.

## Risks

- JVM tun2socks correctness/perf (TCP reassembly, MTU, checksums, ICMP). Highest remaining risk.
- UDP rides SOCKS5 UDP ASSOCIATE, which `things-go/go-socks5` dials with wireproxy's netstack
  dialer — so datagrams do traverse the tunnel. Oversized replies (> MTU) are dropped rather than
  fragmented, and DNS falls back to DNS-over-TCP if an association can't be opened.
- Battery: userspace datapath is heavier than the kernel/GoBackend path.
