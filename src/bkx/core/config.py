"""Configuration layering system.

Implements config layering: flags > env > config file > defaults

Contract rule:
- Core must be framework-free, no CLI imports
- Configuration is pure Python data structures
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
import os


@dataclass(frozen=True)
class BkxConfig:
    """BKX configuration with layered sources.
    
    Precedence (highest to lowest):
    1. CLI flags (passed directly)
    2. Environment variables
    3. Config file (~/.bkx.yaml, .bkx.yaml, bkx.yaml)
    4. Defaults
    """
    
    # Core settings
    default_destination: Path | None = None
    archives_dir: Path | None = None
    verify_after_operation: bool = True
    preserve_permissions: bool = True
    
    # Compression defaults
    compression_algorithm: str = "zstd"
    compression_level: int = 3
    
    # Safety defaults
    fresh_restore_dir: bool = True
    confirm_overwrite: bool = True
    require_confirmation: bool = False
    
    # Tool preferences (will be used by ToolDetectorPort)
    preferred_archiver: str | None = None
    preferred_compressor: str | None = None
    preferred_hasher: str | None = None
    preferred_transfer: str | None = None
    
    # Run history settings
    run_history_enabled: bool = True
    run_history_limit: int = 50
    
    # Logging
    verbose: bool = False
    trace: bool = False
    
    # Raw config source for debugging
    _config_source: str = "defaults"
    _config_path: Path | None = None
    
    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> BkxConfig:
        """Create config from dictionary (e.g., config file)."""
        return cls(
            default_destination=cls._parse_path(data.get("default_destination")),
            archives_dir=cls._parse_path(data.get("archives_dir")),
            verify_after_operation=data.get("verify_after_operation", True),
            preserve_permissions=data.get("preserve_permissions", True),
            compression_algorithm=data.get("compression_algorithm", "zstd"),
            compression_level=data.get("compression_level", 3),
            fresh_restore_dir=data.get("fresh_restore_dir", True),
            confirm_overwrite=data.get("confirm_overwrite", True),
            require_confirmation=data.get("require_confirmation", False),
            preferred_archiver=data.get("preferred_archiver"),
            preferred_compressor=data.get("preferred_compressor"),
            preferred_hasher=data.get("preferred_hasher"),
            preferred_transfer=data.get("preferred_transfer"),
            run_history_enabled=data.get("run_history_enabled", True),
            run_history_limit=data.get("run_history_limit", 50),
            verbose=data.get("verbose", False),
            trace=data.get("trace", False),
            _config_source="file",
        )
    
    @classmethod
    def from_env(cls) -> BkxConfig:
        """Create config from environment variables."""
        return cls(
            default_destination=cls._parse_path(os.environ.get("BKX_DESTINATION")),
            archives_dir=cls._parse_path(os.environ.get("BKX_ARCHIVES_DIR")),
            verify_after_operation=cls._parse_bool(os.environ.get("BKX_VERIFY", "true")),
            preserve_permissions=cls._parse_bool(os.environ.get("BKX_PRESERVE_PERMISSIONS", "true")),
            compression_algorithm=os.environ.get("BKX_COMPRESSION_ALGORITHM", "zstd"),
            compression_level=int(os.environ.get("BKX_COMPRESSION_LEVEL", "3")),
            fresh_restore_dir=cls._parse_bool(os.environ.get("BKX_FRESH_RESTORE_DIR", "true")),
            confirm_overwrite=cls._parse_bool(os.environ.get("BKX_CONFIRM_OVERWRITE", "true")),
            require_confirmation=cls._parse_bool(os.environ.get("BKX_REQUIRE_CONFIRMATION", "false")),
            preferred_archiver=os.environ.get("BKX_PREFERRED_ARCHIVER"),
            preferred_compressor=os.environ.get("BKX_PREFERRED_COMPRESSOR"),
            preferred_hasher=os.environ.get("BKX_PREFERRED_HASHER"),
            preferred_transfer=os.environ.get("BKX_PREFERRED_TRANSFER"),
            run_history_enabled=cls._parse_bool(os.environ.get("BKX_HISTORY_ENABLED", "true")),
            run_history_limit=int(os.environ.get("BKX_HISTORY_LIMIT", "50")),
            verbose=cls._parse_bool(os.environ.get("BKX_VERBOSE", "false")),
            trace=cls._parse_bool(os.environ.get("BKX_TRACE", "false")),
            _config_source="env",
        )
    
    @classmethod
    def _parse_path(cls, value: str | None) -> Path | None:
        """Parse path from string."""
        if value is None:
            return None
        return Path(value)
    
    @classmethod
    def _parse_bool(cls, value: str | None) -> bool:
        """Parse boolean from string."""
        if value is None:
            return False
        return value.lower() in ("true", "1", "yes")
    
    @classmethod
    def load(cls) -> BkxConfig:
        """Load config with proper layering.
        
        Order: defaults -> env -> config file
        Environment overrides defaults, config file overrides env
        """
        # Start with defaults
        config = cls()
        
        # Try to load from config file
        file_config = cls._load_from_file()
        if file_config:
            config = file_config
        
        # Environment overrides config file
        env_config = cls.from_env()
        if env_config._config_source == "env":
            config = cls._merge(config, env_config)
        
        return config
    
    @classmethod
    def _load_from_file(cls) -> BkxConfig | None:
        """Load config from standard config file locations."""
        # Search order: .bkx.yaml, bkx.yaml, ~/.bkx.yaml
        search_paths = [
            Path(".bkx.yaml"),
            Path("bkx.yaml"),
            Path.home() / ".bkx.yaml",
        ]
        
        for config_path in search_paths:
            if config_path.exists():
                try:
                    import yaml
                    with open(config_path) as f:
                        data = yaml.safe_load(f) or {}
                    
                    config = cls.from_dict(data)
                    object.__setattr__(config, "_config_path", config_path)
                    return config
                except ImportError:
                    # YAML not available, skip config file
                    pass
                except Exception:
                    # Parse error, skip
                    pass
        
        return None
    
    @classmethod
    def _merge(cls, base: BkxConfig, override: BkxConfig) -> BkxConfig:
        """Merge two configs, with override taking precedence."""
        # Create a dict from base and override, checking for non-default values
        def to_dict(config: BkxConfig) -> dict[str, Any]:
            return {
                "default_destination": config.default_destination,
                "archives_dir": config.archives_dir,
                "verify_after_operation": config.verify_after_operation,
                "preserve_permissions": config.preserve_permissions,
                "compression_algorithm": config.compression_algorithm,
                "compression_level": config.compression_level,
                "fresh_restore_dir": config.fresh_restore_dir,
                "confirm_overwrite": config.confirm_overwrite,
                "require_confirmation": config.require_confirmation,
                "preferred_archiver": config.preferred_archiver,
                "preferred_compressor": config.preferred_compressor,
                "preferred_hasher": config.preferred_hasher,
                "preferred_transfer": config.preferred_transfer,
                "run_history_enabled": config.run_history_enabled,
                "run_history_limit": config.run_history_limit,
                "verbose": config.verbose,
                "trace": config.trace,
            }
        
        base_dict = to_dict(base)
        override_dict = to_dict(override)
        
        # Merge: override non-None/non-default values
        merged: dict[str, Any] = {}
        for key in base_dict:
            base_val = base_dict[key]
            override_val = override_dict.get(key)
            
            # Check if override is different from default
            default_val = to_dict(cls())[key]
            if override_val != default_val and override_val is not None:
                merged[key] = override_val
            else:
                merged[key] = base_val
        
        # Get config source from merged values
        merged_source = base._config_source
        if override._config_source != "defaults":
            merged_source = f"{base._config_source}+{override._config_source}"
        
        # Create result and set config_source using object.__setattr__ for frozen
        result = cls(**merged)
        object.__setattr__(result, "_config_source", merged_source)
        return result
    
    def get_compression_profile(self) -> dict[str, Any]:
        """Get compression profile for specs."""
        return {
            "algorithm": self.compression_algorithm,
            "level": self.compression_level,
        }
    
    def get_tool_preferences(self) -> dict[str, str | None]:
        """Get tool preferences for ToolDetectorPort."""
        return {
            "archiver": self.preferred_archiver,
            "compressor": self.preferred_compressor,
            "hasher": self.preferred_hasher,
            "transfer": self.preferred_transfer,
        }


# Singleton config instance (lazily loaded)
_config: BkxConfig | None = None


def get_config() -> BkxConfig:
    """Get the global BKX configuration."""
    global _config
    if _config is None:
        _config = BkxConfig.load()
    return _config


def reload_config() -> BkxConfig:
    """Force reload of configuration."""
    global _config
    _config = BkxConfig.load()
    return _config


def reset_config() -> None:
    """Reset configuration to None (will reload on next get_config())."""
    global _config
    _config = None

