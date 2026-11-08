# Feature status — TunerOS

Status labels mean *code present*, not real-device acceptance.

| Capability | Status | Evidence / limitation |
|---|---|---|
| Android TV + phone single app | Initial scaffold | Manifest has launcher and Leanback entries; real-device validation pending |
| M3U import | Initial implementation | Bounded streaming parser; source download from URL; JVM smoke test passed |
| XMLTV import | Initial implementation | Secure SAX parser and JVM smoke test; large-feed integration unverified |
| Search channel by name | Initial implementation | UI code; no device test |
| Optional hide channels with no current EPG | Initial implementation | UI filter; no device test |
| Click/OK channel playback | Initial implementation | Media3 UI; not device tested |
| Stream Doctor | Planned | Error code reporting only in alpha |
| Guide Doctor | Planned | Foundation available; no repair UI yet |
| 2–4 tile Multiview, selectable audio | Planned | Capacity and selection validation functions present; no player UI yet |
| Classic Cable full timeline | Planned | Current alpha uses channel list; timeline not built |
| Favorites, profiles, local database | Planned | Not implemented |
| Built-in channel catalog | Deferred | Requires content redistribution rights audit |
| FastChannels on-device server | Deferred | Explicitly outside current implementation |
| Signing, installation, real-device acceptance | Unverified | No Android SDK available in current local execution environment |
