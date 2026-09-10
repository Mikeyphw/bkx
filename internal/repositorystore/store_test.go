package repositorystore

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"bkx/internal/models"
)

func fixedStore(t *testing.T) *Store {
	t.Helper()
	s := New(filepath.Join(t.TempDir(), "repositories.json"))
	s.Now = func() time.Time { return time.Date(2026, 9, 10, 13, 0, 0, 0, time.UTC) }
	return s
}

func TestCreateLocalAndRcloneRepositories(t *testing.T) {
	s := fixedStore(t)
	local, err := s.Create(models.RepositoryDefinition{RepositoryID: "local-main", Name: "Local", Engine: "restic", Backend: "local", Location: "./backup"})
	if err != nil {
		t.Fatal(err)
	}
	if !filepath.IsAbs(local.Location) || local.ManagedByBKX {
		t.Fatalf("local=%+v", local)
	}

	ref := "restic-drive"
	remote, err := s.Create(models.RepositoryDefinition{RepositoryID: "drive-main", Name: "Drive", Engine: "restic", Backend: "rclone", Location: "rclone://drive/BKX/restic", CredentialRef: &ref})
	if err != nil {
		t.Fatal(err)
	}
	if remote.Location != "rclone://drive/BKX/restic" || remote.CredentialRef == nil || *remote.CredentialRef != ref {
		t.Fatalf("remote=%+v", remote)
	}

	list, err := s.List()
	if err != nil || len(list) != 2 {
		t.Fatalf("list=%+v err=%v", list, err)
	}
	data, err := os.ReadFile(s.Path)
	if err != nil {
		t.Fatal(err)
	}
	text := string(data)
	if strings.Contains(strings.ToLower(text), "password") || strings.Contains(strings.ToLower(text), "access_token") {
		t.Fatalf("repository store leaked secret-shaped fields: %s", text)
	}
}

func TestManagedGoogleDriveIsRestrictedToBkxRemoteAndFolder(t *testing.T) {
	s := fixedStore(t)
	ref := "restic-gdrive"
	got, err := s.Create(models.RepositoryDefinition{RepositoryID: "bkx-drive", Name: "BKX Drive", Engine: "restic", Backend: models.RepositoryBackendManagedGoogleDrive, Location: "rclone://bkx-google-drive/BKX/restic", CredentialRef: &ref})
	if err != nil {
		t.Fatal(err)
	}
	if !got.ManagedByBKX {
		t.Fatalf("managed=%+v", got)
	}

	for _, location := range []string{"rclone://drive/BKX/restic", "rclone://bkx-google-drive/Elsewhere/restic"} {
		if _, err := s.Create(models.RepositoryDefinition{RepositoryID: "bad-" + strings.ReplaceAll(strings.Trim(location, "/"), "/", "-"), Name: "Bad", Engine: "restic", Backend: models.RepositoryBackendManagedGoogleDrive, Location: location}); err == nil {
			t.Fatalf("expected managed Drive location rejection: %s", location)
		}
	}
}

func TestRcloneRemoteSyntaxConversion(t *testing.T) {
	got, err := RcloneURIFromRemote("drive:BKX/restic")
	if err != nil {
		t.Fatal(err)
	}
	if got != "rclone://drive/BKX/restic" {
		t.Fatalf("got=%q", got)
	}
}

func TestStoreRejectsDuplicateAndSupportsDelete(t *testing.T) {
	s := fixedStore(t)
	def := models.RepositoryDefinition{RepositoryID: "one", Name: "One", Engine: "restic", Backend: "local", Location: t.TempDir()}
	if _, err := s.Create(def); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Create(def); err == nil {
		t.Fatal("expected duplicate rejection")
	}
	deleted, err := s.Delete("one")
	if err != nil || !deleted {
		t.Fatalf("deleted=%v err=%v", deleted, err)
	}
	deleted, err = s.Delete("one")
	if err != nil || deleted {
		t.Fatalf("second delete=%v err=%v", deleted, err)
	}
}
