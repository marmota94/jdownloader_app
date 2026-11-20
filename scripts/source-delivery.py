from pathlib import Path
root = Path(__file__).resolve().parents[1]
excluded = {'.gradle', '.git', 'build', 'build-output', '.idea', 'private', '__pycache__'}
files = sorted(p for p in root.rglob('*') if p.is_file() and not excluded.intersection(p.relative_to(root).parts) and p.suffix not in {'.log', '.jar', '.jks', '.keystore', '.pyc'} and not p.name.startswith('.env') and p.name not in {'SOURCE_DELIVERY.md','local.properties'})
priority = ['Dockerfile','docker-compose.yml','Makefile','settings.gradle.kts','build.gradle.kts','app/build.gradle.kts','gradle/libs.versions.toml','app/src/main/AndroidManifest.xml']
priority += ['app/src/main/java/org/jdownloader/remote/' + p for p in ['data/MyJdCrypto.kt', 'data/MyJdApiClient.kt', 'cnl/ClicknLoadServer.kt', 'cnl/CnlDecryptor.kt', 'cnl/CnlForegroundService.kt', 'presentation/theme/Theme.kt', 'presentation/theme/Color.kt', 'data/SettingsRepository.kt']]
priority += ['app/src/main/res/' + p + '/strings.xml' for p in ['values', 'values-es', 'values-de']]
ordered = sorted(files, key=lambda p: (priority.index(p.relative_to(root).as_posix()) if p.relative_to(root).as_posix() in priority else 100, p.relative_to(root).as_posix()))
lines = ['# JD Remote source delivery', '', '## Complete source tree', '', '```text']
for p in sorted(files + [root/'gradle/wrapper/gradle-wrapper.jar']): lines.append(p.relative_to(root).as_posix())
lines += ['```', '', '## Full source files', '']
for p in ordered:
    lines += ['### ' + p.relative_to(root).as_posix(), '', '````' + ({'.kt':'kotlin','.kts':'kotlin','.xml':'xml','.yml':'yaml','.py':'python','.sh':'sh'}.get(p.suffix,'')), p.read_text(encoding='utf-8-sig'), '````', '']
(root/'SOURCE_DELIVERY.md').write_text('\n'.join(lines),encoding='utf-8')
