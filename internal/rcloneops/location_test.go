package rcloneops

import "testing"

func TestLocationNormalizationAndJoining(t *testing.T) {
	got, err := NormalizeLocation("rclone://drive/folder/../safe")
	if err != nil || got != "drive:safe" {
		t.Fatalf("got=%q err=%v", got, err)
	}
	uri, err := JoinURI("rclone://drive/BKX", "Phone backup")
	if err != nil || uri != "rclone://drive/BKX/Phone%20backup" {
		t.Fatalf("uri=%q err=%v", uri, err)
	}
	for _, bad := range []string{
		"s3://bucket/x",
		"rclone://user:secret@drive/x",
		"rclone://drive/x?token=secret",
		"rclone://drive/x#frag",
		"rclone://bad name/x",
	} {
		if _, err := NormalizeLocation(bad); err == nil {
			t.Fatalf("unsafe location accepted: %s", bad)
		}
	}
}
