# Технические подробности WorkshopSentinel

## Клиентская видимость и необязательная установка

`mod.info` регистрирует мод в клиентском каталоге; наличие Java bootstrap не скрывает эту запись. Жёсткое `require=\ZombieBuddy` заменено необязательным порядком `loadModAfter=\ZombieBuddy`, чтобы редактор не блокировал выбор при отсутствии клиентской копии ZombieBuddy. На сервере ZombieBuddy по-прежнему необходим для загрузки Java-кода; его нужно подключить отдельно.

Начиная с 0.3.0, единый пакет WorkshopSentinel содержит серверный JAR и клиентский `WorkshopSentinelMLOSCompat.lua` в одном ID. Начиная с 0.4.1, используется нейтральный javaJarFile=media/java/WorkshopSentinel.jar: в предоставленном dedicated-server логе Build 42.21.0 / ZombieBuddy 2.3.4 путь server/ ошибочно фильтровался как клиентский запуск. Main регистрирует только минимальный bridge; собственный публичный guard GameServer.server проверяется внутри tick до инициализации Config/логов/scheduler. На клиенте bridge может быть зарегистрирован при наличии agent, но сервис не запускается. На dedicated server клиентский Lua не выполняется. Загрузка Lua по средам сама по себе не доказывает необязательность скачивания: проверка контрольных сумм и подключения без пакета остаётся игровым TODO.

### Совместимость с Mod Load Order Sorter

Установленный MLOS (`ModLoadOrderSorter_b42`, Workshop item `3423660713`) в `Refr_Utils:getModsIDs` добавляет mod ID только при непустом Workshop ID. Поэтому найденный локальный мод ошибочно попадает в ветку «mod not found» и исчезает из сохраняемой конфигурации.

Клиентский обработчик устанавливается на `Events.OnMainMenuEnter`, только когда MLOS активен, и оборачивает его таблицу `Refr_utils`. Только существующая локальная запись WorkshopSentinel обходится без Workshop ID; все остальные элементы проверяет оригинальный метод. Результат сохраняет исходный порядок выбранных mod ID и Workshop-список MLOS. Реально отсутствующий WorkshopSentinel с `modInfo=nil` не принимается за найденный. Если у WorkshopSentinel есть настоящий Workshop ID, используется оригинальная логика MLOS. Обработчик идемпотентен и не запускается на dedicated server или внутри подключённого клиента.

Для работы обработчик должен быть включён в основном клиентском списке модов. Он получает модуль активного MLOS через require при входе в главное меню; жёсткой зависимости от MLOS нет. Файлы стороннего MLOS не меняются.

Исправление проверено отдельно в установленном Kahlua runtime игры с настоящим `Refr_utils.lua`: воспроизведена исходная ошибка, затем проверены локальный ID, порядок, отсутствие фиктивного Workshop ID, дедупликация Workshop-списка, повторная установка hook, обычный Workshop-мод, реальные missing-моды и пустой список. Сам интерфейс игры в этой проверке не запускался.

В установленном `projectzomboid.jar` из `<SteamLibrary>/steamapps/common/ProjectZomboid` (Steam build ID `25485521`, ветка `public`) проверены следующие контракты:

- `ConnectionDetails.writeMods(ByteBufferWriter)` перечисляет `GameServer.ServerMods` при формировании требований клиента.
- `GameServer.doMinimumInit()` сначала копирует список для загрузки модов; `ZomboidFileSystem.getModIDs()` хранит отдельный список загруженных модов.
- Серверный флаг называется `GameServer.server`; в адаптере предусмотрен fallback `bServer` для исторических реализаций. Ранее использовавшееся только `bServer` было несовместимо с установленным классом и исправлено.

`OptionalClientModAdapter` вызывается на первом серверном `OnTick`, после загрузки модов. Он проверяет, что WorkshopSentinel загружен, списки независимы и серверный флаг установлен, затем удаляет только точные ID `WorkshopSentinel` и `\WorkshopSentinel` из объявляемого списка. `.ini`, активный список файловой системы, другие зависимости, `WorkshopItems` и проверка контрольных сумм не изменяются. Повторное применение безопасно. Чтобы отключить это поведение, задайте `clientOptional=false` и перезапустите сервер.

Это интеграция с проверенными установленными классами, а не универсальный флаг `serverOnly` в `mod.info`. **TODO:** сетевой smoke-test подключения клиента без WorkshopSentinel и проверка порядка первого tick относительно подключения на каждой поддерживаемой версии B42. Если другие клиентские моды явно требуют WorkshopSentinel, такая транзитивная зависимость тоже требует отдельного пересмотра. Опубликованный item из `WorkshopItems` может оставаться обязательным для скачивания.

## Дополнительные настройки

Основные интервалы и установка описаны в [README](../README.md). Эти параметры нужны для нестандартного размещения файлов или диагностики:

| Настройка | По умолчанию | Назначение |
|---|---|---|
| `tickMillis` | `1000` | Минимальный интервал обработки игрового tick, миллисекунды |
| `httpTimeoutSeconds` | `20` | Таймаут одного HTTP-запроса |
| `serverIni` | `<cachedir>/Server/<serverName>.ini` | Явный путь к серверному `.ini` |
| `workshopIds` | пусто | Список ID через `;` вместо чтения `WorkshopItems` |
| `triggerFile` | `simulate-update.txt` | Файл имитации для провайдера `file` |
| `markerFile` | `restart-marker.properties` | Файл последнего запроса перезапуска |
| `installedBaselineFile` | пусто | Карта подтверждённых установленных ревизий |

Для отдельного файла конфига задайте JVM-параметр `-Dworkshopsentinel.config=/absolute/path/WorkshopSentinel.properties`. Относительные пути внутри конфига считаются от его папки.

Значения `true`/`false` должны быть записаны точно так. Неизвестные провайдеры и адаптеры, некорректные ID или интервалы вне границ блокируют инициализацию. Файловый провайдер не допускает сочетания `dryRun=false` и `shutdownEnabled=true`.

Список `WorkshopItems` читается перед каждой проверкой. Удаление ID не отменяет обновление, уже обнаруженное в текущем процессе. Для новой сессии требуется перезапуск JVM; старый marker не исполняется автоматически.

## Клиентский сканер 0.4.0

Клиентская реализация независима от серверного WorkshopUpdateProvider и Java bridge. Адаптер использует публичные Lua-функции установленного Build 42: getActivatedMods, getModInfoByID, ArrayList.new, querySteamWorkshopItemDetails(ids, callback, context). Callback получает context, статус Completed/NotCompleted и Java-list SteamUGCDetails; проверены getIDString/getTimeUpdated/getState/getTitle. Состояния NeedsUpdate/Installed/Downloading доступны в фактическом байткоде SteamUGCDetails.getState.

Scheduler привязан к OnPreUIDraw, подтверждённому в UIManager, чтобы работать до входа в мир; OnTick для этого не используется. Пакеты до 100 ID, пауза 4 секунды, cooldown 60 секунд, таймаут 30 секунд. Generation и request identity отсекают поздние callbacks. Полнота каждого пакета проверяется; кеш коммитится после всех пакетов. Cache reader/writer работают только в пользовательской Lua-папке. ChangeLog читается штатным getModFileReader(modId, "ChangeLog.txt", false): установленная реализация ищет сначала version dir, затем common dir; файлы мода не создаются.

Интерфейс использует штатные ISPanel/ISButton/ISScrollingListBox/ISRichTextPanel, Clipboard.setClipboard, activateSteamOverlayToWorkshopItem и встроенный PZAPI.ModOptions. Данные ChangeLog экранируются перед rich-text выводом. Это собственная реализация по описанию функций Workshop Update Checker; код/ресурсы работы 3628835042 не включены.

Поля mod.info incompatible и loadModAfter подтверждены по ChooseGameInfo: разделитель — запятая, обратные косые черты удаляются парсером. Обе работы 3756814990 и 3781306534 объявляют Mod ID ServerAutoUpdate_B42. Конфликт объявлен по пересечению задач управления перезапуском. Порядок после MLOS/ZombieBuddy необязателен и не создаёт require.

## WorkshopUpdateProvider: границы достоверности

Интерфейс получает **только ID из конфигурации этого сервера**. Подписки Steam-пользователя и все папки Workshop на машине не перебираются: среди них могут быть работы других серверов. Ошибка провайдера выбрасывает исключение, логируется и не меняет таймер pending.

- `noop`: заглушка без обновлений.
- `file`: читает ID из `triggerFile` и пересекает их с настроенными ID. Файл не удаляется. Предназначен для воспроизводимых dry-run тестов.
- `steam`: HTTPS POST к публичному `ISteamRemoteStorage/GetPublishedFileDetails/v1/`, без ключа API, пакетами до 100 работ, XML-ответ. Проверяются ID, успешность каждой работы, app ID `108600`, полнота ответа и положительный `time_updated`. Ошибки HTTP, недоступная/удалённая работа или неполный пакет не трактуются как обновления. Baseline фиксируется только после успеха всех пакетов.

**Без `installedBaselineFile` первый успешный ответ создаёт baseline только в памяти данной JVM.** Обнаруживается последующее увеличение `time_updated`. Это не сравнение с установленными файлами: обновление, опубликованное до первой успешной проверки, может быть пропущено. `time_updated` также может отражать изменение Workshop-описания/метаданных, поэтому обнаружение не доказывает изменение содержимого JAR/Lua. Baseline намеренно не обновляется после обнаружения и не переносится между JVM: автоматически сохранённый удалённый baseline мог бы вызывать цикл рестартов после уже выполненного обновления или скрывать устаревшую установку.

Чтобы обнаруживать устаревшую ревизию сразу после запуска, администратор может передать **подтверждённый** baseline установленных работ:

```properties
# installed-workshop.properties: Workshop ID = time_updated установленной ревизии, Unix seconds
3619862853=REPLACE_WITH_VERIFIED_NUMERIC_TIMESTAMP
```

Это пример формата, а не готовый файл: замените placeholder числом из достоверной установленной ревизии. Не используйте время изменения папки или произвольный текущий ответ Steam как доказательство установленной версии. После фактического обновления baseline необходимо актуализировать; иначе возможны повторные запросы при следующих запусках. Для ID, отсутствующих в карте, используется session baseline и пишется соответствующий лог.

**TODO:** надёжная сверка с установленной/загруженной ревизией конкретного B42 через его Steam UGC API, включая async callbacks и их очистку. Нативные вызовы SteamWorkshop и скачивание внутри работающего сервера здесь намеренно не реализованы без подтверждённого контракта. Обновление файлов выполняет штатный запуск PZ или ваш supervisor после корректного завершения сервера.

## Shutdown и restart marker

`ShutdownAdapter` отделён от state machine. Для вызова нужны **оба** условия `dryRun=false` и `shutdownEnabled=true`. Адаптер `marker` даже при этих значениях только пишет лог: самостоятельного останова у него нет.

Экспериментальный режим требует третьего явного выбора:

```properties
dryRun=false
shutdownEnabled=true
shutdownAdapter=pz-quit-experimental
```

**TODO / неподтверждённый B42 API:** `ExperimentalPzShutdownAdapter` проверяет историческую публичную сигнатуру `zombie.network.ServerMap.instance.QueueQuit(): void`. Актуальная публичная документация B42 её не подтверждает. Если класс/поле/метод отсутствует, инициализация мода завершается с ошибкой; обходов, private reflection и kill нет. Даже при наличии сигнатуры семантику сохранения мира и завершения JVM необходимо проверить на отдельном тестовом сервере. Адаптер не вызывает `System.exit`, `Runtime.halt`, shell-команды или запуск новой JVM. Для повторного старта нужен внешний supervisor, которого этот проект не устанавливает.

Marker записывается перед вызовом адаптера: `schema`, `requestId`, `requestedAtUtc`, `dryRun`, `shutdownEnabled`, `adapter`, `reason`, `workshopIds`, `updateAgeMillis`, `status`. Он не содержит секретов. Запись идёт через temp file, flush/sync и атомарное перемещение; неподдерживаемое атомарное перемещение блокирует shutdown. Это запись **запроса**, а не подтверждение завершения/сохранения/перезапуска.

Мод никогда не исполняет старый marker при запуске и не восстанавливает из него countdown. Внешний supervisor должен отдельно проверять `dryRun=false`, допустимый adapter/status, свежесть и уникальность `requestId`, затем проверять реальное завершение процесса. Не используйте одно лишь наличие файла как команду kill. Если shutdown бросил исключение после частичного успеха, мод логирует ошибку и не повторяет вызов автоматически.


## Проверенные источники и TODO

Проверено по upstream 2026-10-08; это фиксация исследования, а не обещание совместимости с любым будущим B42.

- [ZombieBuddy Modding Guide](https://github.com/zed-0xff/ZombieBuddy/blob/master/doc/ModdingGuide.md): versioned layout, server-only path, `Main.main`, manual Lua exposure.
- [ZombieBuddy Exposer source](https://github.com/zed-0xff/ZombieBuddy/blob/master/java/src/main/java/me/zed_0xff/zombie_buddy/Exposer.java): `exposeClass(Class)` и регистрация класса в Lua. В проекте вызов изолирован через reflection, поэтому для сборки не нужен распространяемый чужой JAR.
- [ZombieBuddy Installation](https://github.com/zed-0xff/ZombieBuddy/blob/master/doc/Installation.md) и [upstream README](https://github.com/zed-0xff/ZombieBuddy): установка agent и текущие требования runtime.
- [PZ GameServer Javadocs](https://projectzomboid.com/modding/zombie/network/GameServer.html): `getPlayerCount()`; fallback `getPlayers()` без параметров. Эти методы вызываются через `PzGameAdapter`.
- [PZ ChatServer Javadocs](https://projectzomboid.com/modding/zombie/network/chat/ChatServer.html): `getInstance()` и `sendServerAlertMessageToServerChat(String)`.
- [PZ LuaEventManager Javadocs](https://projectzomboid.com/modding/zombie/Lua/LuaEventManager.html): tick callbacks; **TODO:** проверить серверное `Events.OnTick` и порядок Lua exposure на вашем B42.
- [Valve GetPublishedFileDetails](https://partner.steamgames.com/doc/webapi/ISteamRemoteStorage#GetPublishedFileDetails) и [форматы ответов](https://partner.steamgames.com/doc/webapi_overview/responses): публичный POST и XML. Форма ответа дополнительно проверена живым запросом к работе ZombieBuddy.

**Оставшиеся интеграционные TODO:** точные сигнатуры игроков/чата и порядок tick на deployed B42; проверка `ZomboidFileSystem.getCacheDir()` (есть явно логируемый fallback и override); установленная UGC revision; семантика graceful quit. При отсутствии API инициализация отключается с логом; при отсутствии Lua bridge печатается явное предупреждение. Полноценного игрового smoke-test в этом рабочем пространстве не проводилось.
