"""Tests for config layering system.

Tests config loading from defaults, env vars, and config files.
"""

from __future__ import annotations

import pytest
from pathlib import Path
import tempfile
import os

from bkx.core.config import BkxConfig, get_config, reset_config, reload_config


class TestBkxConfigDefaults:
    """Tests for default configuration values."""

    def test_default_values(self):
        """Test that default values are set correctly."""
        config = BkxConfig()

        assert config.verify_after_operation is True
        assert config.preserve_permissions is True
        assert config.compression_algorithm == "zstd"
        assert config.compression_level == 3
        assert config.fresh_restore_dir is True
        assert config.confirm_overwrite is True
        assert config.require_confirmation is False
        assert config.run_history_enabled is True
        assert config.run_history_limit == 50
        assert config.verbose is False
        assert config.trace is False

    def test_default_destination_is_none(self):
        """Test that default_destination is None by default."""
        config = BkxConfig()
        assert config.default_destination is None


class TestBkxConfigFromDict:
    """Tests for creating config from dictionary."""

    def test_from_dict_with_values(self):
        """Test creating config from dict with custom values."""
        config = BkxConfig.from_dict(
            {
                "default_destination": "/custom/path",
                "compression_algorithm": "gzip",
                "compression_level": 6,
                "fresh_restore_dir": False,
            }
        )

        assert config.default_destination == Path("/custom/path")
        assert config.compression_algorithm == "gzip"
        assert config.compression_level == 6
        assert config.fresh_restore_dir is False
        assert config._config_source == "file"

    def test_from_dict_missing_values_use_defaults(self):
        """Test that missing values use defaults."""
        config = BkxConfig.from_dict({})

        assert config.verify_after_operation is True
        assert config.compression_algorithm == "zstd"
        assert config.fresh_restore_dir is True


class TestBkxConfigFromEnv:
    """Tests for creating config from environment variables."""

    def test_from_env_with_values(self):
        """Test creating config from env vars."""
        env_vars = {
            "BKX_DESTINATION": "/env/path",
            "BKX_COMPRESSION_ALGORITHM": "xz",
            "BKX_COMPRESSION_LEVEL": "9",
            "BKX_FRESH_RESTORE_DIR": "false",
        }

        with tempfile.TemporaryDirectory() as tmpdir:
            old_cwd = os.getcwd()
            os.chdir(tmpdir)
            try:
                for key, value in env_vars.items():
                    os.environ[key] = value

                try:
                    config = BkxConfig.from_env()

                    assert config.default_destination == Path("/env/path")
                    assert config.compression_algorithm == "xz"
                    assert config.compression_level == 9
                    assert config.fresh_restore_dir is False
                    assert config._config_source == "env"
                finally:
                    for key in env_vars:
                        os.environ.pop(key, None)
            finally:
                os.chdir(old_cwd)

    def test_from_env_missing_vars_use_defaults(self):
        """Test that missing env vars use defaults."""
        # Clear relevant env vars
        for key in ["BKX_DESTINATION", "BKX_COMPRESSION_ALGORITHM", "BKX_VERIFY"]:
            os.environ.pop(key, None)

        config = BkxConfig.from_env()

        assert config.default_destination is None
        assert config.compression_algorithm == "zstd"
        assert config.verify_after_operation is True


class TestBkxConfigMerge:
    """Tests for config merging."""

    def test_env_overrides_defaults(self):
        """Test that env vars override defaults."""
        with tempfile.TemporaryDirectory() as tmpdir:
            old_cwd = os.getcwd()
            os.chdir(tmpdir)
            try:
                os.environ["BKX_COMPRESSION_ALGORITHM"] = "lz4"

                config = BkxConfig.load()

                assert config.compression_algorithm == "lz4"
            finally:
                os.environ.pop("BKX_COMPRESSION_ALGORITHM", None)
                os.chdir(old_cwd)

    def test_file_overrides_env(self):
        """Test that config file overrides env vars."""
        with tempfile.TemporaryDirectory() as tmpdir:
            old_cwd = os.getcwd()
            os.chdir(tmpdir)
            try:
                # Create config file
                config_file = Path(tmpdir) / ".bkx.yaml"
                config_file.write_text("""
compression_algorithm: bzip2
compression_level: 5
""")

                os.environ["BKX_COMPRESSION_ALGORITHM"] = "zstd"

                reset_config()  # Clear cached config
                config = BkxConfig.load()

                # Config file should override env
                assert config.compression_algorithm == "bzip2"
                assert config.compression_level == 5
            finally:
                os.environ.pop("BKX_COMPRESSION_ALGORITHM", None)
                config_file.unlink(missing_ok=True)
                os.chdir(old_cwd)


class TestBkxConfigHelpers:
    """Tests for helper methods."""

    def test_get_compression_profile(self):
        """Test get_compression_profile helper."""
        config = BkxConfig(
            compression_algorithm="zstd",
            compression_level=5,
        )

        profile = config.get_compression_profile()

        assert profile["algorithm"] == "zstd"
        assert profile["level"] == 5

    def test_get_tool_preferences(self):
        """Test get_tool_preferences helper."""
        config = BkxConfig(
            preferred_archiver="tar",
            preferred_compressor="zstd",
            preferred_hasher="xxh128",
            preferred_transfer="rsync",
        )

        prefs = config.get_tool_preferences()

        assert prefs["archiver"] == "tar"
        assert prefs["compressor"] == "zstd"
        assert prefs["hasher"] == "xxh128"
        assert prefs["transfer"] == "rsync"


class TestGetConfig:
    """Tests for get_config() singleton."""

    def test_get_config_returns_config(self):
        """Test that get_config returns a config object."""
        reset_config()
        config = get_config()

        assert isinstance(config, BkxConfig)

    def test_get_config_is_singleton(self):
        """Test that get_config returns the same instance."""
        reset_config()
        config1 = get_config()
        config2 = get_config()

        assert config1 is config2

    def test_reload_config(self):
        """Test that reload_config forces reload."""
        reset_config()
        config1 = get_config()

        # Modify env and reload
        os.environ["BKX_VERBOSE"] = "true"
        try:
            config2 = reload_config()

            assert config2.verbose is True
            assert config2 is not config1
        finally:
            os.environ.pop("BKX_VERBOSE", None)
            reset_config()


class TestConfigBoolParsing:
    """Tests for boolean parsing from strings."""

    def test_parse_bool_true_values(self):
        """Test parsing true values."""
        assert BkxConfig._parse_bool("true") is True
        assert BkxConfig._parse_bool("1") is True
        assert BkxConfig._parse_bool("yes") is True
        assert BkxConfig._parse_bool("True") is True

    def test_parse_bool_false_values(self):
        """Test parsing false values."""
        assert BkxConfig._parse_bool("false") is False
        assert BkxConfig._parse_bool("0") is False
        assert BkxConfig._parse_bool("no") is False

    def test_parse_bool_none(self):
        """Test parsing None returns False."""
        assert BkxConfig._parse_bool(None) is False

    def test_parse_path(self):
        """Test path parsing."""
        assert BkxConfig._parse_path("/test/path") == Path("/test/path")
        assert BkxConfig._parse_path(None) is None


class TestConfigFileSearch:
    """Tests for config file search locations."""

    def test_search_order(self):
        """Test that config files are searched in correct order."""
        # The search order is: .bkx.yaml, bkx.yaml, ~/.bkx.yaml
        # We test the _load_from_file method behavior
        # by checking that it doesn't crash and returns None when no files exist

        with tempfile.TemporaryDirectory() as tmpdir:
            old_cwd = os.getcwd()
            os.chdir(tmpdir)
            try:
                reset_config()
                result = BkxConfig._load_from_file()

                # Should return None when no config files exist
                assert result is None
            finally:
                os.chdir(old_cwd)
