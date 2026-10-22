# Smarter connection health: implementation and verification

1. Add a separate bounded read-only watchdog on both platforms. Its network round must continue during discovery, use explicit loopback proxy routing and strict HTTPS, and discard results from an old session/node/profile.
2. Check independent HTTPS destinations including application origins. Require confirmation of broad failure; do not switch solely because one site returns an error or one native log line appears. Native AUTOPILOT-route failures may accelerate the next confirmation without storing destinations.
3. Apply state changes and selector mutations on the existing controller lane. Yield long discovery work to urgent confirmed recovery. Freshly verify connectivity, service eligibility and application reachability before promoting a replacement.
4. Keep a separate bounded cooldown for confirmed application-path failures. A successful lightweight latency probe must not erase that failure. Require fresh proof before reuse; preserve healthy pins and explicit pause semantics.
5. Expose fixed diagnostic codes, last check and recovery reason. No arbitrary native log payload, browsing URL or credential enters the new diagnostics.
6. Verify partial outage with Google still responding; transient and single-site failures; stale completions after switching/stopping; recovery during a slow sweep; failed candidate rejection; cooldown and renewed eligibility. Add isolated production-transport/runtime probes and runnable test-count receipts. Run existing build, lint and native CI gates before signed Android publication.
