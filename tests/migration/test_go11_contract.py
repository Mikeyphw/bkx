from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[2]


def test_go11_bridge_sources_and_migration_boundary():
    for rel in (
        "internal/bridge/bridge.go",
        "internal/bridge/bridge_test.go",
        "tools/go_migration/verify_go11_bridge_parity.py",
        "scripts/validate-go11.sh",
    ):
        assert (ROOT / rel).is_file(), rel
    main = (ROOT / "cmd/bkx/main.go").read_text()
    assert 'args[1] == "bridge"' in main
    assert 'case "readiness":' in main
    assert 'case "execute":' in main
    assert 'case "session":' in main
    assert "Go migration scaffold is not the production backend yet" in main


def test_go11_bridge_owns_transport_only_and_delegates_domain_logic():
    source = (ROOT / "internal/bridge/bridge.go").read_text()
    assert '"bkx/internal/services"' in source
    for forbidden in ('"bkx/internal/archiveio"', '"bkx/internal/backupops"', '"bkx/internal/transferops"', '"os/exec"'):
        assert forbidden not in source
    assert ".services.Dispatch(" in source
    assert '"go-native-migration"' in source
    assert '"run.cancel"' in source


def test_go11_registry_matches_frozen_handler_and_advertised_contracts():
    frozen = json.loads((ROOT / "migration/contracts/v1/bridge.json").read_text())
    assert len(frozen["advertisedOperations"]) == 15
    assert len(frozen["handlers"]) == 16
    assert "run.cancel" not in frozen["advertisedOperations"]
    assert "run.cancel" in frozen["handlers"]
    tests = (ROOT / "internal/bridge/bridge_test.go").read_text()
    for name in (
        "TestFrozenBridgeRegistryAndReadiness",
        "TestExecuteSuccessEnvelopeMetricsAndProgressSequence",
        "TestMalformedRequestsFailClosedWithProtocolEnvelope",
        "TestUnknownOperationAndTypedNotFoundMapping",
        "TestCancelEndpointAndQueuedCancellationParity",
        "TestRunCancelHandlerCancelsAnotherRequestWithoutAdvertisingIt",
        "TestProgressSequenceContinuesWhenRequestIDIsReused",
    ):
        assert name in tests


def test_go11_parity_covers_envelopes_errors_progress_and_cancel():
    text = (ROOT / "tools/go_migration/verify_go11_bridge_parity.py").read_text()
    for token in (
        '"filesystem.stat"',
        '"shell.exec"',
        '"run.cancel"',
        '"storage.locations"',
        '"progress"',
        '"cancel"',
        'assert_error_shape_parity',
        '"code"',
        '"go-native-migration"',
        '"embedded-cpython"',
    ):
        assert token in text


def test_go11_validator_chains_go10_and_android_arch_gate():
    text = (ROOT / "scripts/validate-go11.sh").read_text()
    assert "validate-go10.sh" in text
    assert "verify_go11_bridge_parity.py" in text
    assert "go version -m" in text
    assert "GOOS=android" in text
    assert "GOARCH=arm64" in text
    assert "machine != 183" in text
    assert "file -b" not in text
