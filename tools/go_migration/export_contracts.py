#!/usr/bin/env python3
"""Export the deterministic GO00 compatibility baseline from the Python BKX backend."""
from __future__ import annotations

import argparse
import dataclasses
import hashlib
import json
import sys
import tomllib
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "src"
if str(SRC) not in sys.path:
    sys.path.insert(0, str(SRC))

from typer.main import get_command  # noqa: E402

from bkx.android_api import engine as android_engine  # noqa: E402
from bkx.cli.app import app as typer_app  # noqa: E402
from bkx.cli import exit_codes  # noqa: E402
from bkx.core.config import BkxConfig  # noqa: E402
from bkx.core.errors import (  # noqa: E402
    BkxError,
    ExternalToolError,
    NotFoundError,
    OptionalFeatureError,
    PermissionDeniedError,
    ValidationError,
)
from bkx.core.impl import run_store  # noqa: E402

CONTRACT_SCHEMA_VERSION = 1
CONTRACT_VERSION = 1
OUT = ROOT / "migration" / "contracts" / "v1"
SPEC_NAMES = ("backup_spec", "archive_spec", "restore_spec", "transfer_spec")


def _project_version() -> str:
    data = tomllib.loads((ROOT / "pyproject.toml").read_text(encoding="utf-8"))
    return str(data["project"]["version"])


def _safe(value: Any) -> Any:
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, (set, frozenset, tuple)):
        return [_safe(item) for item in sorted(value, key=lambda item: str(item))]
    if isinstance(value, list):
        return [_safe(item) for item in value]
    if isinstance(value, dict):
        return {str(key): _safe(item) for key, item in sorted(value.items(), key=lambda item: str(item[0]))}
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    return str(value)


def _parameter_contract(param: Any) -> dict[str, Any]:
    return {
        "name": param.name,
        "kind": "option" if hasattr(param, "is_flag") else "argument",
        "optionStrings": list(getattr(param, "opts", ())),
        "secondaryOptionStrings": list(getattr(param, "secondary_opts", ())),
        "required": bool(param.required),
        "nargs": int(param.nargs),
        "multiple": bool(param.multiple),
        "default": _safe(param.default),
        "type": str(getattr(param.type, "name", param.type)),
        "isFlag": bool(getattr(param, "is_flag", False)),
        "help": _safe(getattr(param, "help", None)),
        "metavar": _safe(getattr(param, "metavar", None)),
    }


def _walk_click(command: Any, path: tuple[str, ...] = ()) -> list[dict[str, Any]]:
    nodes: list[dict[str, Any]] = []
    for name in sorted(getattr(command, "commands", {})):
        child = command.commands[name]
        child_path = (*path, name)
        nodes.append(
            {
                "path": list(child_path),
                "command": " ".join(child_path),
                "help": _safe(getattr(child, "help", None)),
                "parameters": [_parameter_contract(param) for param in child.params],
                "isGroup": bool(getattr(child, "commands", None)),
            }
        )
        nodes.extend(_walk_click(child, child_path))
    return nodes


def cli_contract() -> dict[str, Any]:
    command = get_command(typer_app)
    nodes = _walk_click(command)
    leaves = [node for node in nodes if not node["isGroup"]]
    return {
        "contractSchemaVersion": CONTRACT_SCHEMA_VERSION,
        "contractVersion": CONTRACT_VERSION,
        "program": "bkx",
        "pythonBackendVersion": _project_version(),
        "rootParameters": [_parameter_contract(param) for param in command.params],
        "commandNodeCount": len(nodes),
        "leafCommandCount": len(leaves),
        "commandNodes": nodes,
    }


def bridge_contract() -> dict[str, Any]:
    handlers = sorted(android_engine._HANDLERS)  # migration-only freeze of current dispatch table
    advertised = list(android_engine.supported_operations())
    capabilities = sorted(android_engine._CAPABILITIES)
    return {
        "contractSchemaVersion": CONTRACT_SCHEMA_VERSION,
        "contractVersion": CONTRACT_VERSION,
        "protocolVersion": android_engine.PROTOCOL_VERSION,
        "runtime": android_engine._RUNTIME,
        "entrypoints": ["readiness", "execute", "progress", "cancel"],
        "requestEnvelope": {
            "required": ["protocol_version", "request_id", "operation"],
            "optional": ["payload"],
            "requestIdAlphabet": "A-Za-z0-9._-",
            "payloadType": "object",
        },
        "resultEnvelope": {
            "required": ["protocol_version", "request_id", "status", "summary", "errors", "artifacts", "metrics"],
            "statuses": ["cancelled", "failure", "success"],
        },
        "progressEnvelope": {
            "required": ["protocol_version", "request_id", "seq", "phase", "message"],
            "optional": ["progress"],
            "progressRange": [0.0, 1.0],
        },
        "handlerCount": len(handlers),
        "handlers": handlers,
        "advertisedOperationCount": len(advertised),
        "advertisedOperations": advertised,
        "capabilityCount": len(capabilities),
        "capabilities": capabilities,
        "arbitraryShell": False,
    }


def _field_contract(field: dataclasses.Field[Any]) -> dict[str, Any]:
    if field.default is not dataclasses.MISSING:
        default_kind = "value"
        default = _safe(field.default)
    elif field.default_factory is not dataclasses.MISSING:  # type: ignore[comparison-overlap]
        default_kind = "factory"
        default = getattr(field.default_factory, "__name__", str(field.default_factory))
    else:
        default_kind = "required"
        default = None
    return {"name": field.name, "type": str(field.type), "defaultKind": default_kind, "default": default}


def core_contract() -> dict[str, Any]:
    error_types = [
        BkxError,
        ValidationError,
        NotFoundError,
        PermissionDeniedError,
        ExternalToolError,
        OptionalFeatureError,
    ]
    exit_map = {
        "BkxError": exit_codes.EXIT_GENERIC,
        "ValidationError": exit_codes.EXIT_USAGE,
        "NotFoundError": exit_codes.EXIT_NOT_FOUND,
        "PermissionDeniedError": exit_codes.EXIT_PERMISSION,
        "ExternalToolError": exit_codes.EXIT_EXTERNAL,
        "OptionalFeatureError": exit_codes.EXIT_EXTERNAL,
    }
    defaults = BkxConfig()
    config_fields = []
    for field in dataclasses.fields(BkxConfig):
        if field.name.startswith("_"):
            continue
        item = _field_contract(field)
        item["effectiveDefault"] = _safe(getattr(defaults, field.name))
        config_fields.append(item)
    run_record_fields = [_field_contract(field) for field in dataclasses.fields(run_store._RunRecord)]
    return {
        "contractSchemaVersion": CONTRACT_SCHEMA_VERSION,
        "contractVersion": CONTRACT_VERSION,
        "config": {
            "precedence": ["cli", "environment", "config_file", "defaults"],
            "environment": dict(sorted(BkxConfig._ENV_MAP.items())),
            "searchOrder": ["./.bkx.yaml", "./bkx.yaml", "~/.bkx.yaml"],
            "fields": config_fields,
        },
        "errors": {
            "types": [item.__name__ for item in error_types],
            "exitCodes": exit_map,
            "stableCodes": {
                "success": exit_codes.EXIT_OK,
                "generic": exit_codes.EXIT_GENERIC,
                "usage": exit_codes.EXIT_USAGE,
                "notFound": exit_codes.EXIT_NOT_FOUND,
                "permission": exit_codes.EXIT_PERMISSION,
                "external": exit_codes.EXIT_EXTERNAL,
            },
        },
        "runHistory": {
            "defaultRelativePath": ".local/share/bkx/runs/runs.jsonl",
            "recordFields": run_record_fields,
            "validStatuses": sorted(run_store._VALID_STATUSES),
            "validOperations": sorted(run_store._VALID_OPERATIONS),
            "encoding": "utf-8 JSONL; canonical keys sorted; one record per line",
            "lockSuffix": ".lock",
            "quarantineSuffix": ".corrupt",
        },
        "specSchemas": [f"spec_schemas/{name}.json" for name in SPEC_NAMES],
    }


def release_contract() -> dict[str, Any]:
    build = (ROOT / "android" / "BKX.Android" / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    application_id = "com.mikeyphw.bkx.android" if 'applicationId = "com.mikeyphw.bkx.android"' in build else "unknown"
    return {
        "contractSchemaVersion": CONTRACT_SCHEMA_VERSION,
        "contractVersion": CONTRACT_VERSION,
        "productVersion": _project_version(),
        "productionBackend": {"language": "python", "runtime": android_engine._RUNTIME},
        "android": {
            "applicationId": application_id,
            "bridgeProtocolVersion": android_engine.PROTOCOL_VERSION,
            "versionSource": "pyproject.toml [project].version",
        },
        "cutoverRules": {
            "pythonRemainsProductionUntilParity": True,
            "androidUiRemainsKotlinCompose": True,
            "protocolV1RemainsStable": True,
            "noFeatureRedesignDuringBackendMigration": True,
        },
    }


def _encoded(value: Any) -> bytes:
    return (json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False) + "\n").encode("utf-8")


def _schema_payload(name: str) -> bytes:
    source = ROOT / "src" / "bkx" / "core" / "schemas" / f"{name}.json"
    return _encoded(json.loads(source.read_text(encoding="utf-8")))


def generated_files() -> dict[str, bytes]:
    files: dict[str, bytes] = {
        "cli.json": _encoded(cli_contract()),
        "bridge.json": _encoded(bridge_contract()),
        "core.json": _encoded(core_contract()),
        "release.json": _encoded(release_contract()),
    }
    for name in SPEC_NAMES:
        files[f"spec_schemas/{name}.json"] = _schema_payload(name)
    manifest = {
        "contractSchemaVersion": CONTRACT_SCHEMA_VERSION,
        "contractVersion": CONTRACT_VERSION,
        "product": "BKX",
        "pythonBackendVersion": _project_version(),
        "files": [
            {"path": path, "sha256": hashlib.sha256(payload).hexdigest(), "size": len(payload)}
            for path, payload in sorted(files.items())
        ],
    }
    files["manifest.json"] = _encoded(manifest)
    return files


def write() -> None:
    for rel, payload in generated_files().items():
        path = OUT / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(payload)


def check() -> list[str]:
    problems: list[str] = []
    expected = generated_files()
    for rel, payload in expected.items():
        path = OUT / rel
        if not path.is_file():
            problems.append(f"missing {path.relative_to(ROOT)}")
            continue
        actual = path.read_bytes()
        if actual != payload:
            problems.append(
                f"stale {path.relative_to(ROOT)} expected={hashlib.sha256(payload).hexdigest()[:12]} actual={hashlib.sha256(actual).hexdigest()[:12]}"
            )
    expected_paths = set(expected)
    if OUT.is_dir():
        actual_paths = {str(path.relative_to(OUT)) for path in OUT.rglob("*.json")}
        for extra in sorted(actual_paths - expected_paths):
            problems.append(f"unexpected {OUT.relative_to(ROOT)}/{extra}")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Fail if committed GO00 fixtures drift from Python.")
    args = parser.parse_args()
    if args.check:
        problems = check()
        if problems:
            print("\n".join(problems), file=sys.stderr)
            return 1
        print("BKX GO00 contracts are current.")
        return 0
    write()
    print(f"Wrote {len(generated_files())} GO00 contract files under {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
