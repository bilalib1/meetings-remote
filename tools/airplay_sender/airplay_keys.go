package airplay

import "crypto/rand"

// deriveStreamKeys supplies the non-FairPlay key setup used by the mirror
// transport. When pair-verify has negotiated encrypted RTSP keys, those keys
// seed the stream. The random fallback preserves DoubleTake's debug behavior.
// This is intentionally the only code retained from upstream fairplay.go.
func (c *AirPlayClient) deriveStreamKeys() error {
	if c.encWriteKey == nil {
		key := make([]byte, 16)
		iv := make([]byte, 16)
		if _, err := rand.Read(key); err != nil {
			return err
		}
		if _, err := rand.Read(iv); err != nil {
			return err
		}
		c.streamKey = key
		c.streamIV = iv
		return nil
	}

	c.streamKey = make([]byte, 16)
	c.streamIV = make([]byte, 16)
	copy(c.streamKey, c.encWriteKey)
	copy(c.streamIV, c.encReadKey)
	return nil
}
