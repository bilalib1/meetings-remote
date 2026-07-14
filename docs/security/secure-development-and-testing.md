# Mobile Remote — Secure Development and Testing Evidence

Prepared for Zoom App Marketplace review. Updated 2026-07-13.

## Scope

This document covers the Android application in `room/` and the production
Cloudflare Worker OAuth/token broker in `backend/cf-worker/`. Meeting audio and
video travel directly between the Android Zoom Meeting SDK and Zoom; they never
cross or enter the Worker or D1 database.

## Secure development lifecycle

1. Security and privacy requirements are recorded before implementation in the
   living Play Store/OAuth plan. Threats considered include secret extraction
   from an APK, OAuth CSRF/code interception, session theft, webhook forgery and
   replay, sensitive logging, dependency vulnerabilities, overbroad scopes, and
   residual data after deauthorization.
2. Changes are version-controlled in Git as small commits. The developer reviews
   the diff, automated checks, release-manifest output, and deployment dry run
   before pushing and deploying.
3. Secrets are never committed. SDK signing credentials, OAuth credentials,
   webhook verification secrets, and the data-encryption key are Cloudflare
   Worker secrets. Release-only Android configuration removes development test
   hooks, cleartext traffic, and local AirPlay pairing material.
4. OAuth uses Authorization Code with S256 PKCE, an unpredictable state/session
   value, an exact HTTPS redirect allow list, and a verified Android App Link.
   Refresh/access tokens never enter the Android app. The app stores only an
   opaque revocable session ID in app-private storage.
5. The Worker enforces input validation, a 50 requests/10 seconds per-IP rate
   limit, authenticated session operations, Zoom webhook HMAC validation, and a
   five-minute webhook replay window. It returns generic errors and does not log
   OAuth tokens, ZAKs, credentials, or personal data.
6. Refresh tokens, Zoom user IDs, display names, PMIs, ZAKs, and PKCE verifiers
   are protected at rest with versioned AES-256-GCM envelopes. The encryption
   key is held separately as a Worker secret. Deauthorization lookup uses a keyed
   user-ID hash rather than plaintext. Sign-out revokes the Zoom token and deletes
   the D1 session; deauthorization performs deletion and Zoom compliance callback.
7. Dependencies are pinned and reviewed during releases. Zoom Meeting SDK is
   reviewed at least quarterly because Zoom raises its minimum supported version
   on a quarterly cadence. Android native libraries are verified for 16 KB page
   alignment before Play release.
8. Production changes are deployed with a backward-compatible migration order,
   black-box verification, and Cloudflare revision rollback available.

## Automated security and quality checks

The release checklist runs:

- Android Lint on the release variant (static analysis), Android unit tests, and
  release APK/AAB builds.
- `npm audit` for the Worker toolchain and `wrangler deploy --dry-run` for Worker
  type/bundle validation.
- A live black-box Worker suite covering 54 success/error/security checks:
  SDK-JWT signature/claims, PKCE challenge binding, state expiry and one-time use,
  invalid/expired sessions, refresh rotation, sign-out, deauthorization HMAC and
  replay rejection, D1 effects, public legal pages, HTTPS/HSTS, asset links, and
  rate limiting.
- On-device lifecycle regression tests that start consecutive real meetings,
  verify fresh short-lived ZAK retrieval immediately before each host attempt,
  confirm host/video/audio state, and end each meeting for all participants.
- Release-manifest inspection confirming the debug receiver and cleartext traffic
  are absent, plus APK string inspection confirming server secrets are absent.

Any failing check blocks release until it is understood and remediated. Results
are retained in CI/local logs and summarized in the repository's living plan.

## Vulnerability and incident response

Security reports are accepted at `ibbilal0@gmail.com`. On a credible report, the
developer will preserve relevant non-sensitive logs, reproduce and scope the
issue, revoke/rotate affected Zoom and Cloudflare secrets, disable or roll back
the Worker revision when containment requires it, patch and retest, notify Zoom
and affected users when required, and document preventive follow-up. Tokens and
personal data are never included in issue trackers or public reports.

## Data protection and user rights

The public privacy policy describes collected fields, purposes, retention,
processors, deletion, and data-subject rights. Users can revoke access and delete
their server-side data in the Android app. Zoom deauthorization also removes the
session. Requests for access, correction, deletion, objection, restriction, or
portability can be sent to `ibbilal0@gmail.com` and are handled after reasonable
identity verification under applicable law.

## Independent testing disclosure

The application has not yet undergone an independent third-party penetration
test. The Zoom Technical Design questionnaire is answered “No” for that item.
