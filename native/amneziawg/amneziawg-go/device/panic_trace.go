package device

import "runtime/debug"

// recoverRoutine keeps a malformed or incompatible packet from terminating the
// Android service process. The full stack is emitted through the device logger,
// allowing the caller to report a failed tunnel instead of losing the service.
func (device *Device) recoverRoutine(name string) {
	if recovered := recover(); recovered != nil {
		device.log.Errorf("PANIC in %s: %v\n%s", name, recovered, debug.Stack())
	}
}
