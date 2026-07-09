// Package mobile is a gomobile-friendly wrapper around doubletake's AirPlay-2
// mirror sender, for use from an Android app.
//
// The Android side captures the screen (MediaProjection) and encodes H.264
// (MediaCodec), then pushes the Annex-B byte stream in via WriteH264. All of
// the AirPlay protocol — pair-verify, SETUP/RECORD, ChaCha20 stream encryption,
// NTP timing, type-110 packetization — runs in the Go core unchanged.
//
// Pairing is assumed to already exist (pairingID + 32-byte ed25519 seed); the
// app stores those after a one-time PIN pairing. FairPlay is auto-skipped when
// the receiver does not advertise FPSAP (e.g. TCL Roku).
package mobile

import (
	"context"
	"crypto/ed25519"
	"errors"
	"fmt"
	"io"
	"sync"

	"doubletake/internal/airplay"
)

// Session is a live mirror stream. Create with Start, feed with WriteH264,
// end with Stop.
type Session struct {
	cancel context.CancelFunc
	pw     *io.PipeWriter

	mu     sync.Mutex
	closed bool
}

// Start connects to the AirPlay receiver at host:port using an existing
// pairing, establishes the mirror session, and begins a background streaming
// loop that consumes H.264 pushed via WriteH264. It returns once the mirror
// session is ready (data port negotiated) or an error if any step fails.
//
// ed25519Seed must be the 32-byte seed of the controller identity registered
// with the receiver during pairing. bitrate is in kbps (0 = auto).
func Start(host string, port int, pairingID string, ed25519Seed []byte, fps, bitrate int) (*Session, error) {
	if len(ed25519Seed) != ed25519.SeedSize {
		return nil, fmt.Errorf("ed25519 seed must be %d bytes, got %d", ed25519.SeedSize, len(ed25519Seed))
	}
	ctx, cancel := context.WithCancel(context.Background())

	client := airplay.NewAirPlayClient(host, port)
	if err := client.Connect(ctx); err != nil {
		cancel()
		return nil, fmt.Errorf("connect: %w", err)
	}
	if _, err := client.GetInfo(); err != nil {
		cancel()
		return nil, fmt.Errorf("get info: %w", err)
	}

	priv := ed25519.NewKeyFromSeed(ed25519Seed)
	pub := priv.Public().(ed25519.PublicKey)
	client.PairingID = pairingID
	client.PairKeys = &airplay.PairKeys{Ed25519Public: pub, Ed25519Private: priv}
	if err := client.PairVerify(ctx); err != nil {
		cancel()
		return nil, fmt.Errorf("pair-verify: %w", err)
	}

	// FairPlay is only required when the receiver advertises FPSAP. TCL Roku
	// does not, so this returns ErrFairPlayUnsupported and we continue with the
	// pair-verify-derived DataStream keys.
	if client.FpEkey == nil {
		if err := client.FairPlaySetup(ctx); err != nil && !errors.Is(err, airplay.ErrFairPlayUnsupported) {
			cancel()
			return nil, fmt.Errorf("fairplay setup: %w", err)
		}
	}

	session, err := client.SetupMirror(ctx, airplay.StreamConfig{
		FPS:     fps,
		Bitrate: bitrate,
		NoAudio: true,
	})
	if err != nil {
		cancel()
		return nil, fmt.Errorf("setup mirror: %w", err)
	}

	pr, pw := io.Pipe()
	s := &Session{cancel: cancel, pw: pw}
	go func() {
		_ = session.StreamFrames(ctx, pr, 0)
		session.Close()
	}()
	return s, nil
}

// WriteH264 pushes a chunk of the H.264 Annex-B byte stream (as produced by
// MediaCodec) into the sender. Chunks need not align to NAL boundaries.
func (s *Session) WriteH264(frame []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return errors.New("session closed")
	}
	_, err := s.pw.Write(frame)
	return err
}

// Stop ends the mirror session and releases resources. Safe to call twice.
func (s *Session) Stop() {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return
	}
	s.closed = true
	s.mu.Unlock()
	_ = s.pw.Close()
	s.cancel()
}
