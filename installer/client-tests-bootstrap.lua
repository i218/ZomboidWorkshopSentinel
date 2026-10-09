ipairs, pairs = table.ipairs, table.pairs
-- Use the runtime assert so failures retain their message.
function print(...) end
function isServer() return false end
testTime, testSteam = 100000, true
function getTimestampMs() return testTime end
function getSteamModeActive() return testSteam end
function list(values)
    return { size = function() return #values end, get = function(self, i) return values[i+1] end,
        add = function(self, value) table.insert(values, value) end, values = values }
end
ArrayList = { new = function() return list({}) end }
testActive, testMods = {}, {}
function getActivatedMods() return list(testActive) end
function getModInfoByID(id) return testMods[id] end
function info(id, workshop, version)
    return { getWorkshopID = function() return workshop end, getDir = function() return "/mods/" .. id end,
        getName = function() return id end, getModVersion = function() return version end }
end
testFiles = {}
function getFileReader(file, create)
    local text = testFiles[file]
    if not text then return nil end
    local lines = {}; for line in text:gmatch("([^\n]+)") do table.insert(lines, line) end
    local cursor = 0
    return { readLine = function() cursor = cursor + 1; return lines[cursor] end, close = function() end }
end
function getFileWriter(file, create, append)
    -- Mirror B42's allowed-extension gate rather than accepting arbitrary names.
    if testWriterUnavailable or not file:match("%.txt$") then return nil end
    local text = ""
    return { write = function(self, value) text = text .. value end,
        close = function() testFiles[file] = text end }
end
function getModFileReader(id, file, create) return getFileReader(id .. "/" .. file, create) end
testEvents = { ticks = {}, menus = {}, games = {} }
Events = { OnPreUIDraw = { Add = function(fn) table.insert(testEvents.ticks, fn) end },
    OnMainMenuEnter = { Add = function(fn) table.insert(testEvents.menus, fn) end },
    OnGameStart = { Add = function(fn) table.insert(testEvents.games, fn) end } }
testRequests = {}
function querySteamWorkshopItemDetails(ids, callback, context)
    table.insert(testRequests, { ids = ids, callback = callback, context = context })
end
function detail(id, stamp, state)
    return { getIDString = function() return id end, getTimeUpdated = function() return stamp end,
        getState = function() return state or "Installed" end, getTitle = function() return "Workshop " .. id end }
end
