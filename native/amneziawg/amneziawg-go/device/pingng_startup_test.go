package device

import "testing"

// Both peers use generated keys and in-memory datagram binds. This exercises
// the user's feature combination without storing their credentials or requiring
// a real server, privileged sockets, or an Android VPN.
func TestPingNgAdvancedProfile(t *testing.T) {
	pair := genTestPair(t, false,
		"jc", "6", "jmin", "86", "jmax", "220",
		"s1", "123", "s2", "89", "s3", "35", "s4", "22",
		"h1", "413209252", "h2", "537973910",
		"h3", "1230367076", "h4", "2026571936",
		"i1", "<r 217>",
		"header_protection_key", "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20",
		"content_padding_addition", "16-48",
		"rekey_after_time", "103-131", "rekey_timeout", "6-7",
		"reject_after_time", "190-273", "keepalive_timeout", "8-12",
		"max_handshake_attempts", "18-41",
		"random_trailers", "1", "disable_cookies", "1",
	)
	if t.Failed() {
		return
	}
	pair.Send(t, Ping, nil)
	pair.Send(t, Pong, nil)
}
