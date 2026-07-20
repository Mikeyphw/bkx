"""Local legacy Textual helpers used by pre-migration widget tests.

The runtime TUI is model-first via ``mikomn.tui``. These classes keep older
BKX Textual panes importable while those widgets are phased out.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any

from textual.app import ComposeResult
from textual.containers import Container, Vertical
from textual.message import Message
from textual.screen import ModalScreen
from textual.widgets import Input, Label, ListView, ProgressBar, RichLog, Static


@dataclass(slots=True)
class FilterOption:
    value: str
    label: str
    selected: bool = False
    count: int | None = None


@dataclass(slots=True)
class FilterGroup:
    field: str
    label: str
    options: list[FilterOption] = field(default_factory=list)
    multi_select: bool = False


@dataclass(slots=True)
class StatusCardData:
    id: str
    title: str
    value: str
    subtitle: str = ""
    icon: str = ""
    status: str = "info"


class FilterMode(str, Enum):
    NONE = "none"
    FILES_ONLY = "files"
    DIRS_ONLY = "dirs"


class FileDirPicker(ModalScreen[list[Path] | None]):
    def __init__(self, *, start_path: Path | None = None, filter_mode: FilterMode = FilterMode.NONE, **kwargs) -> None:
        super().__init__(**kwargs)
        self.start_path = start_path or Path.cwd()
        self.filter_mode = filter_mode

    def compose(self) -> ComposeResult:
        yield Static(str(self.start_path), classes="file-picker")


class FormPane(Vertical):
    def __init__(self, **kwargs) -> None:
        super().__init__(**kwargs)
        self._fields: dict[str, Callable[[], Any]] = {}
        self._widgets: list[tuple[str, str, Any]] = []

    def add_field(self, name: str, label: str, *, placeholder: str = "") -> None:
        widget = Input(placeholder=placeholder, id=f"field-{name}", classes="form-field")
        self.add_block(name, label, widget, get_value=lambda w=widget: w.value)

    def add_block(self, name: str, label: str, widget, *, get_value: Callable[[], Any]) -> None:
        self._fields[name] = get_value
        self._widgets.append((name, label, widget))

    def compose(self) -> ComposeResult:
        for _name, label, widget in self._widgets:
            yield Container(Label(label, classes="form-label"), widget, classes="form-row")

    def get_form_data(self) -> dict[str, Any]:
        return {name: getter() for name, getter in self._fields.items()}


class StatusMessage(Message):
    def __init__(self, text: str, *, level: str = "info", timeout: float | None = None) -> None:
        super().__init__()
        self.text = text
        self.level = level
        self.timeout = timeout


class StatusBar(Static):
    def __init__(self, *args, **kwargs) -> None:
        super().__init__("", *args, **kwargs)

    def on_status_message(self, event: StatusMessage) -> None:
        self.update(event.text)


class GlobalProgressBar(ProgressBar):
    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, total=100, show_eta=False, **kwargs)
        self.eta_seconds = 0
        self.saved_mb = 0.0

    @property
    def progress(self) -> float:
        return float(self.percentage or 0.0)

    @progress.setter
    def progress(self, value: float) -> None:
        self.update(progress=max(0.0, min(100.0, float(value))))


class ProgressPane(Vertical):
    def __init__(self, *_, **kwargs) -> None:
        super().__init__(**kwargs)

    def compose(self) -> ComposeResult:
        yield GlobalProgressBar()

    @property
    def progress_bar(self) -> GlobalProgressBar:
        return self.query_one(GlobalProgressBar)


class StatusPane(Vertical):
    def __init__(self, *, title: str = "Status", **kwargs) -> None:
        super().__init__(**kwargs)
        self.title = title
        self.status_bar = StatusBar()

    def compose(self) -> ComposeResult:
        yield Static(self.title)
        yield self.status_bar


class LogPane(Vertical):
    def __init__(self, *, title: str = "Log", **kwargs) -> None:
        super().__init__(**kwargs)
        self.title = title
        self._log = RichLog()

    def compose(self) -> ComposeResult:
        yield Static(self.title)
        yield self._log

    def write(self, text: str) -> None:
        self._log.write(text)

    def write_lines(self, lines: list[str]) -> None:
        for line in lines:
            self.write(line)

    def clear(self) -> None:
        self._log.clear()


class StatusCards(Static):
    def __init__(self, *, cards: list[StatusCardData] | None = None, layout: str = "grid", **kwargs) -> None:
        super().__init__("", **kwargs)
        self.cards = {card.id: card for card in cards or []}
        self.layout = layout

    def update_card(self, card_id: str, card: StatusCardData) -> None:
        self.cards[card_id] = card


class FilterableListPane(Vertical):
    class FilterChanged(Message):
        def __init__(self, filter_text: str, filtered_count: int, total_count: int) -> None:
            super().__init__()
            self.filter_text = filter_text
            self.filtered_count = filtered_count
            self.total_count = total_count

    class SelectionChanged(Message):
        def __init__(self, value: Any | None) -> None:
            super().__init__()
            self.value = value

    def __init__(self, *, title: str = "Items", filter_placeholder: str = "Search...", **kwargs) -> None:
        super().__init__(**kwargs)
        self.title = title
        self.filter_placeholder = filter_placeholder
        self._items: list[tuple[str, Any]] = []

    def __class_getitem__(cls, _item):
        return cls

    def compose(self) -> ComposeResult:
        yield Static(self.title)
        yield Input(placeholder=self.filter_placeholder, classes="filter-input")
        yield ListView(classes="filterable-list")

    def set_items(self, items: list[tuple[str, Any]]) -> None:
        self._items = list(items)

    def set_filter_text(self, _text: str) -> None:
        return None

    def focus_filter(self) -> None:
        return None

    def select_value(self, value: Any) -> None:
        self.post_message(self.SelectionChanged(value))


class PageSpec:
    def __init__(
        self,
        route: str,
        factory,
        label: str,
        key: str | None = None,
        icon: str | None = None,
        group: str | None = None,
        group_order: int = 0,
        order: int = 0,
        disabled: bool = False,
    ) -> None:
        self.route = route
        self.factory = factory
        self.label = label
        self.key = key
        self.icon = icon
        self.group = group
        self.group_order = group_order
        self.order = order
        self.disabled = disabled


class PageRegistry:
    def __init__(self) -> None:
        self._pages: dict[str, PageSpec] = {}

    def register(self, spec: PageSpec) -> None:
        self._pages[spec.route] = spec

    def get(self, route: str) -> PageSpec | None:
        return self._pages.get(route)

    def specs(self):
        return sorted(self._pages.values(), key=lambda s: (s.group_order, s.order, s.label))
