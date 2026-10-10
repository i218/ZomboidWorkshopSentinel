-- Optional administrator UI integration in the unified mod package.
-- Enable WorkshopSentinel in the main-menu Mods list when using MLOS to edit a server.
if isServer() or isClient() then return end

local function itemId(item)
    local data = item and item.item or {}
    return data.modID or data.modId or ""
end

local function install()
    local active = getActivatedMods()
    if not active:contains("ModLoadOrderSorter_b42") and not active:contains("\\ModLoadOrderSorter_b42") then
        return
    end
    local ok, utils = pcall(require, "OptionScreens/ModSelector/Refr_utils")
    if not ok or type(utils) ~= "table" or type(utils.getModsIDs) ~= "function" or type(utils.getWorkshopId) ~= "function" then
        print("[WorkshopSentinel] MLOS compatibility unavailable: unsupported Refr_utils API")
        return
    end
    if utils._workshopSentinelLocalCompat then return end
    local original = utils.getModsIDs
    utils.getModsIDs = function(self, items, ...)
        local filtered, localEntries, hasLocal = {}, {}, false
        for i, item in ipairs(items) do
            local id = itemId(item)
            local info = item.item and item.item.modInfo
            if not info and id ~= "" then
                local found, value = pcall(getModInfoByID, id:gsub("^\\", ""))
                if found then info = value end
            end
            local identified, workshopId = false, nil
            if info then identified, workshopId = pcall(self.getWorkshopId, self, info) end
            if id ~= "" and info and identified and (workshopId == nil or workshopId == "" or tostring(workshopId) == "0") then
                localEntries[i] = true
                hasLocal = true
            else
                table.insert(filtered, item)
            end
        end
        if not hasLocal then return original(self, items, ...) end
        -- MLOS still validates every other item, including genuinely missing mods.
        local modIDs, workshopIDs = original(self, filtered, ...)
        local retained = {}
        for _, id in ipairs(modIDs) do retained[id] = (retained[id] or 0) + 1 end
        local ordered = {}
        for i, item in ipairs(items) do
            local id = itemId(item)
            if localEntries[i] then
                table.insert(ordered, id)
            elseif (retained[id] or 0) > 0 then
                table.insert(ordered, id)
                retained[id] = retained[id] - 1
            end
        end
        -- No fabricated Workshop ID; preserve the list/deduplication produced by MLOS.
        return ordered, workshopIDs
    end
    utils._workshopSentinelLocalCompat = true
    print("[WorkshopSentinel] MLOS compatibility enabled: installed local mods retained without Workshop IDs")
end

Events.OnMainMenuEnter.Add(install)
