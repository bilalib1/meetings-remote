# Zoom platform, policy, and feasibility notes

Updated 2026-07-13. This document describes the product that is actually
shipped: an Android room appliance built with the Zoom Meeting SDK. Earlier
desktop-client automation experiments are not part of the current application.

## Product and data flow

Mobile Remote is a standalone Android meeting client. A user signs in through
Zoom's user-managed OAuth flow, then the app hosts that user's Personal Meeting
ID through the Android Meeting SDK. An optional RTSP camera can replace the
tablet camera; microphone capture and audio/video synchronization run on-device.

The Android package contains no Zoom signing secret, OAuth refresh token, or
OAuth access token. It stores an opaque, revocable session identifier. The
Cloudflare Worker performs Authorization Code + S256 PKCE, keeps refresh tokens
server-side, fetches a fresh short-lived ZAK immediately before hosting, and
signs Meeting SDK JWTs. Meeting audio and video travel directly between the SDK
and Zoom and never pass through the Worker or D1.

Requested granular scopes are limited to:

- `user:read:user` to display the signed-in user and obtain their PMI.
- `user:read:zak` to host that user's own meeting with the Meeting SDK.
- `meeting:update:status` to end the user's own crash-stranded meeting so a room
  can recover without waiting for the prior meeting to expire.

Sign-out revokes the Zoom OAuth grant and deletes the server session.
Deauthorization also deletes the session and completes Zoom's data-compliance
callback. The public privacy policy documents retention and user rights.

## Zoom app model and publication

One Zoom General App supplies both capabilities: a public-client OAuth
application and the Meeting SDK feature. Development and production credential
sets are distinct, and all production Worker bindings must come from the same
production mode. The Android client never switches or embeds these values.

Marketplace publication is required for use beyond the developer-owned account
and gives Zoom an opportunity to review the OAuth scopes, listing, security
design, reviewer instructions, and Meeting SDK use. The listing is named
"Mobile Remote" to avoid implying that Zoom owns or endorses the product.

The Meeting SDK follows the host account's Zoom meeting entitlements. It is
different from the usage-metered Zoom Video SDK. Ordinary host-account limits,
including any duration or participant limits, continue to apply.

## Security posture

- Exact HTTPS redirect and allow-list entries, S256 PKCE, unpredictable state,
  and a verified Android App Link protect the authorization return.
- OAuth/session fields are envelope-encrypted with AES-256-GCM in D1; keys and
  Zoom credentials are held as Cloudflare Worker secrets.
- Zoom webhook signatures and freshness are verified, replay is rejected, and
  public API traffic is rate-limited.
- Release builds disable cleartext traffic and debug injection hooks. Android
  Lint, unit tests, APK/AAB builds, 16 KB native-library alignment, secret scans,
  and a 54-check live Worker security suite gate releases.
- A ZAK is treated according to its JWT expiry rather than an application cache
  duration. Hosting always refreshes it immediately before calling the SDK.

The application has not yet undergone an independent third-party penetration
test; that is disclosed accurately in the Zoom technical-design questionnaire.

## References

- Zoom Meeting SDK: https://developers.zoom.us/docs/meeting-sdk/
- Meeting SDK authentication: https://developers.zoom.us/docs/meeting-sdk/auth/
- Zoom App Marketplace review: https://developers.zoom.us/docs/distribute/app-review-process/
- Zoom security requirements: https://developers.zoom.us/docs/distribute/security-requirements/
- Privacy policy: https://meetingsremote.app/privacy
- Architecture and testing evidence: `docs/security/`
