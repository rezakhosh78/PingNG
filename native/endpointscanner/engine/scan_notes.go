package main

// Shared status text used by scan reports and account registration.
func i1Note() string {
	return i1NoteFor(awgI1, genI1Label)
}

func i1NoteFor(i1, label string) string {
	if label != "" {
		return "generated " + label + " I1"
	}
	switch i1 {
	case "":
		return "no I1"
	case i1Default:
		return "default iCloud I1"
	}
	return "custom I1"
}
