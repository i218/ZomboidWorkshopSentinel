-- Independent implementation using the game's public Lua/Steam API. No client Java agent.
if isServer() then return end
WorkshopSentinelClient = WorkshopSentinelClient or {}
local C = WorkshopSentinelClient
-- B42 restricts getFileWriter extensions; tab-separated content is stored in an allowed .txt file.
C.cacheFile = "WorkshopSentinel-client-cache.txt"
C.rows, C.baseline, C.listeners = {}, {}, {}
C.busy, C.generation, C.lastStart = false, 0, -60000
C.autoIntervalMs = 20 * 60 * 1000
function C.enableAutomaticChecks()
    C.automatic = true
    if not C.nextAutoAt then C.nextAutoAt = getTimestampMs() end
end
local function clean(value) return (tostring(value or ""):gsub("[\t\r\n]", " ")) end
local function now() return getTimestampMs() end
function C.notify()
    for _, listener in ipairs(C.listeners) do
        local ok, err = pcall(listener, C)
        if not ok then print("[WorkshopSentinel client] UI listener: " .. tostring(err)) end
    end
end
function C.loadCache()
    C.baseline = {}
    local reader
    local ok, err = pcall(function()
        reader = getFileReader(C.cacheFile, false)
        if not reader then return end
        for i = 1, 20000 do
            local line = reader:readLine()
            if not line then break end
            local id, stamp, version = line:match("^(%d+)\t(%d+)\t(.*)$")
            if id then C.baseline[id] = { updated = tonumber(stamp), version = version } end
        end
    end)
    if reader then pcall(function() reader:close() end) end
    if not ok then print("[WorkshopSentinel client] Cache read: " .. tostring(err)) end
end
function C.saveCache(records)
    local writer
    local ok, result, reason = pcall(function()
        writer = getFileWriter(C.cacheFile, true, false)
        if not writer then return false, "Cache writer unavailable: " .. C.cacheFile end
        local ids = {}
        for id in pairs(records) do table.insert(ids, id) end
        table.sort(ids)
        for _, id in ipairs(ids) do
            local r = records[id]
            writer:write(id .. "\t" .. r.updated .. "\t" .. clean(r.version) .. "\n")
        end
        writer:close()
        writer = nil
        return true
    end)
    if writer then pcall(function() writer:close() end) end
    if not ok then return false, result end
    return result, reason
end
function C.resetCache()
    if C.busy then return false end
    local ok, err = C.saveCache({})
    if ok then C.baseline, C.rows = {}, {}; C.status = "reset"
    else C.status = "cache-error"; print("[WorkshopSentinel client] " .. tostring(err)) end
    C.notify()
    return ok
end
function C.collect()
    local active, groups = getActivatedMods(), {}
    for i = 0, active:size() - 1 do
        local modId = tostring(active:get(i)):gsub("^\\", "")
        local info = getModInfoByID(modId)
        if info then
            local id = info:getWorkshopID()
            if not id or id == "" then id = tostring(info:getDir()):gsub("\\", "/"):match("/108600/(%d+)/") end
            if id and tostring(id):match("^%d+$") then
                id = tostring(id)
                local group = groups[id] or { id = id, mods = {}, names = {}, versions = {} }
                groups[id] = group
                table.insert(group.mods, modId)
                table.insert(group.names, clean(info:getName()))
                table.insert(group.versions, clean(modId) .. "=" .. clean(info:getModVersion()))
            end
        end
    end
    local rows = {}
    for _, group in pairs(groups) do
        table.sort(group.versions)
        group.version = table.concat(group.versions, "; ")
        group.title = table.concat(group.names, ", ")
        table.insert(rows, group)
    end
    table.sort(rows, function(a, b) return a.id < b.id end)
    return rows
end
function C.fail(reason)
    C.busy, C.pending, C.nextAt = false, nil, nil
    C.generation = C.generation + 1 -- Ignore late callbacks, including after timeout.
    C.status = reason
    C.notify()
end
function C.complete()
    C.rows, C.busy, C.pending, C.nextAt = C.scanRows, false, nil, nil
    local records, changed = {}, 0
    for _, row in ipairs(C.rows) do
        local previous = C.baseline[row.id]
        row.available = row.state == "NeedsUpdate"
        row.changed = previous and (row.updated > previous.updated or row.version ~= previous.version) or false
        row.first = previous == nil
        if row.available or row.changed then changed = changed + 1 end
        records[row.id] = { updated = row.updated, version = row.version }
        -- New items get a session baseline too, so subsequent manual scans can detect changes.
        if not previous then C.baseline[row.id] = { updated = row.updated, version = row.version } end
    end
    C.changedCount = changed
    local ok, err = C.saveCache(records)
    C.status = ok and "complete" or "cache-error"
    if not ok then print("[WorkshopSentinel client] Cache write: " .. tostring(err)) end
    C.notify()
end
function C.start()
    if C.busy then return false end
    if now() - C.lastStart < 60000 then C.status = "cooldown"; C.notify(); return false end
    if not getSteamModeActive() then C.status = "no-steam"; C.notify(); return false end
    local ok, rows = pcall(C.collect)
    if not ok then print("[WorkshopSentinel client] " .. tostring(rows)); C.fail("api-error"); return false end
    C.lastStart, C.scanRows = now(), rows
    if #rows == 0 then C.rows = {}; C.status = "empty"; C.notify(); return false end
    C.busy, C.cursor, C.nextAt = true, 1, now()
    C.generation = C.generation + 1
    C.status = "checking"
    C.notify()
    return true
end
function C.tick()
    local main = MainScreen and MainScreen.instance
    if C.automatic and main and not main.inGame and not C.busy and now() >= C.nextAutoAt then
        -- Schedule before starting, including failures and empty mod lists; never retry every frame.
        C.nextAutoAt = now() + C.autoIntervalMs
        C.start()
    end
    if not C.busy then return end
    if C.pending then
        if now() - C.pending.started > 30000 then C.fail("timeout") end
        return
    end
    if now() < C.nextAt then return end
    if C.cursor > #C.scanRows then C.complete(); return end
    local ids, expected = ArrayList.new(), {}
    local last = math.min(C.cursor + 99, #C.scanRows)
    for i = C.cursor, last do
        local row = C.scanRows[i]
        ids:add(row.id); expected[row.id] = row
    end
    local request = { started = now(), generation = C.generation, expected = expected, last = last }
    C.pending = request
    local ok, err = pcall(querySteamWorkshopItemDetails, ids, function(context, status, details)
        if context ~= C.pending or context.generation ~= C.generation then return end
        if status ~= "Completed" then C.fail("steam-error"); return end
        local parsed, seen = {}, {}
        local valid, parseError = pcall(function()
            for i = 0, details:size() - 1 do
                local detail = details:get(i)
                local id, stamp = tostring(detail:getIDString()), tonumber(detail:getTimeUpdated())
                if not context.expected[id] or seen[id] or not stamp or stamp <= 0 then error("Unexpected Steam response") end
                seen[id] = true
                parsed[id] = { updated = stamp, state = detail:getState(), title = clean(detail:getTitle()) }
            end
            for id in pairs(context.expected) do if not seen[id] then error("Incomplete Steam response") end end
        end)
        if not valid then print("[WorkshopSentinel client] " .. tostring(parseError)); C.fail("steam-error"); return end
        for id, data in pairs(parsed) do
            local row = context.expected[id]
            row.updated, row.state, row.workshopTitle = data.updated, data.state, data.title
        end
        C.pending, C.cursor, C.nextAt = nil, context.last + 1, now() + 4000
    end, request)
    if not ok then print("[WorkshopSentinel client] " .. tostring(err)); C.fail("api-error") end
end
function C.changelog(row)
    local parts = {}
    for _, modId in ipairs(row.mods) do
        local reader
        local ok = pcall(function()
            -- B42 reader checks version dir, then common. Never creates mod files.
            reader = getModFileReader(modId, "ChangeLog.txt", false)
            if not reader then return end
            local lines, count = {}, 0
            for i = 1, 250 do
                local line = reader:readLine()
                if not line then break end
                count = count + #line
                if count > 24000 then break end
                table.insert(lines, line)
            end
            table.insert(parts, modId .. "\n" .. table.concat(lines, "\n"))
        end)
        if reader then pcall(function() reader:close() end) end
        if not ok then print("[WorkshopSentinel client] Could not read ChangeLog: " .. modId) end
    end
    return table.concat(parts, "\n\n")
end
C.loadCache()
print("[WorkshopSentinel client] Model loaded v0.4.8; client updates do not use the Java bridge")
-- UI frames run in the main menu too; OnTick is not reliable before entering a world.
Events.OnPreUIDraw.Add(C.tick)
