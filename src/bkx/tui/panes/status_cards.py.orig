"""Status cards pane - Dashboard-style status display for settings.

Uses mikomn's StatusCards for displaying settings and status information.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from textual.app import ComposeResult
from textual.containers import Container, Grid, Horizontal, Vertical
from textual.message import Message
from textual.widgets import Label, Static

# mikomn TUI base widgets for enhanced UI
try:
    from mikomn.tui_base.models import StatusCardData as MikomnStatusCardData
    from mikomn.tui_base.widgets import StatusCardData, StatusCards

    _HAS_MIKOMN = True
except ImportError:
    _HAS_MIKOMN = False


@dataclass
class StatusCard:
    """Represents a status card."""

    id: str
    title: str
    value: str
    subtitle: str = ""
    icon: str = "📊"
    status: str = "info"  # "info", "success", "warning", "error"


@dataclass
class StatusCardConfig:
    """Configuration for status cards display."""

    cards: list[StatusCard] = field(default_factory=list)
    layout: str = "grid"  # "grid", "list", "columns"
    columns: int = 2


if _HAS_MIKOMN:

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

        def compose(self) -> ComposeResult:
            cards_data = [
                MikomnStatusCardData(
                    id=card.id,
                    title=card.title,
                    value=card.value,
                    subtitle=card.subtitle,
                    icon=card.icon,
                    status=card.status,
                )
                for card in self._config.cards
            ]
            self._status_cards = StatusCards(
                cards=cards_data,
                layout=self._config.layout,
                id="status-cards",
            )
            yield self._status_cards

        def update_card(
            self,
            card_id: str,
            value: str,
            subtitle: str = "",
            status: str = "info",
        ) -> None:
            """Update a card's value.

            Args:
                card_id: ID of the card to update
                value: New value
                subtitle: New subtitle
                status: New status
            """
            if self._status_cards:
                self._status_cards.update_card(
                    card_id,
                    value=value,
                    subtitle=subtitle,
                    status=status,
                )

        def get_card(self, card_id: str) -> StatusCard | None:
            """Get a card by ID."""
            for card in self._config.cards:
                if card.id == card_id:
                    return card
            return None

else:

    class StatusCardsPane(Container):
        """Status cards pane with custom implementation."""

        def __init__(
            self,
            config: StatusCardConfig | None = None,
            **kwargs,
        ):
            super().__init__(**kwargs)
            self._config = config or StatusCardConfig()
            self._card_widgets: dict[str, Container] = {}

        def compose(self) -> ComposeResult:
            yield Label("📊 Status", classes="cards-header")

            if self._config.layout == "list":
                yield Grid(classes="cards-list", id="cards-container")
            else:
                yield Grid(classes="cards-grid", id="cards-container")

            self._compose_cards()

        def _compose_cards(self) -> None:
            """Compose card widgets."""
            container = self.query_one("#cards-container", Grid)
            container.remove_children()
            self._card_widgets.clear()

            for card in self._config.cards:
                card_widget = self._create_card_widget(card)
                container.append(card_widget)
                self._card_widgets[card.id] = card_widget

        def _create_card_widget(self, card: StatusCard) -> Container:
            """Create a card widget."""
            if self._config.layout == "list":
                return self._create_list_card(card)
            return self._create_grid_card(card)

        def _create_grid_card(self, card: StatusCard) -> Container:
            """Create a grid-style card."""
            card_container = Container(
                Static(card.icon, classes="card-icon"),
                Label(card.title, classes="card-title"),
                Label(card.value, classes="card-value"),
                Label(card.subtitle, classes="card-subtitle"),
                classes=f"status-card {card.status}",
                id=f"card-{card.id}",
            )
            return card_container

        def _create_list_card(self, card: StatusCard) -> Container:
            """Create a list-style card."""
            card_container = Container(
                Horizontal(
                    Static(card.icon, classes="list-icon"),
                    Vertical(
                        Label(card.title, classes="list-title"),
                        Label(card.value, classes="list-value"),
                        Label(card.subtitle, classes="list-subtitle"),
                        classes="list-content",
                    ),
                    classes="list-card-row",
                ),
                classes=f"list-card {card.status}",
                id=f"card-{card.id}",
            )
            return card_container

        def update_card(
            self,
            card_id: str,
            value: str,
            subtitle: str = "",
            status: str = "info",
        ) -> None:
            """Update a card's value."""
            # Update config
            for card in self._config.cards:
                if card.id == card_id:
                    card.value = value
                    if subtitle:
                        card.subtitle = subtitle
                    card.status = status
                    break

            # Update widget
            card_widget = self.query_one(f"#card-{card_id}", Container)
            if card_widget:
                card_widget.remove_class("info", "success", "warning", "error")
                card_widget.add_class(status)
                value_label = card_widget.query_one(".card-value, .list-value", Label)
                if value_label:
                    value_label.update(value)
                subtitle_label = card_widget.query_one(".card-subtitle, .list-subtitle", Label)
                if subtitle_label and subtitle:
                    subtitle_label.update(subtitle)

        def get_card(self, card_id: str) -> StatusCard | None:
            """Get a card by ID."""
            for card in self._config.cards:
                if card.id == card_id:
                    return card
            return None


class ThemeSettingsPane(Container):
    """Settings pane for theme configuration."""

    DEFAULT_CLASSES = "bkx-theme-settings-pane"

    class SettingChanged(Message):
        """A setting was changed."""

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
        status: str = "info",
    ) -> None:
        """Update a status card."""
        status_pane = self.query_one("#status-cards-pane", StatusCardsPane)
        status_pane.update_card(card_id, value, subtitle, status)


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
            status="info",
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
            status="info",
        ),
        StatusCard(
            id="level",
            title="Default Level",
            value="3",
            subtitle="Compression level",
            icon="📊",
            status="info",
        ),
        StatusCard(
            id="archives",
            title="Archives",
            value="0",
            subtitle="Available archives",
            icon="📁",
            status="info",
        ),
        StatusCard(
            id="storage",
            title="Storage",
            value="0 MB",
            subtitle="Total size",
            icon="💾",
            status="info",
        ),
    ]
