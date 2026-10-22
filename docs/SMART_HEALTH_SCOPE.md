# Smarter connection health: scope

22 October 2026. User report: applications can stop responding while Autopilot still considers the selected node healthy.

Improve Android and Windows connection monitoring without changing the currently running desktop VPN. Separate background discovery from active connection monitoring; verify real HTTPS paths; confirm failures before switching; verify replacements freshly; avoid immediately returning to a failing node. Preserve country priorities, healthy pins, pause controls, strict TLS and provider request cooldowns.

Evidence comes first from the current desktop application log. The Android phone is not currently attached over USB; device-specific reproduction is therefore unavailable in this run. Use isolated local network fixtures and existing native Android CI to validate the implementation, and state the remaining physical-device limit.

Do not inspect users' request bodies, persist browsing destinations, reset the live VPN, change routes/proxy settings, or install a desktop update during this work. The health probe may read a bounded first chunk of its own fixed HTTPS response to detect body stalls. Package new versions only after verification. Android delivery uses the existing signed release/update feed.
