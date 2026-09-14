package archiveio

import (
	"archive/zip"
	"bytes"
	"encoding/binary"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"testing"

	"bkx/internal/models"
)

func TestZstdNativeFramesRoundTripWithoutExternalTools(t *testing.T) {
	for _, level := range []int{1, 3, 10, 22} {
		for _, name := range []string{"archive.tar.zst", "archive.zip"} {
			t.Run(fmt.Sprintf("%s-level-%d", name, level), func(t *testing.T) {
				source := fixtureTree(t)
				payload := bytes.Repeat([]byte("BKX native zstd payload\x00\xff\x01\n"), 4096)
				if err := os.WriteFile(filepath.Join(source, "payload.bin"), payload, 0600); err != nil {
					t.Fatal(err)
				}
				if err := os.WriteFile(filepath.Join(source, "empty.bin"), nil, 0600); err != nil {
					t.Fatal(err)
				}
				out := filepath.Join(t.TempDir(), name)
				t.Setenv("PATH", "")
				result, err := Create(createSpec(source, out, "zstd", level), false, nil)
				if err != nil || !result.Success || len(result.Warnings) != 0 {
					t.Fatalf("create=%+v err=%v", result, err)
				}
				raw, err := os.ReadFile(out)
				if err != nil {
					t.Fatal(err)
				}
				if len(raw) >= len(payload)/2 {
					t.Fatalf("not real compression: archive=%d payload=%d", len(raw), len(payload))
				}
				if filepath.Ext(out) != ".zip" && !bytes.HasPrefix(raw, []byte{0x28, 0xb5, 0x2f, 0xfd}) {
					t.Fatal("missing zstd frame magic")
				}
				if filepath.Ext(out) == ".zip" {
					zr, err := zip.OpenReader(out)
					if err != nil {
						t.Fatal(err)
					}
					defer zr.Close()
					for _, f := range zr.File {
						if !f.FileInfo().IsDir() && f.Method != zipMethodZstd {
							t.Fatalf("entry %s uses method %d", f.Name, f.Method)
						}
					}
				}
				destination := filepath.Join(t.TempDir(), "restored")
				restored, err := ExtractRestore(models.RestoreSpec{Archive: out, Destination: &destination, Verify: true, FreshDir: true}, nil)
				if err != nil || !restored.Success {
					t.Fatalf("restore=%+v err=%v", restored, err)
				}
				got, err := os.ReadFile(filepath.Join(restored.DestinationPath, "source", "payload.bin"))
				if err != nil {
					t.Fatal(err)
				}
				if !bytes.Equal(got, payload) {
					t.Fatal("binary content changed during zstd restore")
				}
				empty, err := os.ReadFile(filepath.Join(restored.DestinationPath, "source", "empty.bin"))
				if err != nil || len(empty) != 0 {
					t.Fatalf("empty file restore: %v", err)
				}
			})
		}
	}
}

func TestZstdInteroperabilityWithSystemCodec(t *testing.T) {
	codec, err := exec.LookPath("zstd")
	if err != nil {
		t.Skip("independent zstd command is unavailable; native codec tests still run")
	}
	source := fixtureTree(t)
	if err := os.WriteFile(filepath.Join(source, "empty.bin"), nil, 0600); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"archive.tar.zst", "archive.zip"} {
		out := filepath.Join(t.TempDir(), name)
		if _, err := Create(createSpec(source, out, "zstd", 3), false, nil); err != nil {
			t.Fatal(err)
		}
		if filepath.Ext(out) == ".zip" {
			zr, err := zip.OpenReader(out)
			if err != nil {
				t.Fatal(err)
			}
			for _, f := range zr.File {
				if f.FileInfo().IsDir() {
					continue
				}
				raw, err := f.OpenRaw()
				if err != nil {
					t.Fatal(err)
				}
				cmd := exec.Command(codec, "-q", "-d", "-c")
				cmd.Stdin = raw
				decoded, err := cmd.Output()
				if err != nil {
					t.Fatalf("external decode %s: %v", f.Name, err)
				}
				want, err := os.ReadFile(filepath.Join(filepath.Dir(source), filepath.FromSlash(f.Name)))
				if err != nil {
					t.Fatal(err)
				}
				if !bytes.Equal(decoded, want) {
					t.Fatalf("external decoder content mismatch: %s", f.Name)
				}
			}
			if err := zr.Close(); err != nil {
				t.Fatal(err)
			}
		} else if result, err := exec.Command(codec, "-q", "-t", out).CombinedOutput(); err != nil {
			t.Fatalf("system zstd rejects native tar: %v %s", err, result)
		}
	}
	// Read a frame produced by a separate implementation of zstd.
	tarPath := filepath.Join(t.TempDir(), "external.tar")
	if _, err := Create(createSpec(source, tarPath, "none", 0), false, nil); err != nil {
		t.Fatal(err)
	}
	compressed := tarPath + ".zst"
	if result, err := exec.Command(codec, "-q", "-f", tarPath, "-o", compressed).CombinedOutput(); err != nil {
		t.Fatalf("external compress: %v %s", err, result)
	}
	verified, err := Verify(compressed, nil)
	if err != nil || !verified.Success {
		t.Fatalf("external zstd verify=%+v err=%v", verified, err)
	}
	dest := filepath.Join(t.TempDir(), "restore")
	restored, err := ExtractRestore(models.RestoreSpec{Archive: compressed, Destination: &dest, Verify: true, FreshDir: true}, nil)
	if err != nil || !restored.Success {
		t.Fatalf("external zstd restore=%+v err=%v", restored, err)
	}
}

func TestZstdZipRejectsWrongCentralDirectoryCRCAndSize(t *testing.T) {
	for _, offset := range []int{16, 24} { // CRC32 and uncompressed size in the central-directory header.
		t.Run(fmt.Sprint(offset), func(t *testing.T) {
			out := filepath.Join(t.TempDir(), "corrupt.zip")
			if _, err := Create(createSpec(fixtureTree(t), out, "zstd", 3), false, nil); err != nil {
				t.Fatal(err)
			}
			data, err := os.ReadFile(out)
			if err != nil {
				t.Fatal(err)
			}
			index := 0
			changed := false
			for {
				pos := bytes.Index(data[index:], []byte{'P', 'K', 1, 2})
				if pos < 0 {
					break
				}
				pos += index
				if binary.LittleEndian.Uint16(data[pos+10:pos+12]) == zipMethodZstd {
					data[pos+offset] ^= 1
					changed = true
					break
				}
				index = pos + 46
			}
			if !changed {
				t.Fatal("zstd ZIP entry missing")
			}
			if err := os.WriteFile(out, data, 0600); err != nil {
				t.Fatal(err)
			}
			verified, err := Verify(out, nil)
			if err == nil && verified.Success {
				t.Fatal("corrupt zstd ZIP metadata accepted")
			}
			dest := filepath.Join(t.TempDir(), "restore")
			if result, err := ExtractRestore(models.RestoreSpec{Archive: out, Destination: &dest, Verify: false, FreshDir: true}, nil); err == nil && result.Success {
				t.Fatal("corrupt zstd ZIP published during restore")
			}
			// FreshDir uses dest as the parent for a generated restore directory, so
			// the parent itself may legitimately be created before extraction. A
			// failed restore must leave that parent empty: neither the generated
			// destination nor the temporary .bkx-restore staging directory may
			// survive the error path.
			entries, readErr := os.ReadDir(dest)
			if readErr != nil && !os.IsNotExist(readErr) {
				t.Fatalf("inspect failed restore destination: %v", readErr)
			}
			if len(entries) != 0 {
				t.Fatalf("failed zstd restore published or leaked staging entries: %v", entries)
			}
		})
	}
}

func TestZstdTarRejectsDamagedFrameFooter(t *testing.T) {
	for _, damage := range []string{"truncate", "checksum"} {
		t.Run(damage, func(t *testing.T) {
			out := filepath.Join(t.TempDir(), "corrupt.tar.zst")
			if _, err := Create(createSpec(fixtureTree(t), out, "zstd", 3), false, nil); err != nil {
				t.Fatal(err)
			}
			data, err := os.ReadFile(out)
			if err != nil {
				t.Fatal(err)
			}
			if damage == "truncate" {
				data = data[:len(data)-1]
			} else {
				data[len(data)-1] ^= 0xff
			}
			if err := os.WriteFile(out, data, 0600); err != nil {
				t.Fatal(err)
			}
			verified, err := Verify(out, nil)
			if err == nil && verified.Success {
				t.Fatal("damaged zstd frame accepted")
			}
			dest := filepath.Join(t.TempDir(), "restore")
			if result, err := ExtractRestore(models.RestoreSpec{Archive: out, Destination: &dest, Verify: false, FreshDir: true}, nil); err == nil && result.Success {
				t.Fatal("damaged zstd frame restored")
			}
			if _, err := os.Stat(dest); !os.IsNotExist(err) {
				t.Fatal("damaged frame must not publish restored files")
			}
		})
	}
}

func TestZstdZipReadsHistoricalMethod20(t *testing.T) {
	out := filepath.Join(t.TempDir(), "legacy.zip")
	if _, err := Create(createSpec(fixtureTree(t), out, "zstd", 3), false, nil); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(out)
	if err != nil {
		t.Fatal(err)
	}
	for _, header := range []struct {
		magic  []byte
		offset int
	}{{[]byte{'P', 'K', 3, 4}, 8}, {[]byte{'P', 'K', 1, 2}, 10}} {
		start := 0
		for {
			i := bytes.Index(data[start:], header.magic)
			if i < 0 {
				break
			}
			i += start
			if binary.LittleEndian.Uint16(data[i+header.offset:]) == zipMethodZstd {
				binary.LittleEndian.PutUint16(data[i+header.offset:], zipMethodZstdLegacy)
			}
			start = i + 4
		}
	}
	if err := os.WriteFile(out, data, 0600); err != nil {
		t.Fatal(err)
	}
	verified, err := Verify(out, nil)
	if err != nil || !verified.Success {
		t.Fatalf("method 20 verify=%+v err=%v", verified, err)
	}
	zr, err := openZipArchive(out)
	if err != nil {
		t.Fatal(err)
	}
	defer zr.Close()
	for _, f := range zr.File {
		if f.FileInfo().IsDir() {
			continue
		}
		r, err := openZipPayload(f)
		if err != nil {
			t.Fatal(err)
		}
		_, err = io.Copy(io.Discard, r)
		r.Close()
		if err != nil {
			t.Fatal(err)
		}
	}
}
