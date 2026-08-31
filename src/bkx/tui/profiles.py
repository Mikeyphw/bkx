"""Profiles and context switching

Profile state management for persistent user configurations.
Supports multiple profiles with their own settings.
"""

from __future__ import annotations

import json
import threading
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any


def _get_config_dir() -> Path:
    """Get the BKX config directory."""
    import os

    config_home = os.environ.get("XDG_CONFIG_HOME", "")
    if config_home:
        return Path(config_home) / "bkx"
    return Path.home() / ".config" / "bkx"


def _get_profiles_file() -> Path:
    """Get the profiles file path."""
    return _get_config_dir() / "profiles.json"


@dataclass(slots=True)
class BkxProfile:
    """A user profile with configuration settings."""

    name: str
    source_dir: str = ""  # Store as string for JSON
    destination_dir: str = ""
    algorithm: str = "zstd"
    compression_level: int = 3
    excludes: list[str] = field(default_factory=list)
    verify: bool = True
    last_operation: str = ""
    last_archive: str = ""

    @property
    def source_path(self) -> Path | None:
        """Get source directory as Path."""
        if self.source_dir:
            return Path(self.source_dir)
        return None

    @property
    def destination_path(self) -> Path | None:
        """Get destination directory as Path."""
        if self.destination_dir:
            return Path(self.destination_dir)
        return None

    def to_dict(self) -> dict[str, Any]:
        """Convert to dictionary for JSON serialization."""
        return asdict(self)

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> BkxProfile:
        """Create from dictionary."""
        return cls(**data)


@dataclass(slots=True)
class ProfileState:
    """Profile management state."""

    current_profile: str = "default"
    profiles: dict[str, dict[str, Any]] = field(default_factory=dict)

    def get_profile(self, name: str) -> BkxProfile | None:
        """Get a profile by name."""
        if name in self.profiles:
            return BkxProfile.from_dict(self.profiles[name])
        return None

    def get_current_profile(self) -> BkxProfile:
        """Get the current profile."""
        profile = self.get_profile(self.current_profile)
        if profile:
            return profile
        # Return default if current doesn't exist
        return BkxProfile(name="default")

    def set_profile(self, profile: BkxProfile) -> None:
        """Set a profile."""
        self.profiles[profile.name] = profile.to_dict()

    def delete_profile(self, name: str) -> bool:
        """Delete a profile by name.

        Returns:
            True if deleted, False if not found
        """
        if name in self.profiles:
            del self.profiles[name]
            return True
        return False

    def switch_to(self, name: str) -> bool:
        """Switch to a profile.

        Returns:
            True if switched, False if profile doesn't exist
        """
        if name in self.profiles or name == "default":
            self.current_profile = name
            return True
        return False

    def to_dict(self) -> dict[str, Any]:
        """Convert to dictionary."""
        return {
            "current_profile": self.current_profile,
            "profiles": self.profiles,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> ProfileState:
        """Create from dictionary."""
        return cls(
            current_profile=data.get("current_profile", "default"),
            profiles=data.get("profiles", {}),
        )


class ProfileManager:
    """Thread-safe profile manager.

    Handles profile persistence and switching.
    """

    def __init__(self) -> None:
        """Initialize profile manager."""
        self._lock = threading.Lock()
        self._state = ProfileState()
        self._load()

    def _load(self) -> None:
        """Load profiles from disk."""
        profiles_file = _get_profiles_file()
        if not profiles_file.exists():
            # Initialize with default profile
            self._init_default_profile()
            return

        try:
            with open(profiles_file, encoding="utf-8") as f:
                data = json.load(f)

            self._state = ProfileState.from_dict(data)
        except Exception:
            self._state = ProfileState()
            self._init_default_profile()

    def _init_default_profile(self) -> None:
        """Initialize the default profile."""
        default = BkxProfile(name="default")
        self._state.set_profile(default)

    def _save(self) -> None:
        """Save profiles to disk."""
        profiles_file = _get_profiles_file()

        try:
            profiles_file.parent.mkdir(parents=True, exist_ok=True)

            with open(profiles_file, "w", encoding="utf-8") as f:
                json.dump(self._state.to_dict(), f, indent=2)
        except Exception:
            pass  # Best-effort save

    def get_profiles(self) -> list[str]:
        """Get list of profile names."""
        with self._lock:
            return list(self._state.profiles.keys())

    def get_profile(self, name: str) -> BkxProfile | None:
        """Get a profile by name."""
        with self._lock:
            return self._state.get_profile(name)

    def get_profile_names(self) -> list[str]:
        """Get list of profile names including 'default'."""
        with self._lock:
            names = set(self._state.profiles.keys())
            names.add("default")
            return sorted(names)

    def get_current_profile(self) -> BkxProfile:
        """Get the current profile."""
        with self._lock:
            return self._state.get_current_profile()

    def switch_profile(self, name: str) -> bool:
        """Switch to a profile.

        Args:
            name: Name of profile to switch to

        Returns:
            True if switched, False if profile doesn't exist
        """
        with self._lock:
            if name == "default" or name in self._state.profiles:
                self._state.current_profile = name
                self._save()
                return True
            return False

    def create_profile(self, name: str, source_dir: str = "", destination_dir: str = "") -> BkxProfile:
        """Create a new profile.

        Args:
            name: Name for the new profile
            source_dir: Optional source directory
            destination_dir: Optional destination directory

        Returns:
            The created BkxProfile
        """
        with self._lock:
            profile = BkxProfile(
                name=name,
                source_dir=source_dir,
                destination_dir=destination_dir,
            )
            self._state.set_profile(profile)
            self._save()
            return profile

    def update_profile(self, profile: BkxProfile) -> None:
        """Update an existing profile.

        Args:
            profile: The profile to update
        """
        with self._lock:
            self._state.set_profile(profile)
            self._save()

    def delete_profile(self, name: str) -> bool:
        """Delete a profile.

        Args:
            name: Name of profile to delete

        Returns:
            True if deleted, False if not found
        """
        with self._lock:
            if name == "default":
                return False  # Can't delete default
            result = self._state.delete_profile(name)
            if result:
                self._save()
            return result

    def duplicate_profile(self, source_name: str, new_name: str) -> BkxProfile | None:
        """Duplicate a profile with a new name.

        Args:
            source_name: Name of profile to duplicate
            new_name: Name for the new profile

        Returns:
            The new profile or None if source doesn't exist
        """
        with self._lock:
            source = self._state.get_profile(source_name)
            if not source:
                return None

            new_profile = BkxProfile(
                name=new_name,
                source_dir=source.source_dir,
                destination_dir=source.destination_dir,
                algorithm=source.algorithm,
                compression_level=source.compression_level,
                excludes=list(source.excludes),
                verify=source.verify,
            )
            self._state.set_profile(new_profile)
            self._save()
            return new_profile


# Global profile manager instance
_profile_manager: ProfileManager | None = None


def get_profile_manager() -> ProfileManager:
    """Get the global profile manager instance.

    Returns:
        ProfileManager singleton
    """
    global _profile_manager
    if _profile_manager is None:
        _profile_manager = ProfileManager()
    return _profile_manager


def reset_profile_manager() -> ProfileManager:
    """Reset and return a new profile manager.

    Useful for testing.

    Returns:
        New ProfileManager instance
    """
    global _profile_manager
    _profile_manager = ProfileManager()
    return _profile_manager


# Convenience functions
def get_current_profile() -> BkxProfile:
    """Get the current active profile."""
    return get_profile_manager().get_current_profile()


def get_profile_names() -> list[str]:
    """Get list of available profile names."""
    return get_profile_manager().get_profile_names()


def switch_profile(name: str) -> bool:
    """Switch to a profile by name."""
    return get_profile_manager().switch_profile(name)


def create_profile(name: str, source_dir: str = "", destination_dir: str = "") -> BkxProfile:
    """Create a new profile."""
    return get_profile_manager().create_profile(name, source_dir, destination_dir)


def update_profile(profile: BkxProfile) -> None:
    """Update an existing profile."""
    get_profile_manager().update_profile(profile)
