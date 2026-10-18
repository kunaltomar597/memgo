# Root checks for every component. `make check` fails if any component fails.
.PHONY: check hub-check cloud-check app-check privacy-scan

check: hub-check cloud-check app-check privacy-scan

hub-check:
	$(MAKE) -C hub lint typecheck test

cloud-check:
	cd cloud && npm run lint && npm run typecheck && npm test

app-check:
	cd app && ./gradlew --quiet lint test

privacy-scan:
	cd hub && uv run python ../scripts/privacy_scan.py --self-test
