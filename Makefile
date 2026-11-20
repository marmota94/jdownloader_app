.PHONY: build-debug build-release test
build-debug:
	docker compose run --rm android-builder ./gradlew --no-daemon assembleDebug
build-release:
	docker compose run --rm android-builder ./gradlew --no-daemon assembleRelease
test:
	docker compose run --rm android-builder ./gradlew --no-daemon testDebugUnitTest lintDebug
