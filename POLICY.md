# Zoom policy, cost & feasibility notes (researched July 2026)

Short version: **what you actually asked for — a tablet remote that controls
the official Zoom client on your PC — does not touch Zoom's SDK, servers, or
account limits at all.** It's local UI automation of an app you already run.
So there is no per-use tracking, no scaling charge, and no meeting limit
introduced *by the remote itself*. Zoom only ever sees your normal client.

## The SDK confusion, cleared up

You'd heard of an SDK "to custom-write a Zoom app on their official servers."
That's the **Zoom Meeting SDK** (github.com/zoom — `meetingsdk-web`, plus
native packages on marketplace.zoom.us). It lets you build a *brand-new*
meeting client. It does **not** remote-control the existing `zoom.us` desktop
app. Since your goal is to drive the real client on the PC, the SDK is the
wrong layer — hence this project drives the client's own menus instead.

If you ever *did* want the SDK path (e.g. a fully custom video UI):
- Creating SDK credentials is **free** on any account, incl. free/Basic.
- The Meeting SDK **follows the account's license model** — no per-minute
  billing. A meeting hosted by a free account still has the **40-min limit**.
- The **Video SDK** (different product) *is* metered per participant-minute
  (~$0.0035/min after a free monthly allotment) — that's the one that "charges
  you at scale." The Meeting SDK is not metered.
- Since **March 2, 2026**, SDK apps joining meetings hosted by *other*
  accounts need an OBF/ZAK token; joining your own account's meetings is
  unaffected. Internal-only apps need **no marketplace review**.

## Tracking / scaling for THIS project

- The remote makes **zero** Zoom API or SDK calls. Nothing is attributable to
  any Zoom developer credential. There is nothing for Zoom to rate-limit,
  meter, or bill as you add more tablets/rooms — each just drives its own
  local client.
- The only limits that apply are the ordinary ones on whatever Zoom account
  hosts the meeting (e.g. 40-min cap on free plans) — identical to using Zoom
  by hand. The remote doesn't change them.

## Is automating the official client allowed?

No explicit prohibition found in Zoom's Terms of Service or Acceptable Use
Guidelines against sending keystrokes / UI automation to the client from
another device. The relevant ToS §8 clauses target reverse-engineering the
software and disrupting/overburdening Zoom's *services/networks* — not local
UI control. Strong real-world precedent that Zoom tolerates and even embraces
this:
- **Elgato Stream Deck** is now officially **"Zoom Certified"** with a
  first-party plugin and two-way state sync.
- The **Lostdomain Stream Deck Zoom plugin** used the exact technique here
  (AppleScript scanning + clicking Zoom's menus on macOS); never blocked.
- **ZoomOSC** (OSC control surface) — Zoom **acquired** its maker (Liminal)
  in Dec 2021 and now distributes it themselves.

Caveat: this is not legal advice, and §8's anti-reverse-engineering language
is broad. For personal/team use driving your own meetings, the risk is very
low and there's no known enforcement against such controllers.

## No official local control API

Zoom exposes no general local API for the desktop client. `zoommtg://` URLs
can *launch/join* meetings (this project uses that for `/api/join`) but can't
mute/toggle mid-meeting. The old localhost web server was removed in 2019
(CVE-2019-13450). So UI automation is the supported-in-practice route, which
is what the certified hardware controllers effectively rely on.

## Feasibility verdict

**Fully feasible, and built.** Every in-meeting control you'd want maps to a
Zoom menu item we can click, and `/api/status` reads live state back from
those same menus so the tablet buttons stay in sync. Verified working
end-to-end: tablet → Mac → real Zoom client (mute/unmute, video, leave with a
safe confirm, live status). Scales to as many PC+tablet pairs as you like
with no Zoom-side cost or tracking.

### Sources
- Zoom ToS: https://www.zoom.com/en/trust/terms/
- Acceptable Use: https://www.zoom.com/en/trust/acceptable-use-guidelines/
- Meeting SDK docs (license model): https://developers.zoom.us/docs/meeting-sdk/
- OBF transition (Mar 2026): https://developers.zoom.us/blog/transition-to-obf-token-meetingsdk-apps/
- Video SDK pricing: https://zoom.us/pricing/developer
- REST API rate limits: https://developers.zoom.us/docs/api/rate-limits/
- Stream Deck Zoom Certified: https://www.elgato.com/us/en/explorer/products/stream-deck/stream-deck-is-now-zoom-certified-heres-what-that-means/
- Lostdomain plugin: https://lostdomain.org/stream-deck-plugin-for-zoom
- ZoomOSC / Liminal acquisition: https://www.zoom.com/en/blog/zoom-future-of-events-expanded-offerings-acquisition-of-liminal-assets/
- CVE-2019-13450 (removed local web server): https://www.rapid7.com/blog/post/2019/07/10/zoom-video-snooping-what-you-need-to-know/
