"""Smoke test: base imports must work without optional extras installed."""

def test_imports() -> None:
    import bkx  # noqa: F401
    import bkx.core  # noqa: F401
    import bkx.cli  # noqa: F401
