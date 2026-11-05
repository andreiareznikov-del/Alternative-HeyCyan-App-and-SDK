# W610 ChatGPT integration 0.1 — проверенный программный результат

Дата: 5 ноября 2026.

- Основа: `50618c72191ad75475041667ac6e2ead6ae833cf`.
- Сборка исходников приложения: `6b668ad91ccb83664a038edd92a999eb3cd35d84`.
- GitHub Actions: https://github.com/andreiareznikov-del/Alternative-HeyCyan-App-and-SDK/actions/runs/42004916377 — success.
- 36 JVM/Android unit-тестов: 0 ошибок, 0 неудач, 0 пропусков. Исходные 26 тестов транспорта/координатора + 10 новых.
- 5 Python-проверок политики Manifest: PASS.
- lint: 0 Error/Fatal, 18 Warning, 1 Hint. Предупреждение ExportedContentProvider относится к tools:node=removeAll; реальный провайдер в итоговом Manifest имеет exported=false.
- Проверка git diff: app/ и w610jpeg/ не изменены относительно подтверждённой основы.
- Пакет: `com.fersaiyan.cyanbridge.w610chatgpt`, versionCode 1, versionName 0.1-integration, minSdk 31, targetSdk 36.
- APK: `W610-ChatGPT-Integration-0.1.apk`, 5 669 819 байт.
- SHA-256 APK: `88d19b6f735c1f4369dd72908945a65f289b5f4b18fd722144428b4a39f8cb69`.
- APK подписан офлайн; apksigner verify сообщает Verifies и v3=true. При minSdk 31 verifier выбирает v3; v2=false в отчёте не означает отсутствие действительной подписи.
- SHA-256 сертификата: `5ee8505b22698d80b6602099420a1626580c2d8d19bc5c29e2f05e93d37b465c` (RSA-3072). Для обновлений использовать только этот ключ.
- После подписи проверено равенство Manifest и всех исходных payload entries unsigned APK из CI. Изменения после CI затрагивают только инструмент офлайн-подписи и этот отчёт, исходники приложения совпадают со сборкой.

Итоговый Manifest прошёл аудит: одна неэкспортированная служба connectedDevice, один неэкспортированный FileProvider с read URI grants, 0 receivers. Разрешения: BLUETOOTH_SCAN neverForLocation, BLUETOOTH_CONNECT, FOREGROUND_SERVICE, FOREGROUND_SERVICE_CONNECTED_DEVICE, POST_NOTIFICATIONS, WAKE_LOCK; собственное signature-only разрешение AndroidX для внутренних receiver.

Нет доступа к Интернету, микрофону, телефонной камере, геолокации, контактам/сообщениям; нет Notification Listener, AccessibilityService или overlay. Tasker-профили не изменяются/не импортируются; аудиофокус и запуск нового голосового сеанса не запрашиваются.

Программный минимум готов: проверенный приёмник JPEG соединён с существующим explicit Share в обычный ChatGPT. Автоматическое открытие URI подтверждается только UID ChatGPT. Вложение и тот же Live подтверждаются отдельными отметками пользователя.

Физические сценарии А/Б/В/Г новой сборкой ещё не выполнены. Установка/Play Protect, аппаратная кнопка без предшествующего клика в приложении, background BLE на S23/API 37, актуальность AutoInput-селекторов, доставка в текущий Live и непрерывность звука не объявляются проверенными.

Инструкция и анализ: [README_RU.md](README_RU.md). Две сборки: первая прошла compile/tests/lint, но выявила ошибку формата числового поля в аудиторе; вторая полностью успешна. Исправления офлайн-подписи проверены локально и не потребовали повторной сборки APK.
