"""Strict BKX configuration layering with per-value provenance.

Precedence is: CLI > environment > config file > defaults.
Core remains framework-free; CLI values are resolved through :meth:`BkxConfig.resolve`.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Mapping

from .errors import ExternalToolError, FrozenDetails, PermissionDeniedError, ValidationError

_UNSET = object()


@dataclass(frozen=True)
class ResolvedConfigValue:
    """A configuration value together with the source that won precedence."""

    value: Any
    source: str


@dataclass(frozen=True)
class BkxConfig:
    """BKX configuration with strict parsing and exact source tracking."""

    default_destination: Path | None = None
    verify_after_operation: bool = True
    compression_algorithm: str = "zstd"
    compression_level: int = 3
    fresh_restore_dir: bool = True
    preferred_archiver: str | None = None
    preferred_compressor: str | None = None
    preferred_hasher: str | None = None
    preferred_transfer: str | None = None
    run_history_enabled: bool = True
    run_history_limit: int = 50
    trace: bool = False

    _config_source: str = field(default="defaults", repr=False, compare=False)
    _config_path: Path | None = field(default=None, repr=False, compare=False)
    _provenance: Mapping[str, str] = field(default_factory=dict, repr=False, compare=False)
    _explicit_fields: frozenset[str] = field(default_factory=frozenset, repr=False, compare=False)

    _ENV_MAP = {
        "BKX_DESTINATION": "default_destination",
        "BKX_VERIFY": "verify_after_operation",
        "BKX_COMPRESSION_ALGORITHM": "compression_algorithm",
        "BKX_COMPRESSION_LEVEL": "compression_level",
        "BKX_FRESH_RESTORE_DIR": "fresh_restore_dir",
        "BKX_PREFERRED_ARCHIVER": "preferred_archiver",
        "BKX_PREFERRED_COMPRESSOR": "preferred_compressor",
        "BKX_PREFERRED_HASHER": "preferred_hasher",
        "BKX_PREFERRED_TRANSFER": "preferred_transfer",
        "BKX_HISTORY_ENABLED": "run_history_enabled",
        "BKX_HISTORY_LIMIT": "run_history_limit",
        "BKX_TRACE": "trace",
    }

    _PUBLIC_FIELDS = frozenset(
        {
            "default_destination",
            "verify_after_operation",
            "compression_algorithm",
            "compression_level",
            "fresh_restore_dir",
            "preferred_archiver",
            "preferred_compressor",
            "preferred_hasher",
            "preferred_transfer",
            "run_history_enabled",
            "run_history_limit",
            "trace",
        }
    )

    _BOOL_FIELDS = frozenset(
        {
            "verify_after_operation",
            "fresh_restore_dir",
            "run_history_enabled",
            "trace",
        }
    )
    _OPTIONAL_STRING_FIELDS = frozenset(
        {"preferred_archiver", "preferred_compressor", "preferred_hasher", "preferred_transfer"}
    )

    def __post_init__(self) -> None:
        if not self._provenance:
            object.__setattr__(
                self,
                "_provenance",
                FrozenDetails({name: "default" for name in self._PUBLIC_FIELDS}),
            )
        elif not isinstance(self._provenance, FrozenDetails):
            object.__setattr__(self, "_provenance", FrozenDetails(dict(self._provenance)))

    @classmethod
    def from_dict(
        cls,
        data: dict[str, Any],
        *,
        base_dir: Path | None = None,
        config_path: Path | None = None,
    ) -> BkxConfig:
        """Create a strictly typed file configuration.

        Unknown keys are rejected so misspellings cannot silently change safety
        behavior. Relative file paths resolve against the config file directory.
        """
        if not isinstance(data, dict):
            raise ValidationError(msg="BKX config root must be a mapping", details={"actual_type": type(data).__name__})

        unknown = sorted(set(data) - cls._PUBLIC_FIELDS)
        if unknown:
            raise ValidationError(msg="Unknown BKX configuration key(s)", details={"keys": unknown})

        values: dict[str, Any] = {}
        for key, value in data.items():
            values[key] = cls._coerce_file_value(key, value, base_dir=base_dir)
        return cls._from_partial(values, source="file", config_path=config_path)

    @classmethod
    def from_env(cls, environ: Mapping[str, str] | None = None) -> BkxConfig:
        """Create an environment layer containing only variables actually set."""
        env = os.environ if environ is None else environ
        values: dict[str, Any] = {}
        for env_name, field_name in cls._ENV_MAP.items():
            if env_name not in env:
                continue
            raw = env[env_name]
            values[field_name] = cls._coerce_env_value(field_name, raw, env_name=env_name)
        source = "env" if values else "defaults"
        return cls._from_partial(values, source=source)

    @classmethod
    def _from_partial(
        cls,
        values: Mapping[str, Any],
        *,
        source: str,
        config_path: Path | None = None,
    ) -> BkxConfig:
        kwargs = dict(values)
        provenance = {name: "default" for name in cls._PUBLIC_FIELDS}
        for name in values:
            provenance[name] = source
        kwargs.update(
            _config_source=source,
            _config_path=config_path,
            _provenance=FrozenDetails(provenance),
            _explicit_fields=frozenset(values),
        )
        return cls(**kwargs)

    @classmethod
    def _coerce_file_value(cls, field_name: str, value: Any, *, base_dir: Path | None) -> Any:
        if field_name == "default_destination":
            if value is None:
                return None
            if not isinstance(value, (str, Path)):
                raise cls._type_error(field_name, "path string or null", value)
            return cls._parse_path(value, base_dir=base_dir)
        if field_name in cls._BOOL_FIELDS:
            if type(value) is not bool:
                raise cls._type_error(field_name, "boolean", value)
            return value
        if field_name in {"compression_level", "run_history_limit"}:
            if type(value) is not int:
                raise cls._type_error(field_name, "integer", value)
            if field_name == "run_history_limit" and value <= 0:
                raise ValidationError(msg="run_history_limit must be greater than zero", details={"value": value})
            return value
        if field_name == "compression_algorithm":
            if not isinstance(value, str) or not value.strip():
                raise cls._type_error(field_name, "non-empty string", value)
            return value.strip()
        if field_name in cls._OPTIONAL_STRING_FIELDS:
            if value is None:
                return None
            if not isinstance(value, str) or not value.strip():
                raise cls._type_error(field_name, "non-empty string or null", value)
            return value.strip()
        raise ValidationError(msg="Unsupported BKX configuration field", details={"field": field_name})

    @classmethod
    def _coerce_env_value(cls, field_name: str, raw: str, *, env_name: str) -> Any:
        if field_name == "default_destination":
            if not raw.strip():
                raise ValidationError(msg=f"{env_name} must not be empty", details={"environment": env_name})
            return cls._parse_path(raw, base_dir=Path.cwd())
        if field_name in cls._BOOL_FIELDS:
            return cls._parse_bool(raw, name=env_name)
        if field_name in {"compression_level", "run_history_limit"}:
            try:
                value = int(raw, 10)
            except ValueError as exc:
                raise ValidationError(
                    msg=f"{env_name} must be an integer",
                    details={"environment": env_name, "value": raw},
                ) from exc
            if field_name == "run_history_limit" and value <= 0:
                raise ValidationError(
                    msg=f"{env_name} must be greater than zero",
                    details={"environment": env_name, "value": raw},
                )
            return value
        if not raw.strip():
            raise ValidationError(msg=f"{env_name} must not be empty", details={"environment": env_name})
        return raw.strip()

    @staticmethod
    def _type_error(field_name: str, expected: str, value: Any) -> ValidationError:
        return ValidationError(
            msg=f"Invalid type for BKX config field '{field_name}'",
            details={"field": field_name, "expected": expected, "actual_type": type(value).__name__},
        )

    @classmethod
    def _parse_path(cls, value: str | Path | None, *, base_dir: Path | None = None) -> Path | None:
        """Normalize a path deterministically without requiring it to exist."""
        if value is None:
            return None
        path = Path(value).expanduser()
        if not path.is_absolute():
            path = (base_dir or Path.cwd()) / path
        return path.resolve(strict=False)

    @classmethod
    def _parse_bool(cls, value: str | None, *, name: str = "value") -> bool:
        """Strictly parse a boolean string; invalid spellings are configuration errors."""
        if value is None:
            return False
        normalized = value.strip().lower()
        if normalized in {"true", "1", "yes", "on"}:
            return True
        if normalized in {"false", "0", "no", "off"}:
            return False
        raise ValidationError(
            msg=f"{name} must be a boolean",
            details={"value": value, "accepted": ["true", "false", "1", "0", "yes", "no", "on", "off"]},
        )

    @classmethod
    def load(cls) -> BkxConfig:
        """Load defaults -> file -> environment, with exact per-field precedence."""
        config = cls()
        file_config = cls._load_from_file()
        if file_config is not None:
            config = cls._merge(config, file_config)
        env_config = cls.from_env()
        if env_config._explicit_fields:
            config = cls._merge(config, env_config)
        return config

    @classmethod
    def _load_from_file(cls) -> BkxConfig | None:
        """Load the first config file from the deterministic search order."""
        search_paths = [Path.cwd() / ".bkx.yaml", Path.cwd() / "bkx.yaml", Path.home() / ".bkx.yaml"]
        for candidate in search_paths:
            config_path = candidate.expanduser().resolve(strict=False)
            if not config_path.exists():
                continue
            try:
                import yaml
            except ImportError as exc:
                raise ValidationError(
                    msg="PyYAML is required to read BKX configuration files",
                    details={"path": str(config_path), "install": "install bkx base dependencies"},
                ) from exc
            try:
                with config_path.open(encoding="utf-8") as f:
                    data = yaml.safe_load(f)
            except PermissionError as exc:
                raise PermissionDeniedError(
                    msg=f"Permission denied reading BKX configuration: {config_path}",
                    details={"path": str(config_path), "errno": exc.errno},
                ) from exc
            except OSError as exc:
                raise ExternalToolError(
                    msg=f"System error reading BKX configuration: {config_path}",
                    tool_name="filesystem",
                    details={"path": str(config_path), "errno": exc.errno, "reason": str(exc)},
                ) from exc
            except Exception as exc:
                raise ValidationError(
                    msg=f"Failed to parse BKX configuration: {config_path}",
                    details={"path": str(config_path), "reason": str(exc)},
                ) from exc
            if data is None:
                data = {}
            if not isinstance(data, dict):
                raise ValidationError(
                    msg="BKX config root must be a mapping",
                    details={"path": str(config_path), "actual_type": type(data).__name__},
                )
            return cls.from_dict(data, base_dir=config_path.parent, config_path=config_path)
        return None

    @classmethod
    def _merge(cls, base: BkxConfig, override: BkxConfig) -> BkxConfig:
        """Merge exactly the fields explicitly present in ``override``."""
        values = {name: getattr(base, name) for name in cls._PUBLIC_FIELDS}
        provenance = dict(base._provenance)
        for name in override._explicit_fields:
            values[name] = getattr(override, name)
            provenance[name] = override._provenance[name]

        sources = []
        if base._config_source != "defaults":
            sources.extend(base._config_source.split("+"))
        if override._config_source != "defaults" and override._config_source not in sources:
            sources.append(override._config_source)
        source = "+".join(sources) if sources else "defaults"
        config_path = override._config_path or base._config_path
        return cls(
            **values,
            _config_source=source,
            _config_path=config_path,
            _provenance=FrozenDetails(provenance),
            _explicit_fields=base._explicit_fields | override._explicit_fields,
        )

    def source_of(self, field_name: str) -> str:
        """Return ``default``, ``file`` or ``env`` for an effective config field."""
        if field_name not in self._PUBLIC_FIELDS:
            raise KeyError(field_name)
        return self._provenance[field_name]

    def resolve(self, field_name: str, *, cli_value: Any = _UNSET) -> ResolvedConfigValue:
        """Resolve a field including a possible CLI value and report provenance."""
        if field_name not in self._PUBLIC_FIELDS:
            raise KeyError(field_name)
        if cli_value is not _UNSET:
            return ResolvedConfigValue(cli_value, "cli")
        return ResolvedConfigValue(getattr(self, field_name), self.source_of(field_name))

    def get_compression_profile(self) -> dict[str, Any]:
        return {"algorithm": self.compression_algorithm, "level": self.compression_level}

    def get_tool_preferences(self) -> dict[str, str | None]:
        return {
            "archiver": self.preferred_archiver,
            "compressor": self.preferred_compressor,
            "compression": self.preferred_compressor,
            "hasher": self.preferred_hasher,
            "hashing": self.preferred_hasher,
            "transfer": self.preferred_transfer,
        }


_config: BkxConfig | None = None


def get_config() -> BkxConfig:
    global _config
    if _config is None:
        _config = BkxConfig.load()
    return _config


def reload_config() -> BkxConfig:
    global _config
    _config = BkxConfig.load()
    return _config


def reset_config() -> None:
    global _config
    _config = None
