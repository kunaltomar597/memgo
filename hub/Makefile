# Remu hub. Every target runs inside the uv-managed Python 3.11 environment.
.PHONY: setup dev fake-camera test lint format typecheck eval

UV ?= uv
FIXTURE := ../tests/eval/samples/gate_fixture.mp4

setup:
	$(UV) sync --extra dev --python 3.11
	$(UV) run remu-hub models download
	@test -f $(FIXTURE) || $(UV) run python ../scripts/make_fixture_video.py $(FIXTURE)

dev:
	$(UV) run remu-hub demo

fake-camera:
	$(UV) run python ../scripts/fake_camera.py

test:
	$(UV) run pytest -q

lint:
	$(UV) run ruff check . ../scripts ../tests
	$(UV) run ruff format --check . ../scripts ../tests

format:
	$(UV) run ruff format . ../scripts ../tests
	$(UV) run ruff check --fix . ../scripts ../tests

typecheck:
	$(UV) run mypy

eval:
	$(UV) run python ../tests/eval/run_eval.py
