# Smarter connection health: evidence

22 October 2026. Findings refer to the pre-change source.

## High: application failure is not the same as a successful latency probe

The current desktop `autopilot.log` records repeated AUTOPILOT-route timeouts to ChatGPT and the Anthropic API beginning at 2026-10-22T14:39:24Z. A recovery switch appears at 14:39:49Z; later repeated failures also occur. The symptoms are corroborated, but these records alone do not prove one unique underlying server outage.

The desktop active check only tests the Google 204 URL (`src/main/autopilot.js:1701`); active service verification defaults to ten minutes (`src/main/config-store.js:18`). Android likewise uses the Google URL (`android/app/src/main/java/app/autopilot/android/VpnEngine.java:315`) and can reuse service checks for five minutes (`SelectionPolicy.java:7`). A partially broken path can pass this check.

## High: discovery and active monitoring share a serialized queue

Desktop tick, sweep and service verification all enter the same mutation queue (`src/main/autopilot.js:1377`). Android schedules its tick on the single controller executor (`VpnEngine.java:18`, `:83`). Android's service-discovery loop can perform successive slow checks without an active-node checkpoint (`:267`). Large/slow discovery can delay failure detection.

## High: Android can promote an older cached candidate

Android eligibility allows latency evidence up to ninety seconds old (`SelectionPolicy.java:7`). The automatic switch only refreshes service checks older than thirty seconds (`VpnEngine.java:298`); it does not always refresh connectivity immediately before promotion.

## Medium: application errors are diagnostic only

Desktop core logs reach the log sink (`src/main/index.js:144`), but the engine only subscribes to core exit/listener errors (`src/main/autopilot.js:198`). Android native observations are session-wide diagnostics (`VpnEngine.java:378`), not a recovery trigger. Selector-group errors cannot safely identify a node after a switch: use them to request fresh confirmation, never directly condemn a node.

## Explicit limit: node outbound checks do not prove every Android app's tunnel path

Android excludes its own UID (`AutopilotVpnService.java:48`). The independent native runtime fixture tests another UID's traffic, but production per-node HTTPS checks do not traverse that same TUN path. Do not describe node checks as proof of all other-app traffic. Changing UID routing is outside this change because it can create loops and interrupt otherwise working connections.
