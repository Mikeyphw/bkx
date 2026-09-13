package models

import "testing"

func TestCredentialRefPolicyIsCollisionFree(t *testing.T) {
	for _, ref := range []string{"phone-repo", "repo1", "a-b-c"} {
		key, err := CredentialEnvironmentKey(ref)
		if err != nil {
			t.Fatalf("%s rejected: %v", ref, err)
		}
		if key == "" || key == "BKX_CREDENTIAL_" {
			t.Fatalf("empty key for %s: %q", ref, key)
		}
	}
	for _, ref := range []string{"Phone-Repo", "phone_repo", "phone.repo", "phone/repo", " phone-repo "} {
		if _, err := CredentialEnvironmentKey(ref); err == nil {
			t.Fatalf("expected collision-prone credential ref rejection for %q", ref)
		}
	}
}
