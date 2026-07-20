"""Status cards pane - Dashboard-style status display for settings.

Uses mikomn's StatusCards for displaying settings and status information.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from textual.app import ComposeResult
from textual.containers import Container, Grid
from textual.message import Message
from textual.widgets import Label

from bkx.tui.legacy import StatusCardData as MikomnStatusCardData
from bkx.tui.legacy import StatusCards

if TYPE_CHECKING:
    pass


@dataclass
class StatusCard:
    """Represents a status card."""

    id: str
    title: str
    value: str
    subtitle: str = ""
    icon: str = "📊"
    status: str = "information"  # "information", "success", "warning", "error"


@dataclass
class StatusCardConfig:
    """Configuration for status cards display."""

    cards: list[StatusCard] = field(default_factory=list)
    layout: str = "grid"  # "grid", "list", "columns"
    columns: int = 2


class StatusCardsPane(Container):
    """Status cards pane using mikomn's StatusCards."""

    DEFAULT_CLASSES = "bkx-status-cards-pane"

    def __init__(
        self,
        config: StatusCardConfig | None = None,
        **kwargs,
    ):
        super().__init__(**kwargs)
        self._config = config or StatusCardConfig()
        self._status_cards: StatusCards | None = None
        # Track cards data for updates
        self._cards_data: dict[str, StatusCard] = {}
        for card in self._config.cards:
            self._cards_data[card.id] = card

    def compose(self) -> ComposeResult:
        cards_data = [
            MikomnStatusCardData(
                id=card.id,
                title=card.title,
                value=card.value,
                subtitle=card.subtitle,
                icon=card.icon,
                status=self._map_status(card.status),
            )
            for card in self._config.cards
        ]
        self._status_cards = StatusCards(
            cards=cards_data,
            layout=self._config.layout,
            id="status-cards",
        )
        yield self._status_cards

    def _map_status(self, status: str) -> str:
        """Map status string to mikomn status format."""
        status_map = {
            "information": "info",
            "success": "success",
            "warning": "warning",
            "error": "error",
        }
        return status_map.get(status, "info")

    def update_card(
        self,
        card_id: str,
        value: str,
        subtitle: str = "",
        status: str = "information",
    ) -> None:
        """Update a card's value.

        Args:
            card_id: ID of the card to update
            value: New value
            subtitle: New subtitle
            status: New status
        """
        if self._status_cards and card_id in self._cards_data:
            card_data = self._cards_data[card_id]
            # Update local state
            card_data.value = value
            card_data.subtitle = subtitle
            card_data.status = status

            # Create mikomn StatusCardData with all fields
            mikomn_card = MikomnStatusCardData(
                id=card_id,
                title=card_data.title,
                value=value,
                subtitle=subtitle,
                icon=card_data.icon,
                status=self._map_status(status),
            )
            self._status_cards.update_card(card_id, mikomn_card)

    def get_card(self, card_id: str) -> StatusCard | None:
        """Get a card by ID."""
        return self._cards_data.get(card_id)


class ThemeSettingsPane(Container):
    """Settings pane for theme configuration."""

    DEFAULT_CLASSES = "bkx-theme-settings-pane"

    class SettingChanged(Message):
        """A setting was changed."""

        setting_key: str
        setting_value: str

        def __init__(self, setting_key: str, setting_value: str) -> None:
            super().__init__()
            self.setting_key = setting_key
            self.setting_value = setting_value

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self._settings: dict[str, str] = {}

    def compose(self) -> ComposeResult:
        yield Label("⚙️ Settings", classes="settings-header")

        yield Container(
            Grid(
                # Theme setting
                Container(
                    Label("Theme:", classes="setting-label"),
                    Label("sweet-dracula", classes="setting-value", id="theme-value"),
                    classes="setting-row",
                ),
                # Compression setting
                Container(
                    Label("Compression:", classes="setting-label"),
                    Label("zstd", classes="setting-value", id="compression-value"),
                    classes="setting-row",
                ),
                # Verify setting
                Container(
                    Label("Verify:", classes="setting-label"),
                    Label("Enabled", classes="setting-value", id="verify-value"),
                    classes="setting-row",
                ),
                # Level setting
                Container(
                    Label("Default Level:", classes="setting-label"),
                    Label("3", classes="setting-value", id="level-value"),
                    classes="setting-row",
                ),
                id="settings-grid",
            ),
            classes="settings-grid",
        )

    def update_setting(self, key: str, value: str) -> None:
        """Update a setting value.

        Args:
            key: Setting key (e.g., "theme", "compression")
            value: New value
        """
        self._settings[key] = value
        label = self.query_one(f"#{key}-value", Label)
        if label:
            label.update(value)
        self.post_message(self.SettingChanged(setting_key=key, setting_value=value))


class ToolsDashboardPane(Container):
    """Dashboard-style pane combining status cards and settings."""

    DEFAULT_CLASSES = "bkx-tools-dashboard-pane"

    def __init__(self, config: StatusCardConfig | None = None, **kwargs):
        super().__init__(**kwargs)
        self._status_cards_config = config or StatusCardConfig(cards=create_default_status_cards())

    def compose(self) -> ComposeResult:
        yield Label("🔧 Tools & Settings", classes="dashboard-header")

        yield Container(
            StatusCardsPane(
                config=self._status_cards_config,
                id="status-cards-pane",
            ),
            ThemeSettingsPane(id="theme-settings"),
            classes="dashboard-content",
        )

    def add_status_card(self, card: StatusCard) -> None:
        """Add a status card."""
        self._status_cards_config.cards.append(card)

    def update_status_card(
        self,
        card_id: str,
        value: str,
        subtitle: str = "",
        status: str = "information",
    ) -> None:
        """Update a status card."""
        status_pane = self.query_one("#status-cards-pane", StatusCardsPane)
        status_pane.update_card(card_id, value, subtitle, status)

    def update_setting(self, key: str, value: str) -> None:
        """Update a theme/settings value (for compatibility with tools.py).

        Args:
            key: Setting key ("theme", "compression", "verify", "level")
            value: New value
        """
        # Map setting keys to status card IDs
        card_mapping: dict[str, tuple[str, str, str]] = {
            "theme": ("theme", value, "Current color scheme"),
            "compression": ("compression", value, "Default algorithm"),
            "verify": ("verification", value, "Verify after ops"),
            "level": ("level", value, "Compression level"),
        }

        if key in card_mapping:
            card_id, card_value, subtitle = card_mapping[key]
            self.update_status_card(card_id, card_value, subtitle, "info")


def create_default_status_cards() -> list[StatusCard]:
    """Create default status cards for BKX.

    Returns:
        List of default status cards
    """
    from bkx.ui.theme import get_current_theme_name

    return [
        StatusCard(
            id="theme",
            title="Theme",
            value=get_current_theme_name(),
            subtitle="Current color scheme",
            icon="🎨",
            status="information",
        ),
        StatusCard(
            id="compression",
            title="Compression",
            value="zstd",
            subtitle="Default algorithm",
            icon="📦",
            status="success",
        ),
        StatusCard(
            id="verification",
            title="Verification",
            value="Enabled",
            subtitle="Verify after operations",
            icon="🔍",
            status="information",
        ),
        StatusCard(
            id="level",
            title="Default Level",
            value="3",
            subtitle="Compression level",
            icon="📊",
            status="information",
        ),
        StatusCard(
            id="archives",
            title="Archives",
            value="0",
            subtitle="Available archives",
            icon="📁",
            status="information",
        ),
        StatusCard(
            id="storage",
            title="Storage",
            value="0 MB",
            subtitle="Total size",
            icon="💾",
            status="information",
        ),
    ]
