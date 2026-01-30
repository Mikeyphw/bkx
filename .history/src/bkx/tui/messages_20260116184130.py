"""TUI messages (intents).

Widgets -> Messages -> App actions -> Services.

Message-driven architecture:
- Widgets emit messages (user intents)
- App handles messages and dispatches to services
- Services return results as messages
- UI updates based on result messages

Contract rule:
- No business logic in message handlers
- Messages only carry data for Spec building
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional, Protocol, runtime_checkable

from textual.message import Message


# =============================================================================
# Intent Messages (User Actions)
# =============================================================================

class Intent(Message):
    """Base class for all TUI intents."""
    pass


@dataclass
class BackupIntent(Intent):
    """User wants to perform a backup operation."""
    sources: list[Path] = field(default_factory=list)
    destination: Optional[Path] = None
    archive_name: Optional[str] = None
    excludes: list[str] = field(default_factory=list)
    algorithm: str = "zstd"
    level: int = 3
    verify: bool = True
    
    def to_spec_params(self) -> dict:
        """Convert to spec builder parameters."""
        return {
            "sources": self.sources,
            "destination": self.destination,
            "archive_name": self.archive_name,
            "excludes": self.excludes,
            "algorithm": self.algorithm,
            "level": self.level,
            "verify": self.verify,
        }


@dataclass
class ArchiveIntent(Intent):
    """User wants to create an archive."""
    sources: list[Path] = field(default_factory=list)
    output: Path | None = None
    excludes: list[str] = field(default_factory=list)
    algorithm: str = "zstd"
    verify: bool = True
    
    def to_spec_params(self) -> dict:
        """Convert to spec builder parameters."""
        return {
            "sources": self.sources,
            "output": self.output,
            "excludes": self.excludes,
            "algorithm": self.algorithm,
            "verify": self.verify,
        }


@dataclass
class RestoreIntent(Intent):
    """User wants to restore from an archive."""
    archive: Path
    destination: Optional[Path] = None
    fresh_dir: bool = False
    overwrite: bool = False
    verify: bool = True
    
    def to_spec_params(self) -> dict:
        """Convert to spec builder parameters."""
        return {
            "archive": self.archive,
            "destination": self.destination,
            "fresh_dir": self.fresh_dir,
            "overwrite": self.overwrite,
            "verify": self.verify,
        }


@dataclass
class TransferIntent(Intent):
    """User wants to transfer files."""
    source: Path
    destination: Path
    direction: str = "push"  # "push" or "pull"
    verify: bool = True
    
    def to_spec_params(self) -> dict:
        """Convert to spec builder parameters."""
        return {
            "source": self.source,
            "destination": self.destination,
            "direction": self.direction,
            "verify": self.verify,
        }


# =============================================================================
# Navigation Messages
# =============================================================================

@dataclass
class NavigateToScreen(Intent):
    """Navigate to a specific screen."""
    screen_name: str  # "backup", "archive", "restore", "transfer", "tools"


@dataclass
class ShowCommandPalette(Intent):
    """Show the command palette overlay."""


@dataclass
class CycleTheme(Intent):
    """Cycle to the next theme."""


# =============================================================================
# Service Result Messages
# =============================================================================

@dataclass
class ServiceResult(Intent):
    """Base class for service operation results."""
    success: bool = False
    message: str = ""
    details: dict = field(default_factory=dict)


@dataclass
class BackupResult(ServiceResult):
    """Result from a backup operation."""
    archive_path: Optional[Path] = None
    files_count: int = 0
    bytes_total: int = 0


@dataclass
class ArchiveResult(ServiceResult):
    """Result from an archive creation operation."""
    archive_path: Optional[Path] = None
    files_count: int = 0


@dataclass
class RestoreResult(ServiceResult):
    """Result from a restore operation."""
    destination_path: Optional[Path] = None
    files_count: int = 0


@dataclass
class TransferResult(ServiceResult):
    """Result from a transfer operation."""
    source_path: Optional[Path] = None
    destination_path: Optional[Path] = None


@dataclass
class ServiceError(ServiceResult):
    """Error result from a service operation."""
    error_code: int = 1
    error_details: dict = field(default_factory=dict)


# =============================================================================
# UI State Messages
# =============================================================================

@dataclass
class SetBusy(Intent):
    """Set UI to busy state."""
    message: str = "Processing..."


@dataclass
class ClearBusy(Intent):
    """Clear busy state."""


@dataclass
class ShowNotification(Intent):
    """Show a notification."""
    message: str
    level: str = "info"  # "info", "success", "warning", "error"


@dataclass
class UpdateLog(Intent):
    """Update the run log pane."""
    message: str
    level: str = "info"  # "info", "success", "warning", "error", "debug"


# =============================================================================
# File Picker Messages
# =============================================================================

@dataclass
class FilesSelected(Intent):
    """User selected files via picker."""
    paths: list[Path]
    picker_for: str = ""  # Identifies which picker triggered this


@dataclass
class DirectoriesSelected(Intent):
    """User selected directories via picker."""
    paths: list[Path]
    picker_for: str = ""


# =============================================================================
# Protocol for Intent Handlers
# =============================================================================

@runtime_checkable
class IntentHandler(Protocol):
    """Protocol for handling intents."""
    
    def handle_backup_intent(self, intent: BackupIntent) -> None:
        ...
    
    def handle_archive_intent(self, intent: ArchiveIntent) -> None:
        ...
    
    def handle_restore_intent(self, intent: RestoreIntent) -> None:
        ...
    
    def handle_transfer_intent(self, intent: TransferIntent) -> None:
        ...

